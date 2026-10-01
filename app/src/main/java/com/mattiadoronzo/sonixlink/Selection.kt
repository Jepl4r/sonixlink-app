package com.mattiadoronzo.sonixlink

import android.content.Context
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the library tabs and the search page share in selection mode: the
 * tracks a chosen row stands for, and the playlist picker.
 */
object Selection {

    enum class Action { QUEUE, FAVOURITES_ADD, FAVOURITES_REMOVE, PLAYLIST_ADD, PLAYLIST_REMOVE }

    /**
     * The chosen rows as tracks, in the list's order and each once: a record,
     * an artist or a playlist stands for its tracks, as in the player's
     * selection. `kindOf` says which kind of category a row is. Blocking.
     */
    fun paths(rows: List<Row>, kindOf: (Row) -> Section?): List<String> {
        val library = Session.library
        val out = LinkedHashSet<String>()
        for (row in rows) {
            if (row.isTrack) {
                out.add(row.path)
                continue
            }
            if (row.filter.isEmpty()) continue
            val tracks = when (kindOf(row)) {
                Section.ALBUMS -> library?.tracksOfAlbum(row.filter)
                Section.ARTISTS -> library?.tracksOfArtist(row.filter)
                Section.ALBUM_ARTISTS -> library?.tracksOfAlbumArtist(row.filter)
                Section.PLAYLISTS -> LiveLists.playlistTracks(library, row.filter)
                else -> null
            }
            tracks?.forEach { if (it.path.isNotEmpty()) out.add(it.path) }
        }
        return out.toList()
    }

    /**
     * Which playlist: one of the player's, as they stand now, or a new one by
     * name -- the player makes it when it is not there.
     */
    fun pickPlaylist(context: Context, scope: CoroutineScope, chosen: (String) -> Unit) {
        scope.launch {
            val names = withContext(Dispatchers.IO) { LiveLists.playlists(Session.library).map { it.title } }
            val items = (listOf(context.getString(R.string.playlist_new)) + names).toTypedArray()
            AlertDialog.Builder(context)
                .setTitle(R.string.sel_playlist_add)
                .setItems(items) { _, which ->
                    if (which == 0) askPlaylistName(context, chosen) else chosen(names[which - 1])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun askPlaylistName(context: Context, chosen: (String) -> Unit) {
        val field = android.widget.EditText(context).apply {
            setHint(R.string.playlist_name_hint)
            setSingleLine(true)
        }
        val box = android.widget.FrameLayout(context).apply {
            val side = (20 * context.resources.displayMetrics.density).toInt()
            setPadding(side, side / 2, side, 0)
            addView(field)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.playlist_new)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Strips the characters a file name cannot hold; the player
                // refuses them too.
                val name = field.text.toString().trim().replace(Regex("[/\\\\:*?\"<>|]"), "")
                if (name.isNotEmpty()) chosen(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
