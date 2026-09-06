package com.mattiadoronzo.sonixlink

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The player's index, on the phone.
 *
 * The same SQLite file the player uses, downloaded verbatim: no conversion, no
 * paging over the wire, no format of our own to keep in step. The app opens it
 * read-only and runs its queries.
 *
 * On ordering: the player sorts with a collation of its own, `listorder`, which
 * does not exist here. It is not needed -- the `sortkey` column the scan writes
 * is built precisely so that `ORDER BY sortkey` answers the same as
 * `ORDER BY name COLLATE listorder`. Never emit `COLLATE listorder` from here:
 * Android's SQLite does not know it and the query fails.
 */
class Library(context: Context) {

    private val file = File(context.filesDir, "library.db")
    private var db: SQLiteDatabase? = null

    // Syncing closes and reopens this file while a tab may be halfway through a
    // query. Closing under a reader throws IllegalStateException, which is not
    // an SQLiteException: everyone goes through here.
    private val gate = Any()

    val databaseFile: File get() = file
    val exists: Boolean get() = file.exists() && file.length() > 0

    /** Opens the downloaded index. False when it is missing or unreadable. */
    fun open(): Boolean = synchronized(gate) {
        closeLocked()
        if (!exists) return@synchronized false
        try {
            db = SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            true
        } catch (e: Exception) {
            db = null
            false
        }
    }

    fun close() {
        synchronized(gate) { closeLocked() }
    }

    private fun closeLocked() {
        try {
            db?.close()
        } catch (e: Exception) {
            // Already closed: nothing to say.
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
                // The track the thumbnail comes from: path, mtime and size are
                // the three pieces of the key the player uses.
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
    // The six sections
    // -----------------------------------------------------------------------

    fun tracks(): List<Row> = query(
        """SELECT name AS row_title, artist AS row_subtitle, path AS row_path, album AS row_album,
                  path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size,
                  SUBSTR(sortkey, 1, 2) AS row_sortkey
           FROM MEDIA_TABLE WHERE path <> '' ORDER BY sortkey, name"""
    )

    // Beware the `cn` column: in the album, artist and genre tables it is not a
    // track count. The scan writes the alphabet group there (`index_character`),
    // which is what the player's A-Z strip reads -- the name is inherited from
    // the original firmware's schema. Counting means counting, and the column
    // being counted on is indexed.
    fun albums(): List<Row> = query(
        // The album's cover does not come from here: Covers picks it, knowing
        // which tracks actually have a thumbnail.
        """SELECT album AS row_title, '' AS row_subtitle, album AS row_filter,
                  (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.album = t.album AND m.path <> '') AS row_count,
                  album AS row_album, SUBSTR(sortkey, 1, 2) AS row_sortkey
           FROM ALBUM_TABLE t WHERE album <> '' ORDER BY sortkey, album"""
    )

    fun artists(): List<Row> = query(
        """SELECT artist AS row_title, '' AS row_subtitle, artist AS row_filter,
                  (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.artist = t.artist AND m.path <> '') AS row_count,
                  SUBSTR(sortkey, 1, 2) AS row_sortkey
           FROM ARTIST_TABLE t WHERE artist <> '' ORDER BY sortkey, artist"""
    )

    fun albumArtists(): List<Row> = query(
        """SELECT album_artist AS row_title, '' AS row_subtitle, album_artist AS row_filter,
                  (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.album_artist = t.album_artist AND m.path <> '') AS row_count,
                  SUBSTR(sortkey, 1, 2) AS row_sortkey
           FROM ALBUM_ARTIST_TABLE t WHERE album_artist <> '' ORDER BY sortkey, album_artist"""
    )

    fun genres(): List<Row> = query(
        """SELECT genre AS row_title, '' AS row_subtitle, genre AS row_filter,
                  (SELECT COUNT(*) FROM MEDIA_TABLE m WHERE m.genre = t.genre AND m.path <> '') AS row_count
           FROM GENRE_TABLE t WHERE genre <> '' ORDER BY sortkey, genre"""
    )

    /** Favourites, newest first: the order the player keeps them in. */
    fun favourites(): List<Row> = query(
        """SELECT f.name AS row_title, f.artist AS row_subtitle, f.path AS row_path,
                  f.path AS row_art_path,
                  (SELECT m.mtime FROM MEDIA_TABLE m WHERE m.path = f.path) AS row_art_mtime,
                  (SELECT m.size FROM MEDIA_TABLE m WHERE m.path = f.path) AS row_art_size
           FROM FAVOURITES f ORDER BY f.added_at DESC"""
    )

    /**
     * Playlists: one table each, named "M3U_" plus the playlist's name. The names
     * are read from SQLite's own catalogue, not from a list kept beside it.
     */
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
                        // One unreadable table must not take every other
                        // playlist down with it.
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

    fun tracksOfAlbum(album: String): List<Row> = query(
        """SELECT name AS row_title, artist AS row_subtitle, path AS row_path, album AS row_album,
                  path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size
           FROM MEDIA_TABLE WHERE album = ? AND path <> '' ORDER BY dis_id, sortkey, name""",
        arrayOf(album),
    )

    fun tracksOfArtist(artist: String): List<Row> = query(
        """SELECT name AS row_title, album AS row_subtitle, path AS row_path, album AS row_album,
                  path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size
           FROM MEDIA_TABLE WHERE artist = ? AND path <> '' ORDER BY album, dis_id, sortkey, name""",
        arrayOf(artist),
    )

    fun tracksOfAlbumArtist(albumArtist: String): List<Row> = query(
        """SELECT name AS row_title, album AS row_subtitle, path AS row_path, album AS row_album,
                  path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size
           FROM MEDIA_TABLE WHERE album_artist = ? AND path <> '' ORDER BY album, dis_id, sortkey, name""",
        arrayOf(albumArtist),
    )

    fun tracksOfGenre(genre: String): List<Row> = query(
        """SELECT name AS row_title, artist AS row_subtitle, path AS row_path, album AS row_album,
                  path AS row_art_path, mtime AS row_art_mtime, size AS row_art_size
           FROM MEDIA_TABLE WHERE genre = ? AND path <> '' ORDER BY sortkey, name""",
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

    /**
     * A table name inside a query, quoted. The name comes from SQLite's catalogue
     * and not from the user, but a playlist can be called anything, and a quote
     * in the name would close the identifier halfway.
     */
    private fun quoted(table: String): String = "\"" + table.replace("\"", "\"\"") + "\""

    /**
     * Title, artist and album for a handful of paths, in one query. This is what
     * the queue needs: the player sends paths and nothing else, and the app
     * already has the names.
     */
    fun tracksByPaths(paths: List<String>): Map<String, Row> {
        val wanted = paths.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return emptyMap()

        val out = HashMap<String, Row>(wanted.size)
        // In chunks: SQLite stops at about a thousand placeholders per query.
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

    /** Albums whose name contains the text. */
    fun albumsMatching(text: String, limit: Int = 30): List<Row> {
        val like = likeOf(text) ?: return emptyList()
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

    /** The search, over the three columns anyone would expect. */
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
