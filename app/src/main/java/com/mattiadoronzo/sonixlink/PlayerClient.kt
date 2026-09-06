package com.mattiadoronzo.sonixlink

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The player, as the app sees it.
 *
 * Everything goes through here, and everything is HTTP with JSON answers: no
 * framing of our own, no length-prefixed records, no binary signatures. When
 * something looks wrong, the same address opens in a browser and shows itself.
 *
 * No method here may be called from the main thread: they all block on the
 * network. Callers sit inside a coroutine on Dispatchers.IO.
 */
class PlayerClient(val host: String, val port: Int = DEFAULT_PORT) {

    companion object {
        const val DEFAULT_PORT = 7800
        private const val TAG = "SonixLink"

        /** Short: a player that does not answer at once is better reported. */
        private const val CONNECT_TIMEOUT = 3000

        /** Long: the index can be a few megabytes over a slow network. */
        private const val DOWNLOAD_TIMEOUT = 60000
    }

    val base: String get() = "http://$host:$port"

    // -----------------------------------------------------------------------
    // Requests
    // -----------------------------------------------------------------------

    private fun request(path: String, method: String = "GET", readTimeout: Int = 4000): String {
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            this.readTimeout = readTimeout
            useCaches = false
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw PlayerException("$code ${body.take(120)}")
            }
            return body
        } finally {
            connection.disconnect()
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
            scanning = json.optBoolean("scanning", false),
            scanCount = json.optInt("scan_count", 0),
            tracks = json.optInt("tracks", 0),
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
     * Starts a track and says which list it came from, so the queue becomes that
     * list rather than the whole library. `list` is "all", "album", "artist",
     * "album_artist", "genre", "favourites", "playlist" or "queue".
     */
    fun playPath(path: String, list: String = "all", value: String = "") =
        command("do" to "play_path", "path" to path, "list" to list, "value" to value)
    fun startScan() = command("do" to "scan")
    fun playQueueIndex(index: Int) = command("do" to "queue_index", "value" to index.toString())

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
     * The favourites as they stand on the player right now.
     *
     * Not read from the downloaded index: a star set a minute ago has to show at
     * once, not at the next download.
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
    fun queue(): QueueWindow {
        val json = JSONObject(request("/api/queue"))
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
        )
    }

    /**
     * Downloads the index into a file.
     *
     * Written beside the destination and then renamed: if the network drops
     * halfway, what remains is the whole old index, not a half-written file that
     * would open with an incomprehensible error.
     */
    fun downloadDatabase(destination: File): Long = downloadFile("/api/db", destination)

    private fun downloadFile(path: String, destination: File): Long {
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = DOWNLOAD_TIMEOUT
            useCaches = false
        }
        try {
            val code = connection.responseCode
            if (code == 503) throw ScanningException()
            if (code == 404) throw MissingException()
            if (code !in 200..299) throw PlayerException("$code")

            val total = connection.contentLength.toLong()
            val temporary = File(destination.parentFile, destination.name + ".part")
            var written = 0L

            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        output.write(chunk, 0, read)
                        written += read
                    }
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
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The player's thumbnails: the very file it fills as it draws its own lists.
     * It may not exist yet (404), which is not an error -- it means the player
     * has not drawn anything yet.
     */
    fun downloadCovers(destination: File): Long = downloadFile("/api/covers", destination)

    /**
     * A track's artwork, whole and exactly as it sits in the file: the player
     * does not touch it, the phone decodes it. Null when the track has none.
     */
    fun artwork(path: String): ByteArray? {
        val connection = (URL("$base/api/art?path=" + URLEncoder.encode(path, "UTF-8"))
            .openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = DOWNLOAD_TIMEOUT
            useCaches = false
        }
        try {
            if (connection.responseCode != 200) return null
            // Artwork bigger than this is a mistake in a tag rather than a
            // picture: read up to the limit and no further.
            val limit = 8 * 1024 * 1024
            val out = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val chunk = ByteArray(64 * 1024)
                while (out.size() < limit) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    out.write(chunk, 0, read)
                }
            }
            return if (out.size() > 0) out.toByteArray() else null
        } catch (e: Exception) {
            return null
        } finally {
            connection.disconnect()
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

/** The player is rebuilding the index: there is nothing to download now. */
class ScanningException : Exception("scanning")

/** The player does not have that file. */
class MissingException : Exception("missing")

/** The queue window the player sends: paths, not metadata. */
data class QueueWindow(val count: Int, val position: Int, val first: Int, val paths: List<String>)
