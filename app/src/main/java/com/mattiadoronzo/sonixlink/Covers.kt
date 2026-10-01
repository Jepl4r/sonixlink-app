package com.mattiadoronzo.sonixlink

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * The player's thumbnails, fetched as they are needed.
 *
 * The player keeps a thumbnail for every cover it has drawn (`src/gui/cover.c`)
 * as decoded, scaled RGB565 pixels. Its file is too large to download whole, so
 * at sync only the list of keys comes across (`/api/thumbkeys`); thumbnails are
 * asked for a screenful at a time as lists scroll (`/api/thumbs`) and stored
 * here permanently, since a key's pixels never change.
 *
 * The key is the player's: 64-bit FNV-1a over
 *
 *     <path>|<side>|f|<mtime>|<size>
 *
 * printed as sixteen hex digits, with path, mtime and size from the index and
 * side 72. A row with w and h at zero means "no artwork".
 */
class Covers(context: Context) {

    companion object {
        /** THUMB_SIZE from the player's lists (medialist.c, browser.c). */
        const val BOX = 72

        /** Keys in one request: a screenful and a bit, about 300 KB. Over Bluetooth, PER_ASK_SLOW. */
        private const val PER_ASK = 32
        private const val PER_ASK_SLOW = 8

        /** The pause between two requests over a slow link. */
        private const val SLOW_PAUSE_MS = 150L

        /** Most keys waiting at once; past this the oldest are dropped. */
        private const val WANTED_MAX = 160

        /** After a failed request, the wait before the next. */
        private const val BACKOFF_MS = 3000L

        /** The same FNV-1a as thumb_hash() in src/gui/cover.c. */
        fun key(path: String, mtime: Long, size: Long, box: Int = BOX, isDir: Boolean = false): String {
            val material = "$path|$box|${if (isDir) 'd' else 'f'}|$mtime|$size"
            var h = 1469598103934665603L
            for (b in material.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toLong() and 0xFF)
                h *= 1099511628211L
            }
            return String.format(Locale.US, "%016x", h)
        }

        private val main = Handler(Looper.getMainLooper())
        private val watchers = LinkedHashSet<() -> Unit>()

        /**
         * Registers [watcher] to run on the main thread when thumbnails arrive.
         * Pair with [unwatch]: a watcher holds a reference to its screen.
         */
        fun watch(watcher: () -> Unit) {
            watchers.add(watcher)
        }

        fun unwatch(watcher: () -> Unit) {
            watchers.remove(watcher)
        }

        private fun announce() {
            main.post { watchers.toList().forEach { it() } }
        }
    }

    private val file = File(context.filesDir, "thumbnails.db")
    private val keysFile = File(context.filesDir, "thumbkeys.txt")
    private var db: SQLiteDatabase? = null

    // Guards the database and the state below: the main thread reads, the
    // fetcher writes, and syncing closes and reopens the file. Closing a
    // database mid-query throws IllegalStateException, not SQLiteException.
    private val gate = Any()

    private val cache = object : LruCache<String, Bitmap>(300) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** Keys already looked up and not found, or with no artwork. */
    private val misses = HashSet<String>()

    /**
     * The keys the player holds, with a picture or with "no artwork". Null
     * when the player cannot send the list (older firmware): nothing is
     * fetched, and only stored thumbnails are shown.
     */
    private var remote: Set<String>? = null

    /** Keys waiting to be fetched, newest last, and the ones on their way. */
    private val wanted = LinkedHashSet<String>()
    private val inFlight = HashSet<String>()
    private var fetcher: Thread? = null
    private var stopped = false

    /** Fetches thumbnails for a set of keys; set by the owner of the player's client. */
    @Volatile
    var source: ((Collection<String>) -> List<Thumb>)? = null

    /**
     * Over Bluetooth: smaller requests with a pause between them, so state
     * reads and commands can get through and the headphones' audio, on the
     * same radio, is not starved.
     */
    @Volatile
    var slowLink = false

    /**
     * Album to the key of the track that stands in as its cover: the first
     * track with a thumbnail. The player only has thumbnails for rows it has
     * drawn, so an arbitrary track of the album often has none.
     */
    private var albumKeys: Map<String, String> = emptyMap()

    val databaseFile: File get() = file

    /** Opens the local store, creating it the first time. */
    fun open(): Boolean = synchronized(gate) {
        closeLocked()
        try {
            val database = SQLiteDatabase.openDatabase(
                file.absolutePath, null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or
                    SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            )
            // The player's own schema, so a whole copy of its thumbnail file
            // is readable as is.
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS thumbs(key TEXT PRIMARY KEY, w INTEGER NOT NULL, " +
                    "h INTEGER NOT NULL, pixels BLOB)"
            )
            db = database
            stopped = false
            true
        } catch (e: Exception) {
            db = null
            false
        }
    }

    fun close() {
        synchronized(gate) {
            stopped = true
            wanted.clear()
            (gate as java.lang.Object).notifyAll()
            closeLocked()
        }
    }

    private fun closeLocked() {
        try {
            db?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        db = null
        cache.evictAll()
        misses.clear()
        albumKeys = emptyMap()
    }

    // --- The player's list of keys ---

    /** The list as the player last sent it, from disk. False when there is none. */
    fun loadRemoteKeys(): Boolean {
        val keys = try {
            if (!keysFile.exists()) return false
            keysFile.readLines().filter { it.length == 16 }.toHashSet()
        } catch (e: Exception) {
            return false
        }
        synchronized(gate) {
            remote = keys
            misses.clear()
        }
        return true
    }

    /** A fresh list from the player, kept on disk for the next connection. */
    fun setRemoteKeys(keys: Set<String>) {
        try {
            val temporary = File(keysFile.parentFile, keysFile.name + ".part")
            temporary.writeText(keys.joinToString("\n"))
            if (!temporary.renameTo(keysFile)) temporary.delete()
        } catch (e: Exception) {
            // Not saved: the list is asked for again at the next connection.
        }
        synchronized(gate) {
            remote = keys
            misses.clear()
        }
    }

    /** The player cannot send a list: nothing will be fetched. */
    fun clearRemoteKeys() {
        synchronized(gate) { remote = null }
    }

    /**
     * Fills the album covers from [rows]: per album, the first track that has
     * a thumbnail here or on the player. Call off the main thread.
     */
    fun indexAlbums(rows: List<Row>) {
        val present = HashSet(localKeys())
        synchronized(gate) { remote?.let { present.addAll(it) } }
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

    /** Every key with a picture already here: one read, then comparisons. */
    private fun localKeys(): Set<String> = synchronized(gate) {
        val database = db ?: return@synchronized emptySet()
        val out = HashSet<String>()
        try {
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

    // --- Reading ---

    /** An album's cover, taken from the track that has one. */
    fun forAlbum(album: String): Bitmap? {
        if (album.isEmpty()) return null
        val key = synchronized(gate) { albumKeys[album] } ?: return null
        return forKey(key)
    }

    /** A track's thumbnail, or null until it is here (it is then fetched). */
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
        var stored = false
        try {
            database.rawQuery("SELECT w,h,pixels FROM thumbs WHERE key=? LIMIT 1", arrayOf(key)).use { cursor ->
                if (cursor.moveToFirst()) {
                    stored = true
                    found = bitmapOf(cursor.getInt(0), cursor.getInt(1), cursor.getBlob(2))
                }
            }
        } catch (e: Exception) {
            return@synchronized null
        }

        // A local val so the compiler can smart-cast what the lambda wrote.
        val bitmap = found
        when {
            bitmap != null -> cache.put(key, bitmap)
            // Not stored, and the player has it: fetch it.
            !stored && remote?.contains(key) == true -> want(key)
            else -> miss(key)
        }
        bitmap
    }

    /** w and h at zero: the player has looked and found nothing. The rest is RGB565. */
    private fun bitmapOf(w: Int, h: Int, pixels: ByteArray?): Bitmap? {
        if (w <= 0 || h <= 0 || pixels == null || pixels.size < w * h * 2) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels, 0, w * h * 2))
        return bitmap
    }

    private fun miss(key: String) {
        // Bounded: a large library without artwork would grow it without limit.
        if (misses.size > 4000) {
            misses.clear()
        }
        misses.add(key)
    }

    // --- Fetching ---

    /** Under the gate. The newest request goes last, which is where the fetcher takes from. */
    private fun want(key: String) {
        if (stopped || key in inFlight) return
        wanted.remove(key)
        wanted.add(key)
        while (wanted.size > WANTED_MAX) {
            wanted.remove(wanted.first())
        }
        if (fetcher == null) {
            fetcher = Thread({ fetchLoop() }, "thumbnails").apply {
                isDaemon = true
                start()
            }
        }
        (gate as java.lang.Object).notifyAll()
    }

    /** One request at a time, newest keys first: those are the rows on screen. */
    private fun fetchLoop() {
        while (true) {
            val batch = synchronized(gate) {
                while (!stopped && (wanted.isEmpty() || source == null)) {
                    try {
                        (gate as java.lang.Object).wait(5000)
                    } catch (e: InterruptedException) {
                        fetcher = null
                        return
                    }
                }
                if (stopped) {
                    fetcher = null
                    return
                }
                val newest = wanted.toList().takeLast(if (slowLink) PER_ASK_SLOW else PER_ASK)
                wanted.removeAll(newest.toSet())
                inFlight.addAll(newest)
                newest
            }

            val fetch = source
            val thumbs = try {
                fetch?.invoke(batch)
            } catch (e: Exception) {
                null
            }

            synchronized(gate) {
                inFlight.removeAll(batch.toSet())
                if (thumbs == null) {
                    // Failed: the keys are wanted again when their rows are
                    // redrawn, after BACKOFF_MS.
                } else {
                    store(thumbs)
                    // Keys left unanswered are not asked for again until the
                    // misses are cleared.
                    val answered = thumbs.mapTo(HashSet()) { it.key }
                    batch.filter { it !in answered }.forEach { miss(it) }
                }
            }
            if (thumbs == null) {
                Thread.sleep(BACKOFF_MS)
            } else {
                if (thumbs.isNotEmpty()) announce()
                if (slowLink) Thread.sleep(SLOW_PAUSE_MS)
            }
        }
    }

    /** Under the gate: into the store for good, and into memory for the next draw. */
    private fun store(thumbs: List<Thumb>) {
        val database = db ?: return
        try {
            database.beginTransaction()
            try {
                for (thumb in thumbs) {
                    val values = ContentValues().apply {
                        put("key", thumb.key)
                        put("w", thumb.w)
                        put("h", thumb.h)
                        put("pixels", thumb.pixels)
                    }
                    database.insertWithOnConflict("thumbs", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        } catch (e: Exception) {
            // Not stored: still cached below, and fetched again next session.
        }
        for (thumb in thumbs) {
            val bitmap = bitmapOf(thumb.w, thumb.h, thumb.pixels)
            if (bitmap != null) {
                cache.put(thumb.key, bitmap)
                misses.remove(thumb.key)
            } else {
                miss(thumb.key)
            }
        }
    }
}
