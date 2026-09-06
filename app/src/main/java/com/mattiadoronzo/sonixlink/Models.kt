package com.mattiadoronzo.sonixlink

/** Which player this is, and how old its index is. */
data class PlayerInfo(
    val name: String,
    val model: String,
    val firmware: String,
    val tracks: Int,
    val scanning: Boolean,
    val dbAvailable: Boolean,
    val dbSize: Long,
    val dbMtime: Long,
    val coversAvailable: Boolean,
    val coversSize: Long,
    val coversMtime: Long,
    /** The player's accent, "#rrggbb". */
    val accent: String,
)

/**
 * The five modes, in the order the button cycles them -- the player's own order,
 * so pressing the button here and pressing it there give the same sequence.
 */
enum class PlayMode(val wire: String, val label: Int, val icon: Int) {
    NORMAL("normal", R.string.mode_normal, R.drawable.ic_repeat_off),
    REPEAT_ALL("repeat_all", R.string.mode_repeat_all, R.drawable.ic_repeat),
    REPEAT_ONE("repeat_one", R.string.mode_repeat_one, R.drawable.ic_repeat_one),
    SHUFFLE("shuffle", R.string.mode_shuffle, R.drawable.ic_shuffle),
    SHUFFLE_REPEAT("shuffle_repeat", R.string.mode_shuffle_repeat, R.drawable.ic_shuffle_repeat);

    /** The mode after this one. */
    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromWire(value: String): PlayMode =
            entries.firstOrNull { it.wire == value } ?: NORMAL
    }
}

data class PlayerState(
    val playState: Int, // 0 stopped, 1 playing, 2 paused
    val mode: PlayMode,
    val volume: Int,
    val position: Int,
    val duration: Int,
    val title: String,
    val artist: String,
    val album: String,
    val path: String,
    val sampleRate: Int,
    val bits: Int,
    val bitrate: Int,
    val lossless: Boolean,
    val battery: Int,
    val charging: Boolean,
    val favourite: Boolean,
    /** The player's accent, "#rrggbb". Empty from a player that does not send it. */
    val accent: String,
    /** Where playback sits in the queue, and how long the queue is. -1 for neither. */
    val queuePosition: Int,
    val queueCount: Int,
    val scanning: Boolean,
    val scanCount: Int,
    val tracks: Int,
) {
    val isPlaying: Boolean get() = playState == 1
    val hasTrack: Boolean get() = title.isNotEmpty() || path.isNotEmpty()

    companion object {
        val EMPTY = PlayerState(
            0, PlayMode.NORMAL, 0, 0, 0, "", "", "", "",
            0, 0, 0, false, -1, false, false, "", -1, 0, false, 0, 0,
        )
    }
}

/** One row of a list: a track, or a category to open. */
data class Row(
    val title: String,
    val subtitle: String,
    /** The path, for a track. Empty for a category. */
    val path: String = "",
    /** What to filter by, for a category. */
    val filter: String = "",
    val count: Int = 0,
    /** The album, for the subtitle where one is wanted. */
    val album: String = "",
    // The track the thumbnail comes from, and the two numbers that go into its
    // key alongside the path. For a category, any track of that category.
    val artPath: String = "",
    val artMtime: Long = 0,
    val artSize: Long = 0,
    /** A real row, or a section title (the search results). */
    val kind: Kind = Kind.ROW,
    /**
     * The first two characters of the `sortkey` the list is ordered by: the
     * strip's letter comes from there rather than being guessed from the title.
     */
    val sortKey: String = "",
    /** This row's icon, where a list mixes rows of different kinds. */
    val iconRes: Int = 0,
) {
    enum class Kind { ROW, HEADER }

    val isTrack: Boolean get() = path.isNotEmpty()
}

/** What a tab shows: a list of tracks, or a list of categories. */
enum class Section(val titleRes: Int, val icon: Int) {
    TRACKS(R.string.tab_tracks, R.drawable.ic_track),
    ALBUMS(R.string.tab_albums, R.drawable.ic_album),
    ARTISTS(R.string.tab_artists, R.drawable.ic_artist),
    ALBUM_ARTISTS(R.string.tab_album_artists, R.drawable.ic_album_artist),
    PLAYLISTS(R.string.tab_playlists, R.drawable.ic_playlist),
    FAVOURITES(R.string.tab_favourites, R.drawable.ic_favourite),
}
