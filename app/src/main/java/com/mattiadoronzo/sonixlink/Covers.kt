package com.mattiadoronzo.sonixlink

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * The player's thumbnails.
 *
 * Not a store built for the app: this is **the same file** the player fills as
 * it draws its own lists (`src/gui/cover.c`), downloaded verbatim. Inside are
 * pixels already decoded and already scaled down, so nothing here decodes or
 * resizes anything.
 *
 * The key is the one the player computes: 64-bit FNV-1a over
 *
 *     <path>|<side>|f|<mtime>|<size>
 *
 * printed as sixteen hex digits. Path, mtime and size are already in the index
 * (`MEDIA_TABLE.path`, `.mtime`, `.size`), and the side is 72, what the player
 * uses for the rows of its lists.
 *
 * A row with w and h at zero means "no artwork here", which is worth knowing:
 * it saves asking again on every scroll.
 */
class Covers(context: Context) {

    companion object {
        /** THUMB_SIZE from the player's lists (medialist.c, browser.c). */
        const val BOX = 72

        /** The same FNV-1a as thumb_hash() in src/gui/cover.c. */
        fun key(path: String, mtime: Long, size: Long, box: Int = BOX, isDir: Boolean = false): String {
            val material = "$path|$box|${if (isDir) 'd' else 'f'}|$mtime|$size"
            // The same offset basis as in cover.c, digit for digit.
            var h = 1469598103934665603L
            for (b in material.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xFF)
                h *= 1099511628211L
            }
            return String.format(Locale.US, "%016x", h)
        }
    }

    private val file = File(context.filesDir, "thumbnails.db")
    private var db: SQLiteDatabase? = null

    // Rows are read from the main thread while syncing, on another, can close
    // and reopen the file. Closing a database someone is querying throws
    // IllegalStateException, not SQLiteException: it all sits behind this lock.
    private val gate = Any()

    private val cache = object : LruCache<String, Bitmap>(96) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** Keys already looked up and not found, or with no artwork. */
    private val misses = HashSet<String>()

    /**
     * Which track stands in as each album's cover.
     *
     * The player only has thumbnails for rows it has actually drawn at least
     * once, so "any track of the album" often landed on one without, and the
     * album stayed blank even where the Tracks tab showed the artwork plainly.
     * This finds, once, the first track of each album that has a thumbnail.
     */
    private var albumKeys: Map<String, String> = emptyMap()

    val databaseFile: File get() = file
    val exists: Boolean get() = file.exists() && file.length() > 0

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
            // A database already closed is not news.
        }
        db = null
        cache.evictAll()
        misses.clear()
        albumKeys = emptyMap()
    }

    /**
     * Walks the index once and keeps, per album, the first track that has a
     * thumbnail. Six thousand rows and as many hashes: a few tens of
     * milliseconds, off the screen's thread.
     */
    fun indexAlbums(rows: List<Row>) {
        val present = allKeys()
        if (present.isEmpty()) {
            synchronized(gate) { albumKeys = emptyMap() }
            return
        }
        val map = HashMap<String, String>()
        for (row in rows) {
            if (row.album.isEmpty() || row.album in map) continue
            if (row.artPath.isEmpty() || row.artMtime <= 0 || row.artSize <= 0) continue
            val k = key(row.artPath, row.artMtime, row.artSize)
            if (k in present) {
                map[row.album] = k
            }
        }
        synchronized(gate) { albumKeys = map }
    }

    /** Every key the file holds: one read, then it is only comparisons. */
    private fun allKeys(): Set<String> = synchronized(gate) {
        val database = db ?: return@synchronized emptySet()
        val out = HashSet<String>()
        try {
            // Only the ones with a picture: zero rows mean "no artwork here",
            // which is no use to an album.
            database.rawQuery("SELECT key FROM thumbs WHERE w > 0 AND h > 0", null).use { cursor ->
                while (cursor.moveToNext()) {
                    out.add(cursor.getString(0) ?: continue)
                }
            }
        } catch (e: Exception) {
            return@synchronized emptySet()
        }
        out
    }

    /** An album's cover, taken from the track that has one. */
    fun forAlbum(album: String): Bitmap? {
        if (album.isEmpty()) return null
        val key = synchronized(gate) { albumKeys[album] } ?: return null
        return forKey(key)
    }

    /** A track's thumbnail, or null while the player has yet to make it. */
    fun forTrack(path: String, mtime: Long, size: Long): Bitmap? {
        if (path.isEmpty() || mtime <= 0 || size <= 0) return null
        return forKey(key(path, mtime, size))
    }

    private fun forKey(key: String): Bitmap? = synchronized(gate) {
        val cached = cache.get(key)
        if (cached != null) return@synchronized cached
        if (key in misses) return@synchronized null

        val database = db ?: return@synchronized null
        var found: Bitmap? = null
        try {
            database.rawQuery("SELECT w,h,pixels FROM thumbs WHERE key=? LIMIT 1", arrayOf(key)).use { cursor ->
                if (cursor.moveToFirst()) {
                    val w = cursor.getInt(0)
                    val h = cursor.getInt(1)
                    val pixels = cursor.getBlob(2)
                    // w and h at zero: the player has looked and found
                    // nothing. The rest is RGB565, two bytes per pixel.
                    if (w > 0 && h > 0 && pixels != null && pixels.size >= w * h * 2) {
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                        found = bitmap
                    }
                }
            }
        } catch (e: Exception) {
            return@synchronized null
        }

        // A local copy first: `found` is written inside a lambda, and from out
        // here the compiler no longer holds it non-null.
        val bitmap = found
        if (bitmap == null) {
            // A large library with no artwork would fill this set row by row:
            // past a point it starts over.
            if (misses.size > 4000) {
                misses.clear()
            }
            misses.add(key)
        } else {
            cache.put(key, bitmap)
        }
        bitmap
    }
}
