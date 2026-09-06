package com.mattiadoronzo.sonixlink

/**
 * The connected player and its index, in one place.
 *
 * The library tabs are fragments Android recreates whenever it likes, and each
 * would have had to be handed the client and the database by the screen holding
 * it. Keeping them here saves passing them through four constructors and
 * opening the same SQLite file six times.
 */
object Session {

    @Volatile
    var client: PlayerClient? = null

    @Volatile
    var library: Library? = null

    @Volatile
    var covers: Covers? = null

    /** The last state read, to fill the bar before the next poll arrives. */
    @Volatile
    var state: PlayerState = PlayerState.EMPTY

    /**
     * The accent chosen on the player, as an ARGB colour. The app dresses itself
     * in this; the value below is only what is used until the player has said
     * its own (the Adwaita blue, which is also the player's default).
     *
     * Writing it tells whoever is watching. Without that, changing the accent on
     * the player recoloured only the screen that happened to be polling state:
     * the others kept the old colour until Android rebuilt them.
     */
    var accent: Int = 0xFF3584E4.toInt()
        set(value) {
            if (field == value) {
                return
            }
            field = value
            announceAccent()
        }

    private val accentWatchers = LinkedHashSet<() -> Unit>()

    /** Call in onStart and drop in onStop: a watcher holds on to a screen. */
    fun watchAccent(watcher: () -> Unit) {
        accentWatchers.add(watcher)
    }

    fun unwatchAccent(watcher: () -> Unit) {
        accentWatchers.remove(watcher)
    }

    private fun announceAccent() {
        // Watchers touch views. The accent is written from the main thread, but
        // one hop costs nothing and rules out the day someone writes it from a
        // network coroutine.
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == main) {
            accentWatchers.toList().forEach { it() }
        } else {
            android.os.Handler(main).post { accentWatchers.toList().forEach { it() } }
        }
    }

    /** "#rrggbb" to a colour. Keeps the current one if the string is not one. */
    fun parseAccent(text: String): Int {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6) return accent
        return try {
            0xFF000000.toInt() or hex.toInt(16)
        } catch (e: NumberFormatException) {
            accent
        }
    }

    fun clear() {
        library?.close()
        library = null
        covers?.close()
        covers = null
        client = null
        state = PlayerState.EMPTY
    }
}
