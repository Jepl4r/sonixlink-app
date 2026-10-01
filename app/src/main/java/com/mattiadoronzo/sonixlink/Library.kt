package com.mattiadoronzo.sonixlink

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The player's index on the phone: its SQLite file, downloaded verbatim and
 * opened read-only.
 *
 * Never emit `COLLATE listorder`: it is the player's own collation and
 * Android's SQLite does not have it, so the query fails. `ORDER BY sortkey`
 * gives the same order, since the scan builds `sortkey` for that purpose.
 *
 * Each list mirrors the player's query (library.c, library_index_open): by
 * sort key or date added and reversed per [SortPrefs], albums by disc and
 * track number, ties by row id. Albums with the same name are told apart by
 * `MEDIA_TABLE.album_key` where the index has it.
 */
class Library(context: Context) {

    private val file = File(context.filesDir, "library.db")
    private var db: SQLiteDatabase? = null

    // Guards every use of `db`: a sync closes and reopens the file while a tab
    // may be mid-query, and closing under a reader throws IllegalStateException
    // (not an SQLiteException).
    private val gate = Any()

    val databaseFile: File get() = file
    val exists: Boolean get() = file.exists() && file.length() > 0

    /**
     * Optional parts of the index, depending on the firmware that wrote it: the
     * `disc` column, and album keys (ALBUM_GROUP_TABLE with
     * `MEDIA_TABLE.album_key`). Queries leave out whichever is missing.
     */
    private var hasDisc = false
    private var hasAlbumKeys = false

    /** Opens the downloaded index. False when it is missing or unreadable. */
    fun open(): Boolean = synchronized(gate) {
        closeLocked()
        if (!exists) return@synchronized false
        try {
            val database = SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            val columns = HashSet<String>()
            database.rawQuery("PRAGMA table_info(MEDIA_TABLE)", null).use { cursor ->
                val name = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) columns.add(cursor.getString(name).orEmpty())
            }
            var groups = false
            database.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='ALBUM_GROUP_TABLE'", null,
            ).use { groups = it.moveToFirst() }
            hasDisc = "disc" in columns
            hasAlbumKeys = groups && "album_key" in columns
            db = database
            true
        } catch (e: Exception) {
            db = null
            false
        }
    }

    /** Disc and track number, the player's TRACK_ORDER_IN_ALBUM. */
    private val inAlbum: String get() = if (hasDisc) "COALESCE(disc,1), dis_id" else "dis_id"

    /** A list read backwards when the player's is. */
    private fun List<Row>.maybeReversed(reverse: Boolean): List<Row> = if (reverse) asReversed() else this

    /** "name" + 0x1f + key, as the player's album rows carry it, split in two. */
    private fun splitAlbum(value: String): Pair<String, String?> {
        val at = value.indexOf('\u001f')
        return if (at < 0) value to null else value.substring(0, at) to value.substring(at + 1)
    }

    fun close() {
        synchronized(gate) { closeLocked() }
    }

    private fun closeLocked() {
        try {
            db?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        db = null
    }

    private fun query(sql: String, args: Array<String> = emptyArray()): List<Row> = synchronized(gate) {
        val database = db ?: return@synchronized emptyList()
        val rows = ArrayList<Row>()
        try {
            database.rawQuery(sql, args).use { cursor ->
                val title = cursor.getColumnIndex("row_title")
                val subtitle = cursor.getColumnIndex("row_subtitle")
                val path = cursor.getColumnIndex("row_path")
                val filter = cursor.getColumnIndex("row_filter")
                val count = cursor.getColumnIndex("row_count")
                val album = cursor.getColumnIndex("row_album")
                // The thumbnail's source track: path, mtime and size make up
                // the player's thumbnail key.
                val artPath = cursor.getColumnIndex("row_art_path")
                val artMtime = cursor.getColumnIndex("row_art_mtime")
                val artSize = cursor.getColumnIndex("row_art_size")
                val sortKey = cursor.getColumnIndex("row_sortkey")
                while (cursor.moveToNext()) {
                    rows.add(
                        Row(
                            title = if (title >= 0) cursor.getString(title).orEmpty() else "",
                            subtitle = if (subtitle >= 0) cursor.getString(subtitle).orEmpty() else "",
                            path = if (path >= 0) cursor.getString(path).orEmpty() else "",
                            filter = if (filter >= 0) cursor.getString(filter).orEmpty() else "",
                            count = if (count >= 0) cursor.getInt(count) else 0,
                            album = if (album >= 0) cursor.getString(album).orEmpty() else "",
                            artPath = if (artPath >= 0) cursor.getString(artPath).orEmpty() else "",
                            artMtime = if (artMtime >= 0) cursor.getLong(artMtime) else 0,
                            artSize = if (artSize >= 0) cursor.getLong(artSize) else 0,
                            sortKey = if (sortKey >= 0) cursor.getString(sortKey).orEmpty() else "",
                        )
                    )
                }
            }
        } catch (e: Exception) {
            return@synchronized emptyList()
        }
        rows
    }

    fun trackCount(): Int = synchronized(gate) {
        val database = db ?: return@synchronized 0
        try {
            database.rawQuery("SELECT COUNT(*) FROM MEDIA_TABLE", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } catch (e: Exception) {
            0
        }
    }

    // -----------------------------------------------------------------------
    // The sections
    // -----------------------------------------------------------------------

    fun tracks(sort: SortPrefs = Session.sort): List<Row> {
        val order = if (sort.byDate(SortPrefs.TRACKS)) "ctime, sortkey, rowid" else "sortkey, rowid"
        return query(
            """SELECT name AS row_title, artist AS row_subtitle, path AS row_path, album AS row_album,
                      path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size,
                      SUBSTR(sortkey, 1, 2) AS row_sortkey
               FROM MEDIA_TABLE WHERE path <> '' ORDER BY $order"""
        ).maybeReversed(sort.reversed(SortPrefs.TRACKS))
    }

    // The `cn` column of the album, artist and genre tables is not a track
    // count: it holds the A-Z strip's alphabet group (`index_character`).
    // Counts come from COUNT(*) over MEDIA_TABLE.
    //
    // Album rows carry no cover track: Covers picks one that has a thumbnail.
    fun albums(sort: SortPrefs = Session.sort): List<Row> {
        val byDate = sort.byDate(SortPrefs.ALBUMS)
        val rows = if (hasAlbumKeys) {
            // One row per (album, album_key), as the player lists them. The
            // filter carries both, joined by 0x1f, so opening or playing it
            // reaches that album alone and not every album of the same name.
            val order = if (byDate) {
                "(SELECT MAX(m.ctime) FROM MEDIA_TABLE m WHERE m.album = g.album AND m.album_key = g.album_key), sortkey"
            } else {
                "sortkey"
            }
            query(
                """SELECT album AS row_title, '' AS row_subtitle, album || char(31) || album_key AS row_filter,
                          (SELECT COUNT(*) FROM MEDIA_TABLE m
                            WHERE m.album = g.album AND m.album_key = g.album_key AND m.path <> '') AS row_count,
                          album AS row_album, SUBSTR(sortkey, 1, 2) AS row_sortkey
                   FROM ALBUM_GROUP_TABLE g WHERE album <> '' ORDER BY $order, album, album_key"""
            )
        } else {
            val order = if (byDate) "(SELECT MAX(m.ctime) FROM MEDIA_TABLE m WHERE m.album = t.album), sortkey" else "sortkey"
            query(
                """SELECT album AS row_title, '' AS row_subtitle, album AS row_filter,
                          (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.album = t.album AND m.path <> '') AS row_count,
                          album AS row_album, SUBSTR(sortkey, 1, 2) AS row_sortkey
                   FROM ALBUM_TABLE t WHERE album <> '' ORDER BY $order, album"""
            )
        }
        return rows.maybeReversed(sort.reversed(SortPrefs.ALBUMS))
    }

    fun artists(sort: SortPrefs = Session.sort): List<Row> {
        val order = if (sort.byDate(SortPrefs.ARTISTS)) {
            "(SELECT MAX(m.ctime) FROM MEDIA_TABLE m WHERE m.artist = t.artist), sortkey"
        } else {
            "sortkey"
        }
        return query(
            """SELECT artist AS row_title, '' AS row_subtitle, artist AS row_filter,
                      (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.artist = t.artist AND m.path <> '') AS row_count,
                      SUBSTR(sortkey, 1, 2) AS row_sortkey
               FROM ARTIST_TABLE t WHERE artist <> '' ORDER BY $order, artist"""
        ).maybeReversed(sort.reversed(SortPrefs.ARTISTS))
    }

    fun albumArtists(sort: SortPrefs = Session.sort): List<Row> {
        val order = if (sort.byDate(SortPrefs.ALBUM_ARTISTS)) {
            "(SELECT MAX(m.ctime) FROM MEDIA_TABLE m WHERE m.album_artist = t.album_artist), sortkey"
        } else {
            "sortkey"
        }
        return query(
            """SELECT album_artist AS row_title, '' AS row_subtitle, album_artist AS row_filter,
                      (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.album_artist = t.album_artist AND m.path <> '') AS row_count,
                      SUBSTR(sortkey, 1, 2) AS row_sortkey
               FROM ALBUM_ARTIST_TABLE t WHERE album_artist <> '' ORDER BY $order, album_artist"""
        ).maybeReversed(sort.reversed(SortPrefs.ALBUM_ARTISTS))
    }

    fun genres(): List<Row> = query(
        """SELECT genre AS row_title, '' AS row_subtitle, genre AS row_filter,
                  (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.genre = t.genre AND m.path <> '') AS row_count
           FROM GENRE_TABLE t WHERE genre <> '' ORDER BY sortkey, genre"""
    )

    /** Favourites oldest-starred first, or newest first when the player's list is reversed. */
    fun favourites(sort: SortPrefs = Session.sort): List<Row> = query(
        """SELECT f.name AS row_title, f.artist AS row_subtitle, f.path AS row_path,
                  f.path AS row_art_path,
                  (SELECT m.mtime FROM MEDIA_TABLE m WHERE m.path = f.path) AS row_art_mtime,
                  (SELECT m.size FROM MEDIA_TABLE m WHERE m.path = f.path) AS row_art_size
           FROM FAVOURITES f ORDER BY f.added_at, f.rowid"""
    ).maybeReversed(sort.favouritesReversed)

    /** Playlists: one table each, named "M3U_" plus the playlist's name, found in sqlite_master. */
    fun playlists(): List<Row> = synchronized(gate) {
        val database = db ?: return@synchronized emptyList()
        val rows = ArrayList<Row>()
        try {
            database.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'M3U\\_%' ESCAPE '\\' ORDER BY name",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val table = cursor.getString(0) ?: continue
                    val label = table.removePrefix("M3U_")
                    var count = 0
                    try {
                        database.rawQuery("SELECT COUNT(*) FROM ${quoted(table)}", null).use {
                            if (it.moveToFirst()) count = it.getInt(0)
                        }
                    } catch (e: Exception) {
                        // An unreadable table is listed with a count of 0.
                    }
                    rows.add(Row(title = label, subtitle = "", filter = table, count = count))
                }
            }
        } catch (e: Exception) {
            return@synchronized emptyList()
        }
        rows
    }

    // -----------------------------------------------------------------------
    // Inside a category
    // -----------------------------------------------------------------------

    /**
     * One album's tracks by disc and track number. `album` is an album row's
     * filter: the name, optionally followed by 0x1f and the album key. A bare
     * name, or an index without album keys, matches every album of that name.
     */
    fun tracksOfAlbum(album: String): List<Row> {
        val (name, key) = splitAlbum(album)
        val select = """SELECT name AS row_title, artist AS row_subtitle, path AS row_path, album AS row_album,
                               path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size
                        FROM MEDIA_TABLE"""
        return if (key != null && hasAlbumKeys) {
            query("$select WHERE album = ? AND album_key = ? AND path <> '' ORDER BY $inAlbum, sortkey, rowid", arrayOf(name, key))
        } else {
            query("$select WHERE album = ? AND path <> '' ORDER BY $inAlbum, sortkey, rowid", arrayOf(name))
        }
    }

    /**
     * An artist's tracks, as the player's artist page lists them: by sort key,
     * or grouped by album in disc and track order when [SortPrefs.artistByAlbum].
     */
    private fun tracksOfPerson(column: String, value: String, sort: SortPrefs): List<Row> {
        val order = if (sort.artistByAlbum) {
            "(SELECT a.sortkey FROM ALBUM_TABLE a WHERE a.album = m.album), album, $inAlbum, sortkey, rowid"
        } else {
            "sortkey, rowid"
        }
        return query(
            """SELECT name AS row_title, album AS row_subtitle, path AS row_path, album AS row_album,
                      path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size
               FROM MEDIA_TABLE m WHERE $column = ? AND path <> '' ORDER BY $order""",
            arrayOf(value),
        )
    }

    fun tracksOfArtist(artist: String, sort: SortPrefs = Session.sort): List<Row> =
        tracksOfPerson("artist", artist, sort)

    fun tracksOfAlbumArtist(albumArtist: String, sort: SortPrefs = Session.sort): List<Row> =
        tracksOfPerson("album_artist", albumArtist, sort)

    fun tracksOfGenre(genre: String): List<Row> = query(
        """SELECT name AS row_title, artist AS row_subtitle, path AS row_path, album AS row_album,
                  path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size
           FROM MEDIA_TABLE WHERE genre = ? AND path <> '' ORDER BY sortkey, rowid""",
        arrayOf(genre),
    )

    /** A playlist, in the order it was built. */
    fun tracksOfPlaylist(table: String): List<Row> {
        if (!table.startsWith("M3U_")) return emptyList()
        return query(
            """SELECT p.title AS row_title, p.artist AS row_subtitle, p.path AS row_path,
                      p.path AS row_art_path,
                      (SELECT m.mtime FROM MEDIA_TABLE m WHERE m.path = p.path) AS row_art_mtime,
                      (SELECT m.size FROM MEDIA_TABLE m WHERE m.path = p.path) AS row_art_size
               FROM ${quoted(table)} p WHERE p.path <> '' ORDER BY p.idx"""
        )
    }

    /** A table name quoted as an SQL identifier: playlist names may contain quotes. */
    private fun quoted(table: String): String = "\"" + table.replace("\"", "\"\"") + "\""

    /**
     * Title, artist and album for a set of paths, keyed by path. The queue
     * routes send only paths, so their names are looked up here.
     */
    fun tracksByPaths(paths: List<String>): Map<String, Row> {
        val wanted = paths.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return emptyMap()

        val out = HashMap<String, Row>(wanted.size)
        // Chunked: older SQLite allows at most 999 placeholders per query.
        wanted.chunked(400).forEach { chunk ->
            val holes = chunk.joinToString(",") { "?" }
            query(
                """SELECT name AS row_title, artist AS row_subtitle, path AS row_path, album AS row_album,
                          path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size
                   FROM MEDIA_TABLE WHERE path IN ($holes)""",
                chunk.toTypedArray(),
            ).forEach { out[it.path] = it }
        }
        return out
    }

    /** Albums whose name contains the text, split by album key as in the Albums tab. */
    fun albumsMatching(text: String, limit: Int = 30): List<Row> {
        val like = likeOf(text) ?: return emptyList()
        if (hasAlbumKeys) {
            return query(
                """SELECT album AS row_title, '' AS row_subtitle, album || char(31) || album_key AS row_filter,
                          (SELECT COUNT(*) FROM MEDIA_TABLE m
                            WHERE m.album = g.album AND m.album_key = g.album_key AND m.path <> '') AS row_count,
                          album AS row_album
                   FROM ALBUM_GROUP_TABLE g WHERE album LIKE ? ESCAPE '\'
                   ORDER BY sortkey, album, album_key LIMIT $limit""",
                arrayOf(like),
            )
        }
        return query(
            """SELECT album AS row_title, '' AS row_subtitle, album AS row_filter,
                      (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.album = t.album AND m.path <> '') AS row_count,
                      album AS row_album
               FROM ALBUM_TABLE t WHERE album LIKE ? ESCAPE '\'
               ORDER BY sortkey, album LIMIT $limit""",
            arrayOf(like),
        )
    }

    /** Artists whose name contains the text. */
    fun artistsMatching(text: String, limit: Int = 30): List<Row> {
        val like = likeOf(text) ?: return emptyList()
        return query(
            """SELECT artist AS row_title, '' AS row_subtitle, artist AS row_filter,
                      (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.artist = t.artist AND m.path <> '') AS row_count
               FROM ARTIST_TABLE t WHERE artist LIKE ? ESCAPE '\'
               ORDER BY sortkey, artist LIMIT $limit""",
            arrayOf(like),
        )
    }

    /** The text as LIKE wants it, or null when there is nothing to search for. */
    private fun likeOf(text: String): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return null
        return "%" + clean.replace("%", "\\%").replace("_", "\\_") + "%"
    }

    /** Tracks whose title, artist or album contains the text. */
    fun search(text: String, limit: Int = 300): List<Row> {
        if (text.isBlank()) return emptyList()
        val like = "%" + text.trim().replace("%", "\\%").replace("_", "\\_") + "%"
        return query(
            """SELECT name AS row_title, artist AS row_subtitle, path AS row_path, album AS row_album,
                      path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size
               FROM MEDIA_TABLE
               WHERE path <> '' AND (name LIKE ? ESCAPE '\' OR artist LIKE ? ESCAPE '\'
                                     OR album LIKE ? ESCAPE '\')
               ORDER BY sortkey, name LIMIT $limit""",
            arrayOf(like, like, like),
        )
    }
}
