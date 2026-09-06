package com.mattiadoronzo.sonixlink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Finding the player on the network.
 *
 * Two routes, so neither has to work every time:
 *
 *  * DNS-SD (`_sonixlink._tcp`), which Android resolves itself through
 *    NsdManager;
 *  * a plain UDP beacon on port 7801, sent by the player every two seconds and
 *    read with six lines of socket code. This is what carries the day on
 *    networks where multicast does not pass, and whenever mDNS sulks.
 */
class Discovery(private val context: Context) {

    data class Found(val name: String, val host: String, val port: Int) {
        override fun equals(other: Any?): Boolean =
            other is Found && other.host == host && other.port == port

        override fun hashCode(): Int = host.hashCode() * 31 + port
    }

    companion object {
        private const val TAG = "SonixLinkDiscovery"
        const val SERVICE_TYPE = "_sonixlink._tcp."
        const val BEACON_PORT = 7801
        private const val BEACON_PREFIX = "SONIXLINK1"
    }

    private var nsd: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private var beacon: BeaconListener? = null

    /**
     * Starts looking. `onFound` arrives on the main thread, once per player:
     * both routes can report the same one, and the duplicate is dropped here.
     */
    fun start(onFound: (Found) -> Unit) {
        val seen = HashSet<Found>()
        val report: (Found) -> Unit = { found ->
            synchronized(seen) {
                if (seen.add(found)) {
                    android.os.Handler(context.mainLooper).post { onFound(found) }
                }
            }
        }

        startNsd(report)
        startBeacon(report)
    }

    fun stop() {
        listener?.let { active ->
            try {
                nsd?.stopServiceDiscovery(active)
            } catch (e: IllegalArgumentException) {
                // Already stopped: NsdManager throws rather than ignoring.
            }
        }
        listener = null
        beacon?.shutdown()
        beacon = null
    }

    // -----------------------------------------------------------------------
    // mDNS
    // -----------------------------------------------------------------------

    private fun startNsd(report: (Found) -> Unit) {
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = manager

        // Resolutions go one at a time on older versions: two at once and the
        // second comes back FAILURE_ALREADY_ACTIVE.
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val service = pending.removeFirstOrNull() ?: return
            resolving = true
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "resolve failed: ${info.serviceName} ($errorCode)")
                    resolving = false
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress
                    if (host != null) {
                        report(Found(info.serviceName ?: host, host, info.port))
                    }
                    resolving = false
                    resolveNext()
                }
            })
        }

        val active = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.w(TAG, "discovery failed to start ($errorCode)")
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}

            override fun onDiscoveryStarted(type: String) {}

            override fun onDiscoveryStopped(type: String) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                pending.addLast(info)
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) {}
        }

        listener = active
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, active)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS unavailable: ${e.message}")
            listener = null
        }
    }

    // -----------------------------------------------------------------------
    // The plain beacon
    // -----------------------------------------------------------------------

    private fun startBeacon(report: (Found) -> Unit) {
        beacon = BeaconListener(context, report).also { it.start() }
    }

    private class BeaconListener(
        private val context: Context,
        private val report: (Found) -> Unit,
    ) : Thread("sonixlink-beacon") {

        @Volatile
        private var running = true
        private var socket: DatagramSocket? = null

        /** Not called stop(): that is a Thread method, and not this one. */
        fun shutdown() {
            running = false
            socket?.close()
        }

        override fun run() {
            // Without this lock many phones drop broadcast traffic to save
            // battery, and the beacon never arrives.
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = wifi?.createMulticastLock("sonixlink")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            try {
                val listening = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(java.net.InetSocketAddress(BEACON_PORT))
                    soTimeout = 1000
                }
                socket = listening

                val buffer = ByteArray(512)
                while (running) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        listening.receive(packet)
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        break
                    }
                    parse(String(packet.data, 0, packet.length, Charsets.UTF_8), packet.address)
                        ?.let(report)
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot listen for the beacon: ${e.message}")
            } finally {
                socket?.close()
                multicastLock?.let { if (it.isHeld) it.release() }
            }
        }

        /** "SONIXLINK1 <ip> <port> <name, spaces and all>" */
        private fun parse(message: String, from: InetAddress): Found? {
            val parts = message.trim().split(" ", limit = 4)
            if (parts.size < 3 || parts[0] != BEACON_PREFIX) return null
            val port = parts[2].toIntOrNull() ?: return null
            // Where it came from beats what it claims: that is the address
            // that actually reaches the player from this phone.
            val host = from.hostAddress ?: parts[1]
            val name = if (parts.size > 3 && parts[3].isNotBlank()) parts[3] else host
            return Found(name, host, port)
        }
    }
}
