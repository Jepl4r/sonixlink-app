package com.mattiadoronzo.sonixlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * How a request reaches the player: over the network, or over Bluetooth.
 *
 * Both carry the same HTTP requests and get the same answers, so everything
 * above this -- the routes, the JSON, the downloads -- is written once.
 */
interface Transport {
    /** Where the player is, for the screens to show. */
    val label: String

    /**
     * One request. The body is read by the caller and closed through
     * [Response.close]; nothing else goes over the same link until then.
     *
     * `bulk` marks a request that moves a lot of data (artwork, thumbnails,
     * the index): on a link that carries one request at a time, the small
     * ones -- the state, a command -- go before any bulk one still waiting.
     *
     * `body` goes after the headers with its Content-Length: a selection of
     * tracks, too long for a query string.
     */
    fun exchange(
        path: String,
        method: String,
        readTimeout: Int,
        bulk: Boolean = false,
        body: ByteArray? = null,
    ): Response

    /** Lets go of the link, when it has one. */
    fun close() {}
}

class Response(
    val code: Int,
    /** Content-Length, or -1 when the player did not say. */
    val length: Long,
    val body: InputStream,
    private val onClose: () -> Unit,
) : AutoCloseable {
    override fun close() {
        try {
            body.close()
        } catch (e: IOException) {
            // Nothing left to do with a stream that will not close.
        }
        onClose()
    }
}

/** The network: one HTTP connection per request, as the player expects there. */
class HttpTransport(private val host: String, private val port: Int) : Transport {

    companion object {
        /** Milliseconds; short so an unreachable player is reported quickly. */
        private const val CONNECT_TIMEOUT = 3000
    }

    override val label: String get() = "$host:$port"

    override fun exchange(path: String, method: String, readTimeout: Int, bulk: Boolean, body: ByteArray?): Response {
        val connection = (URL("http://$host:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT
            this.readTimeout = readTimeout
            useCaches = false
            if (body != null) {
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            }
        }
        val code = try {
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }
            connection.responseCode
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
        val stream = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?: ByteArrayInputStream(ByteArray(0))
        return Response(code, connection.contentLengthLong, stream) { connection.disconnect() }
    }
}

/**
 * Bluetooth: an RFCOMM link to the player's SonixLink service, found by its
 * UUID, kept open and used for one request after another. The player answers
 * each with a Content-Length, which is where one answer ends and the next
 * request may go.
 *
 * Needs the phone to be paired with the player, and BLUETOOTH_CONNECT granted.
 */
class BluetoothTransport(private val address: String, private val name: String = "") : Transport {

    companion object {
        /** The player's SONIXLINK_BT_UUID. */
        val SERVICE: UUID = UUID.fromString("8d6e3a52-4c1f-4b7e-9a2d-5f0c7e1b3a90")

        /**
         * The player's SONIXLINK_BT_CHANNEL: where the service listens, for
         * when the phone's SDP lookup of the UUID does not find it.
         */
        private const val CHANNEL = 22

        private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

        /** How a Bluetooth player is written where a host name would go. */
        const val PREFIX = "bt:"

        fun isBluetooth(host: String): Boolean = host.startsWith(PREFIX)
        fun addressOf(host: String): String = host.removePrefix(PREFIX)
    }

    /** The name the phone knows the player by, once paired; else the address. */
    override val label: String
        get() = name.ifEmpty { deviceName() ?: address }

    @SuppressLint("MissingPermission")
    private fun deviceName(): String? = try {
        BluetoothAdapter.getDefaultAdapter()?.getRemoteDevice(address)?.name
    } catch (e: Exception) {
        // No permission, or no Bluetooth: the address says enough.
        null
    }

    private val lock = Object()
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Held from a request until its answer has been read out. */
    private val busy = PriorityGate()

    @SuppressLint("MissingPermission")
    private fun connect() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IOException("no Bluetooth")
        val device = adapter.getRemoteDevice(address)
        // Discovery slows every connection down while it runs.
        try {
            adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            // Without the scan permission there is no discovery of ours to stop.
        }
        val fresh = open(device)
        socket = fresh
        input = java.io.BufferedInputStream(fresh.inputStream, 16 * 1024)
        output = fresh.outputStream
    }

    /**
     * Opens the link by the service's UUID (an SDP lookup of the channel), and
     * by the fixed channel number if that fails. Some phones cache the SDP
     * answer from pairing time, so a player paired before its SonixLink
     * service existed appears to have none.
     */
    @SuppressLint("MissingPermission")
    private fun open(device: android.bluetooth.BluetoothDevice): BluetoothSocket {
        val first = device.createRfcommSocketToServiceRecord(SERVICE)
        try {
            first.connect()
            return first
        } catch (e: IOException) {
            try {
                first.close()
            } catch (ignored: IOException) {
                // Never opened.
            }
            val byChannel = try {
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, CHANNEL) as BluetoothSocket
            } catch (reflection: Exception) {
                throw e
            }
            try {
                byChannel.connect()
            } catch (second: IOException) {
                try {
                    byChannel.close()
                } catch (ignored: IOException) {
                    // Never opened.
                }
                // The first failure is the cause; it goes first and is chained.
                throw IOException("${e.message} / ${second.message}", e)
            }
            // The UUID lookup failing and the channel answering means the
            // phone's SDP record of the player is stale: request a fresh one
            // so the next connection can find the service by UUID.
            try {
                device.fetchUuidsWithSdp()
            } catch (ignored: Exception) {
                // Only a refresh for next time.
            }
            return byChannel
        }
    }

    private fun drop() {
        try {
            socket?.close()
        } catch (e: IOException) {
            // Already gone.
        }
        socket = null
        input = null
        output = null
    }

    override fun close() {
        synchronized(lock) { drop() }
    }

    override fun exchange(path: String, method: String, readTimeout: Int, bulk: Boolean, body: ByteArray?): Response {
        busy.acquire(bulk)
        try {
            val response = synchronized(lock) {
                val hadLink = socket != null
                try {
                    send(path, method, readTimeout, body)
                } catch (e: IOException) {
                    drop()
                    // An existing link may have been closed by the player (it drops
                    // links idle for 30 s): reopen once and retry. A link that could
                    // not be opened at all is not retried.
                    if (!hadLink) throw e
                    send(path, method, readTimeout, body)
                }
            }
            return response
        } catch (e: Exception) {
            busy.release()
            throw e
        }
    }

    private fun send(path: String, method: String, readTimeout: Int, body: ByteArray?): Response {
        if (socket == null) connect()
        val out = output ?: throw IOException("not connected")
        val inp = input ?: throw IOException("not connected")
        val length = if (body != null) "Content-Length: ${body.size}\r\n" else ""
        out.write("$method $path HTTP/1.1\r\nHost: sonixlink\r\nConnection: keep-alive\r\n$length\r\n".toByteArray())
        if (body != null) out.write(body)
        out.flush()

        // A Bluetooth socket has no read timeout of its own: a player that
        // never answers would hold this thread for ever. Closing the socket
        // from here is what ends the wait.
        val stuck = socket
        val watchdog = timer.schedule({
            try {
                stuck?.close()
            } catch (e: IOException) {
                // Already gone.
            }
        }, readTimeout.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
        try {
            return readHead(inp)
        } finally {
            watchdog.cancel(false)
        }
    }

    private fun readHead(inp: InputStream): Response {
        val status = readLine(inp) ?: throw IOException("the player closed the link")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer: $status")
        var length = -1L
        while (true) {
            val line = readLine(inp) ?: throw IOException("the player closed the link")
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                length = line.substring(colon + 1).trim().toLongOrNull() ?: -1L
            }
        }
        if (length < 0) throw IOException("an answer with no length")
        return Response(code, length, Bounded(inp, length)) { busy.release() }
    }

    /** A line of the header, without its CRLF. Null at the end of the stream. */
    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
        }
        return bytes.toString("UTF-8")
    }

    /**
     * The body of one answer: exactly `length` bytes of the link, then end of
     * stream, so the next answer starts where this one ends. Closing it drains
     * whatever is left unread.
     */
    private class Bounded(private val source: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            val b = source.read()
            if (b >= 0) left--
            return b
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (left <= 0) return -1
            val want = minOf(length.toLong(), left).toInt()
            val n = source.read(buffer, offset, want)
            if (n > 0) left -= n
            return n
        }

        override fun available(): Int = minOf(source.available().toLong(), left).toInt()

        override fun close() {
            val skip = ByteArray(8192)
            while (left > 0) {
                val n = source.read(skip, 0, minOf(skip.size.toLong(), left).toInt())
                if (n <= 0) break
                left -= n
            }
        }
    }
}

/**
 * A single-holder lock in which waiting small requests go before waiting bulk
 * ones, so a state read or a command does not queue behind covers and
 * thumbnail pages, which each take a sizeable fraction of a second over Bluetooth.
 */
class PriorityGate {
    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val freed = lock.newCondition()
    private var held = false
    private var urgentWaiting = 0

    fun acquire(bulk: Boolean) {
        lock.lock()
        try {
            if (bulk) {
                while (held || urgentWaiting > 0) freed.await()
            } else {
                urgentWaiting++
                try {
                    while (held) freed.await()
                } finally {
                    urgentWaiting--
                }
            }
            held = true
        } finally {
            lock.unlock()
        }
    }

    fun release() {
        lock.lock()
        try {
            held = false
            freed.signalAll()
        } finally {
            lock.unlock()
        }
    }
}
