package com.mattiadoronzo.sonixlink

/**
 * Which category rows carry the playmark, matched as the player's lists match
 * them: an album by its name, an artist by the artist tag, an album artist by
 * the album-artist tag or, without one, the artist.
 */
object Playmark {

    fun album(row: Row, state: PlayerState): Boolean =
        state.album.isNotEmpty() && row.album.ifEmpty { row.title } == state.album

    fun artist(row: Row, state: PlayerState): Boolean =
        state.artist.isNotEmpty() && row.filter == state.artist

    fun albumArtist(row: Row, state: PlayerState): Boolean {
        val credited = state.albumArtist.ifEmpty { state.artist }
        return credited.isNotEmpty() && row.filter == credited
    }

    /** The test for a tab's top-level rows; null where the player marks none. */
    fun forSection(section: Section): ((Row, PlayerState) -> Boolean)? = when (section) {
        Section.ALBUMS -> ::album
        Section.ARTISTS -> ::artist
        Section.ALBUM_ARTISTS -> ::albumArtist
        else -> null
    }

    /** The test for a list that mixes kinds (the search results), by each row's icon. */
    fun byIcon(row: Row, state: PlayerState): Boolean = when (row.iconRes) {
        R.drawable.ic_album -> album(row, state)
        R.drawable.ic_artist -> artist(row, state)
        else -> false
    }
}
