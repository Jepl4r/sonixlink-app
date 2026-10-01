package com.mattiadoronzo.sonixlink

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder

/**
 * The player's HTTP API, as the app uses it. Requests are plain HTTP with JSON
 * answers (the thumbnail routes excepted), so over the network the same URLs
 * can be opened in a browser.
 *
 * Every method blocks on the link: call from a background thread, such as a
 * coroutine on Dispatchers.IO, never from the main thread.
 */
class PlayerClient(val host: String, val port: Int = DEFAULT_PORT) {

    companion object {
        const val DEFAULT_PORT = 7800
        private const val TAG = "SonixLink"

        /** Milliseconds; long enough for a multi-megabyte index over a slow link. */
        private const val DOWNLOAD_TIMEOUT = 60000

        /**
         * One transport per player, shared by every client made for it: the
         * player accepts a single Bluetooth link at a time.
         */
        private val transports = HashMap<String, Transport>()

        fun transportFor(host: String, port: Int): Transport = synchronized(transports) {
            transports.getOrPut("$host:$port") {
                if (BluetoothTransport.isBluetooth(host)) {
                    BluetoothTransport(BluetoothTransport.addressOf(host))
                } else {
                    HttpTransport(host, port)
                }
            }
        }

        /** Closes the link to a player the app is leaving. */
        fun release(host: String, port: Int) {
            synchronized(transports) { transports.remove("$host:$port") }?.close()
        }
    }

    private val transport: Transport = transportFor(host, port)

    /** Whether this player is reached over Bluetooth. */
    val isBluetooth: Boolean get() = BluetoothTransport.isBluetooth(host)

    /** Where the player is, for the screens: an address and port, or a name. */
    val label: String get() = transport.label

    // -----------------------------------------------------------------------
    // Requests
    // -----------------------------------------------------------------------

    private fun request(
        path: String,
        method: String = "GET",
        readTimeout: Int = 4000,
        body: ByteArray? = null,
    ): String {
        transport.exchange(path, method, readTimeout, body = body).use { response ->
            val body = response.body.bufferedReader().readText()
            if (response.code !in 200..299) {
                throw PlayerException("${response.code} ${body.take(120)}")
            }
            return body
        }
    }

    /** Which player this is, and how old its index is. */
    fun info(): PlayerInfo {
        val json = JSONObject(request("/api/info"))
        return PlayerInfo(
            name = json.optString("name", host),
            model = json.optString("model", ""),
            firmware = json.optString("firmware", ""),
            tracks = json.optInt("tracks", 0),
            scanning = json.optBoolean("scanning", false),
            dbAvailable = json.optBoolean("db_available", false),
            dbSize = json.optLong("db_size", 0),
            dbMtime = json.optLong("db_mtime", 0),
            coversAvailable = json.optBoolean("covers_available", false),
            coversSize = json.optLong("covers_size", 0),
            coversMtime = json.optLong("covers_mtime", 0),
            accent = json.optString("accent", ""),
            serial = json.optString("serial", ""),
            dbHash = json.optString("db_hash", ""),
            coversHash = json.optString("covers_hash", ""),
            sort = SortPrefs.fromJson(json.optJSONObject("sort")),
        )
    }

    /** What is playing right now. */
    fun state(): PlayerState {
        val json = JSONObject(request("/api/state"))
        return PlayerState(
            playState = json.optInt("state", 0),
            mode = PlayMode.fromWire(json.optString("mode", "normal")),
            volume = json.optInt("volume", 0),
            position = json.optInt("position", 0),
            duration = json.optInt("duration", 0),
            title = json.optString("title", ""),
            artist = json.optString("artist", ""),
            album = json.optString("album", ""),
            path = json.optString("path", ""),
            sampleRate = json.optInt("sample_rate", 0),
            bits = json.optInt("bits", 0),
            bitrate = json.optInt("bitrate", 0),
            lossless = json.optBoolean("lossless", false),
            battery = json.optInt("battery", -1),
            charging = json.optBoolean("charging", false),
            favourite = json.optBoolean("favourite", false),
            accent = json.optString("accent", ""),
            queuePosition = json.optInt("queue_position", -1),
            queueCount = json.optInt("queue_count", 0),
            queueRevision = json.optLong("queue_revision", -1),
            displayPosition = json.optInt("display_position", -1),
            displayCount = json.optInt("display_count", -1),
            scanning = json.optBoolean("scanning", false),
            scanCount = json.optInt("scan_count", 0),
            tracks = json.optInt("tracks", 0),
            sort = SortPrefs.fromJson(json.optJSONObject("sort")),
        )
    }

    private fun command(vararg params: Pair<String, String>) {
        val query = params.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        request("/api/command?$query", method = "POST")
    }

    fun play() = command("do" to "play")
    fun pause() = command("do" to "pause")
    fun toggle() = command("do" to "toggle")
    fun next() = command("do" to "next")
    fun previous() = command("do" to "prev")
    fun seek(seconds: Int) = command("do" to "seek", "value" to seconds.toString())
    fun setVolume(percent: Int) = command("do" to "volume", "value" to percent.coerceIn(0, 100).toString())
    fun setMode(mode: PlayMode) = command("do" to "mode", "value" to mode.wire)
    /**
     * Starts a track with the list it came from, which becomes the queue.
     * `list` is "all", "album", "artist", "album_artist", "genre",
     * "favourites", "playlist" or "queue"; `value` names the album, artist etc.
     */
    fun playPath(path: String, list: String = "all", value: String = "") =
        command("do" to "play_path", "path" to path, "list" to list, "value" to value)
    fun startScan() = command("do" to "scan")
    fun playQueueIndex(index: Int) = command("do" to "queue_index", "value" to index.toString())

    /**
     * The player's play-all menu for a list. `how` is "sequence", "shuffle" or
     * "random" (one random track of the list, queued after the current one).
     * `list` as in [playPath], plus "albums" for every album.
     */
    fun playAll(list: String, value: String, how: String) =
        command("do" to "play_all", "list" to list, "value" to value, "how" to how)

    /**
     * Applies a selection-mode action to some tracks. `action` is "queue_next",
     * "favourites_add", "favourites_remove", "playlist_add" or "playlist_remove"
     * (the last two with `playlist`). The paths go in the request body, one per
     * line, as a selection can exceed any query-string limit.
     */
    fun selection(action: String, paths: List<String>, playlist: String = "") {
        if (paths.isEmpty()) return
        var target = "/api/command?do=" + URLEncoder.encode(action, "UTF-8")
        if (playlist.isNotEmpty()) target += "&value=" + URLEncoder.encode(playlist, "UTF-8")
        request(target, method = "POST", readTimeout = 8000, body = paths.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** Sets or clears the star. Without `starred` the player flips it. */
    fun setFavourite(path: String, starred: Boolean? = null) = command(
        "do" to "favourite",
        "path" to path,
        "value" to when (starred) {
            true -> "1"
            false -> "0"
            null -> ""
        },
    )

    /**
     * The favourites as they stand on the player, read live rather than from
     * the downloaded index so recent stars show at once.
     */
    fun favourites(): List<Row> {
        val json = JSONObject(request("/api/favourites"))
        val array = json.optJSONArray("favourites") ?: return emptyList()
        val out = ArrayList<Row>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val path = item.optString("path", "")
            if (path.isEmpty()) continue
            out.add(
                Row(
                    title = item.optString("name", "").ifEmpty { path.substringAfterLast('/') },
                    subtitle = item.optString("artist", ""),
                    path = path,
                )
            )
        }
        return out
    }

    /** The queue: the player sends a window around the playing track. */
    fun queue(): QueueWindow = parseQueue(JSONObject(request("/api/queue")))

    /**
     * The stretch of the queue starting at `from`. The player fetches it on its
     * next turn, so early answers may be pending: polls every 150 ms, about
     * three seconds in all, then throws.
     */
    fun queuePage(from: Int): QueueWindow {
        repeat(20) {
            val page = parseQueue(JSONObject(request("/api/queue?from=$from")))
            if (!page.pending) return page
            Thread.sleep(150)
        }
        throw PlayerException("the queue did not arrive")
    }

    private fun parseQueue(json: JSONObject): QueueWindow {
        val array = json.optJSONArray("paths")
        val paths = ArrayList<String>(array?.length() ?: 0)
        for (i in 0 until (array?.length() ?: 0)) {
            paths.add(array!!.optString(i, ""))
        }
        return QueueWindow(
            count = json.optInt("count", 0),
            position = json.optInt("position", -1),
            first = json.optInt("first", 0),
            paths = paths,
            revision = json.optLong("revision", -1),
            pending = json.optBoolean("pending", false),
        )
    }

    /**
     * One folder of the card, as the player's file browser lists it. Empty
     * `path` is the card's root.
     */
    fun browse(path: String): Folder {
        val query = if (path.isEmpty()) "" else "?path=" + URLEncoder.encode(path, "UTF-8")
        val json = JSONObject(request("/api/browse$query"))
        val array = json.optJSONArray("entries")
        val entries = ArrayList<FolderEntry>(array?.length() ?: 0)
        for (i in 0 until (array?.length() ?: 0)) {
            val item = array!!.optJSONObject(i) ?: continue
            entries.add(
                FolderEntry(
                    name = item.optString("name", ""),
                    path = item.optString("path", ""),
                    isFolder = item.optBoolean("dir", false),
                )
            )
        }
        return Folder(
            path = json.optString("path", ""),
            root = json.optString("root", ""),
            parent = json.optString("parent", ""),
            entries = entries,
        )
    }

    /** The heartbeat: keeps the player's status bar showing the app. */
    fun ping() {
        request("/api/ping")
    }

    /** Tells the player the app has let go, so its status bar forgets it now. */
    fun bye() {
        request("/api/bye", readTimeout = 1500)
    }

    /**
     * Downloads the index into `destination`. The file is written to a ".part"
     * beside it and renamed only when complete, so a dropped link leaves the
     * old index intact. Throws [ScanningException] while the player rebuilds it.
     */
    fun downloadDatabase(destination: File, progress: (Long, Long) -> Unit = { _, _ -> }): Long =
        downloadFile("/api/db", destination, progress)

    /**
     * `progress` hears (bytes so far, total) as the file arrives, at most a few
     * times a second, on this thread; total is -1 when the player did not say.
     */
    private fun downloadFile(path: String, destination: File, progress: (Long, Long) -> Unit = { _, _ -> }): Long {
        transport.exchange(path, "GET", DOWNLOAD_TIMEOUT, bulk = true).use { response ->
            val code = response.code
            if (code == 503) throw ScanningException()
            if (code == 404) throw MissingException()
            if (code !in 200..299) throw PlayerException("$code")

            val total = response.length
            val temporary = File(destination.parentFile, destination.name + ".part")
            var written = 0L

            response.body.let { input ->
                FileOutputStream(temporary).use { output ->
                    val chunk = ByteArray(64 * 1024)
                    var told = 0L
                    progress(0, total)
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        output.write(chunk, 0, read)
                        written += read
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - told >= 200) {
                            told = now
                            progress(written, total)
                        }
                    }
                    progress(written, total)
                    output.fd.sync()
                }
            }

            if (total > 0 && written != total) {
                temporary.delete()
                throw PlayerException("got $written bytes of $total")
            }
            if (destination.exists() && !destination.delete()) {
                Log.w(TAG, "cannot remove the old index")
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                throw PlayerException("cannot move the index into place")
            }
            return written
        }
    }

    /**
     * Every thumbnail key the player holds, including keys it looked up and
     * found no artwork for, as 16-digit lowercase hex. On the wire each key is
     * 8 bytes, little-endian. Throws [MissingException] when the player has no
     * keys to give (firmware without the route, or no thumbnails drawn yet).
     */
    fun thumbKeys(): Set<String> {
        transport.exchange("/api/thumbkeys", "GET", DOWNLOAD_TIMEOUT, bulk = true).use { response ->
            if (response.code == 404) throw MissingException()
            if (response.code !in 200..299) throw PlayerException("${response.code}")
            val bytes = response.body.readBytes()
            val out = HashSet<String>(bytes.size / 8 * 2)
            var at = 0
            while (at + 8 <= bytes.size) {
                var v = 0L
                for (i in 7 downTo 0) {
                    v = (v shl 8) or (bytes[at + i].toLong() and 0xFF)
                }
                out.add(String.format(java.util.Locale.US, "%016x", v))
                at += 8
            }
            return out
        }
    }

    /**
     * The thumbnails behind some keys, as the player drew them: RGB565 pixels,
     * already scaled. Each record on the wire is the 16-char ASCII key, width
     * and height (u16 LE each), the pixel byte count (u32 LE), then the pixels.
     * Keys the player does not hold are left out; keys with no artwork come
     * back 0x0.
     */
    fun thumbs(keys: Collection<String>): List<Thumb> {
        if (keys.isEmpty()) return emptyList()
        val query = keys.joinToString(",")
        transport.exchange("/api/thumbs?keys=$query", "GET", DOWNLOAD_TIMEOUT, bulk = true).use { response ->
            if (response.code == 404) throw MissingException()
            if (response.code !in 200..299) throw PlayerException("${response.code}")
            val input = java.io.DataInputStream(java.io.BufferedInputStream(response.body))
            val out = ArrayList<Thumb>(keys.size)
            val keyBytes = ByteArray(16)
            val head = ByteArray(8)
            while (true) {
                try {
                    input.readFully(keyBytes)
                } catch (e: java.io.EOFException) {
                    break
                }
                input.readFully(head)
                val w = (head[0].toInt() and 0xFF) or ((head[1].toInt() and 0xFF) shl 8)
                val h = (head[2].toInt() and 0xFF) or ((head[3].toInt() and 0xFF) shl 8)
                val n = (head[4].toInt() and 0xFF) or ((head[5].toInt() and 0xFF) shl 8) or
                    ((head[6].toInt() and 0xFF) shl 16) or ((head[7].toInt() and 0xFF) shl 24)
                if (n < 0 || n > 4 * 1024 * 1024) throw PlayerException("bad thumbnail length $n")
                val pixels = ByteArray(n)
                input.readFully(pixels)
                out.add(Thumb(String(keyBytes, Charsets.US_ASCII), w, h, pixels))
            }
            return out
        }
    }

    /**
     * A track's artwork: null when the track has none, an exception when the
     * player could not be asked, and [ArtPending] while it is being prepared.
     *
     * With `maxSide` the player scales the picture to fit and sends a small
     * JPEG, which keeps the link free over Bluetooth; without it, the embedded
     * picture comes unchanged.
     */
    fun artwork(path: String, maxSide: Int = 0): ByteArray? {
        var target = "/api/art?path=" + URLEncoder.encode(path, "UTF-8")
        if (maxSide > 0) target += "&max=$maxSide"
        transport.exchange(target, "GET", DOWNLOAD_TIMEOUT, bulk = true).use { response ->
            if (response.code == 404) return null
            // 202: the player is still preparing it; Artwork retries.
            if (response.code == 202) throw ArtPending()
            if (response.code != 200) throw PlayerException("${response.code}")
            // Reads at most about 8 MB: anything larger is a broken tag, not a picture.
            val limit = 8 * 1024 * 1024
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val read = response.body.read(chunk)
                if (read <= 0) break
                out.write(chunk, 0, read)
            }
            return if (out.size() > 0) out.toByteArray() else null
        }
    }

    /** One quick round trip to find out whether a player is at the far end. */
    fun reachable(): Boolean = try {
        info()
        true
    } catch (e: Exception) {
        false
    }
}

class PlayerException(message: String) : Exception(message)

/** The cover asked for is being made on the player: ask again in a moment. */
class ArtPending : Exception("preparing")

/** The player is rebuilding the index: there is nothing to download now. */
class ScanningException : Exception("scanning")

/** The player does not have that file. */
class MissingException : Exception("missing")

/** The queue window the player sends: paths, not metadata. */
data class QueueWindow(
    val count: Int,
    val position: Int,
    val first: Int,
    val paths: List<String>,
    /** Changes whenever the queue does. -1 from a player that does not say. */
    val revision: Long = -1,
    /** The player has not fetched that stretch yet: ask again. */
    val pending: Boolean = false,
)

/** One thumbnail from the player: w and h at zero mean "no artwork". */
class Thumb(val key: String, val w: Int, val h: Int, val pixels: ByteArray)

/** One folder of the card. */
data class Folder(val path: String, val root: String, val parent: String, val entries: List<FolderEntry>)

data class FolderEntry(val name: String, val path: String, val isFolder: Boolean)
