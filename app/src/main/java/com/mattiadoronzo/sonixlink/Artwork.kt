package com.mattiadoronzo.sonixlink

/**
 * The playing track's artwork, shared by the now-playing screen and the
 * notification.
 *
 * Keeps the bytes of the last few covers and fetches one cover at a time; a
 * caller asking for a cover already on its way waits for it. A caller whose
 * track changed while it waited gets [Stale] instead of a fetch, so only the
 * cover of the track that ends up playing crosses the link.
 */
object Artwork {

    /** The side asked for over Bluetooth: enough for a phone's cover, a few tens of KB. */
    const val BLUETOOTH_SIDE = 640

    /** The side asked for over Wi-Fi: as wide as a phone's screen. */
    const val WIFI_SIDE = 1080

    /** How often a cover the player is still making is asked for again. */
    private const val PENDING_RETRY_MS = 200L

    /** How long a cover is waited for before the caller is told to try later. */
    private const val PENDING_GIVE_UP_MS = 15_000L

    /** What a caller no longer interested gets instead of a cover. */
    class Stale : Exception("the track has changed")

    private const val KEEP = 4

    private val lock = Object()

    /** Path to bytes, null meaning "the track has no cover". Oldest first. */
    private val kept = LinkedHashMap<String, ByteArray?>()
    private var fetching = false

    /**
     * The cover of `path`: from memory, or from the player. Null when the track
     * has none. Throws [Stale] when `stillWanted` turns false before the fetch
     * starts, and whatever the client throws when the player cannot be asked.
     * Blocks: call from a background thread.
     */
    fun get(client: PlayerClient, path: String, stillWanted: () -> Boolean): ByteArray? {
        synchronized(lock) {
            while (true) {
                if (kept.containsKey(path)) {
                    val bytes = kept.remove(path)
                    kept[path] = bytes
                    return bytes
                }
                if (!stillWanted()) throw Stale()
                if (!fetching) break
                (lock as java.lang.Object).wait(250)
            }
            fetching = true
        }
        try {
            val side = if (client.isBluetooth) BLUETOOTH_SIDE else WIFI_SIDE
            // The player scales the cover on a thread of its own and answers
            // "not yet" (ArtPending) meanwhile; it is asked again until the
            // cover is ready, nobody wants it, or PENDING_GIVE_UP_MS passes.
            val started = android.os.SystemClock.uptimeMillis()
            var bytes: ByteArray? = null
            while (true) {
                try {
                    bytes = client.artwork(path, side)
                    break
                } catch (e: ArtPending) {
                    if (!stillWanted()) throw Stale()
                    if (android.os.SystemClock.uptimeMillis() - started > PENDING_GIVE_UP_MS) throw e
                    Thread.sleep(PENDING_RETRY_MS)
                }
            }
            synchronized(lock) {
                kept[path] = bytes
                while (kept.size > KEEP) {
                    kept.remove(kept.keys.first())
                }
            }
            return bytes
        } finally {
            synchronized(lock) {
                fetching = false
                (lock as java.lang.Object).notifyAll()
            }
        }
    }

    /** Another player, or none: nothing kept belongs to it. */
    fun clear() {
        synchronized(lock) { kept.clear() }
    }
}
