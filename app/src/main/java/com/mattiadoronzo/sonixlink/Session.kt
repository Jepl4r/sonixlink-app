package com.mattiadoronzo.sonixlink

/**
 * The connected player, its index and its covers, shared process-wide.
 *
 * The library tabs are fragments Android recreates at will, so they read the
 * client and the open database from here instead of having them passed in.
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
        set(value) {
            field = value
            stateAt = android.os.SystemClock.elapsedRealtime()
        }

    /**
     * When [state] was last set, on the elapsedRealtime clock. Lets the
     * service skip a poll while a screen in front is already polling.
     */
    @Volatile
    var stateAt: Long = 0L
        private set

    /**
     * How the player orders its lists; the library's queries follow it. Taken
     * from the last state or info read; the default matches the player's.
     */
    @Volatile
    var sort: SortPrefs = SortPrefs()

    /**
     * The mode and the favourite star as last set from the app, held over reads
     * that still carry the old values (see [Held]). Shared so the bar and the
     * now-playing screen agree.
     */
    val heldMode = Held<PlayMode>()
    val heldFavourite = Held<Pair<String, Boolean>>()

    /**
     * The accent chosen on the player, as an ARGB colour, used to tint the app.
     * The initial value is the player's default (Adwaita blue) until the player
     * reports its own. Setting a different value notifies every watcher, so all
     * open screens recolour, not only the one polling state.
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
        // Watchers touch views, so they always run on the main thread.
        val main = android.os.Looper.getMainLooper()
        if (android.os.Looper.myLooper() == main) {
            accentWatchers.toList().forEach { it() }
        } else {
            android.os.Handler(main).post { accentWatchers.toList().forEach { it() } }
        }
    }

    /** Parses "#rrggbb" to an opaque colour; returns the current accent if the text is not one. */
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
        Artwork.clear()
    }
}
