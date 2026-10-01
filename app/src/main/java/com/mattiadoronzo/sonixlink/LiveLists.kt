package com.mattiadoronzo.sonixlink

/**
 * The lists that change on the player between index downloads -- the
 * favourites and the playlists -- read from the player as they stand, with
 * the downloaded index as the fallback when it does not answer.
 *
 * The player's answers carry path, title and artist; album and the artwork
 * fields the thumbnail key needs are filled in from the index. Blocking: call
 * from a background thread.
 */
object LiveLists {

    /** The favourites in the order the player's Favourites list shows them. */
    fun favourites(library: Library): List<Row> {
        val fresh = try {
            Session.client?.favourites()
        } catch (e: Exception) {
            null
        } ?: return library.favourites()
        // The player sends them newest first; its own list shows oldest first
        // unless reversed.
        val ordered = if (Session.sort.favouritesReversed) fresh else fresh.asReversed()
        return withIndex(library, ordered)
    }

    /**
     * The playlists, by name as the player lists them: a collator stands in
     * for the player's `listorder`, which ignores case and accents.
     */
    fun playlists(library: Library?): List<Row> {
        val fresh = try {
            Session.client?.playlists()
        } catch (e: Exception) {
            null
        } ?: return library?.playlists().orEmpty()
        val collator = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
        return fresh.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    /** One playlist's tracks in its order. `table` is a playlist row's filter, "M3U_" and the name. */
    fun playlistTracks(library: Library?, table: String): List<Row> {
        val fresh = try {
            Session.client?.playlistTracks(table.removePrefix("M3U_"))
        } catch (e: Exception) {
            null
        } ?: return library?.tracksOfPlaylist(table).orEmpty()
        return if (library == null) fresh else withIndex(library, fresh)
    }

    private fun withIndex(library: Library, rows: List<Row>): List<Row> {
        val known = library.tracksByPaths(rows.map { it.path })
        return rows.map { row ->
            val indexed = known[row.path] ?: return@map row
            row.copy(
                album = indexed.album,
                artPath = indexed.artPath,
                artMtime = indexed.artMtime,
                artSize = indexed.artSize,
            )
        }
    }
}
