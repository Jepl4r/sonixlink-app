package com.mattiadoronzo.sonixlink

/**
 * How the player orders its lists, so the app's copies match: per-list Z-A
 * ([desc]) and by-date ([added]) flags, and whether an artist's tracks are
 * grouped by album. Bit n of each mask is the player's list kind n
 * (LIBRARY_LIST_*), mirrored by the constants below.
 */
data class SortPrefs(
    val desc: Int = 0,
    val added: Int = 0,
    val artistByAlbum: Boolean = false,
    val favouritesReversed: Boolean = false,
) {
    fun reversed(kind: Int): Boolean = desc and (1 shl kind) != 0
    fun byDate(kind: Int): Boolean = added and (1 shl kind) != 0

    companion object {
        const val TRACKS = 0
        const val ALBUMS = 1
        const val ARTISTS = 2
        const val ALBUM_ARTISTS = 3

        fun fromJson(json: org.json.JSONObject?): SortPrefs? {
            if (json == null) return null
            return SortPrefs(
                desc = json.optInt("desc", 0),
                added = json.optInt("added", 0),
                artistByAlbum = json.optBoolean("artist_by_album", false),
                favouritesReversed = json.optBoolean("favourites_reversed", false),
            )
        }
    }
}

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
    /** Which player this is, whichever way it is reached. Empty from a player that does not send it. */
    val serial: String = "",
    /**
     * Fingerprints of the index and thumbnail content the phone reads. They
     * change with the library or the thumbnails, unlike the files' mtimes,
     * which also move whenever the player saves the playback position.
     * Empty from a player that does not send them.
     */
    val dbHash: String = "",
    val coversHash: String = "",
    /** How the player orders its lists. Null from a player that does not say. */
    val sort: SortPrefs? = null,
) {
    /** The player, for remembering whose files the phone holds. */
    fun identity(host: String): String = serial.ifEmpty { host }

    /** What the phone's copy of the index has to match. */
    val dbVersion: String get() = dbHash.ifEmpty { dbMtime.toString() }
    val coversVersion: String get() = coversHash.ifEmpty { coversMtime.toString() }
}

/** The five modes, in the order the button cycles them; matches the player's own order. */
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
    /** Changes whenever the queue does. -1 from a player that does not say. */
    val queueRevision: Long,
    val scanning: Boolean,
    val scanCount: Int,
    val tracks: Int,
    /** How the player orders its lists. Null from a player that does not say. */
    val sort: SortPrefs? = null,
    /**
     * The "4/12" the player's own screen shows (under shuffle, the place in the
     * shuffled order); 0 and 0 where it shows nothing. -1 from a player that
     * does not send it, in which case the queue's numbers apply.
     */
    val displayPosition: Int = -1,
    val displayCount: Int = -1,
    /** The album's artist from the tags; empty when they have none. */
    val albumArtist: String = "",
    /**
     * Change whenever the favourites, or any playlist, do on the player. -1
     * from a player that does not send them.
     */
    val favouritesRevision: Long = -1,
    val playlistsRevision: Long = -1,
) {
    val isPlaying: Boolean get() = playState == 1
    val hasTrack: Boolean get() = title.isNotEmpty() || path.isNotEmpty()

    companion object {
        val EMPTY = PlayerState(
            0, PlayMode.NORMAL, 0, 0, 0, "", "", "", "",
            0, 0, 0, false, -1, false, false, "", -1, 0, -1, false, 0, 0,
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
    /** A real row, a section title (the search results), or a placeholder. */
    val kind: Kind = Kind.ROW,
    /**
     * The first two characters of the `sortkey` the list is ordered by: the
     * strip's letter comes from there rather than being guessed from the title.
     */
    val sortKey: String = "",
    /** This row's icon, where a list mixes rows of different kinds. */
    val iconRes: Int = 0,
) {
    /** PENDING: a row whose content has not arrived yet (the queue's pages). */
    enum class Kind { ROW, HEADER, PENDING }

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
    FOLDERS(R.string.tab_folders, R.drawable.ic_folder),
}
