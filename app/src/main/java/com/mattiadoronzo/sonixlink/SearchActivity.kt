package com.mattiadoronzo.sonixlink

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattiadoronzo.sonixlink.databinding.ActivitySearchBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The search page, laid out like the player's: the field at the top and the
 * results split into tracks, albums and artists. An album or artist opens here,
 * on its tracks.
 *
 * A long press starts selection mode as in the library tabs: tracks, albums
 * and artists can be chosen together and go after the playing track, into the
 * favourites or out of them, or into a playlist.
 */
class SearchActivity : AppCompatActivity() {

    companion object {
        /** Typing pause before a search; matches the player's SEARCH_DEBOUNCE_MS. */
        private const val DEBOUNCE_MS = 350L

        /** Results per category, as on the player. */
        private const val PER_CATEGORY = 12

        /**
         * How often the playmark follows the playing track. Nothing is asked of
         * the player: the service keeps Session.state fresh while this page is
         * in front.
         */
        private const val MARK_MS = 1000L
    }

    private lateinit var views: ActivitySearchBinding
    private lateinit var adapter: RowAdapter

    private val clock = Handler(Looper.getMainLooper())
    private val runSearch = Runnable { search() }
    private val followPlaying = object : Runnable {
        override fun run() {
            adapter.playing = Session.state
            clock.postDelayed(this, MARK_MS)
        }
    }

    /** The category opened from the results, if one was. */
    private var opened: Row? = null
    private var openedIsArtist = false

    /**
     * The latest query's number. A slower, older query can finish after a
     * newer one; its results are dropped when the numbers differ.
     */
    private var generation = 0

    /** Repaints the bound rows, whose playing row carries the accent. */
    private val accentWatch = {
        if (::adapter.isInitialized) adapter.repaint()
        if (::volume.isInitialized) volume.accent()
    }

    private lateinit var volume: VolumePill

    /** The phone's volume keys set the player's volume. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        volume.onKey(event) || super.dispatchKeyEvent(event)

    override fun onStart() {
        super.onStart()
        Session.watchAccent(accentWatch)
        accentWatch()
        // A single read: nothing polls state here, and the volume keys start
        // from Session.state.
        refreshVolume()
        followPlaying.run()
    }

    override fun onStop() {
        super.onStop()
        Session.unwatchAccent(accentWatch)
        clock.removeCallbacks(followPlaying)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(views.root)

        volume = VolumePill(views.volume) { level -> sendVolume(level) }

        adapter = RowAdapter(R.drawable.ic_track) { at, row -> onRowClicked(at, row) }
        adapter.onLongPress = { at, row -> onRowLongPressed(at, row) }
        adapter.marksCategory = Playmark::byIcon
        views.list.layoutManager = LinearLayoutManager(this)
        views.list.adapter = adapter

        views.backButton.setOnClickListener { finish() }
        views.headerBack.setOnClickListener { closeCategory() }
        views.selClose.setOnClickListener { stopSelection() }
        views.selQueue.setOnClickListener { actOnSelection(Selection.Action.QUEUE) }
        views.selFavAdd.setOnClickListener { actOnSelection(Selection.Action.FAVOURITES_ADD) }
        views.selFavRemove.setOnClickListener { actOnSelection(Selection.Action.FAVOURITES_REMOVE) }
        views.selPlaylistAdd.setOnClickListener { actOnSelection(Selection.Action.PLAYLIST_ADD) }
        views.clearButton.setOnClickListener {
            views.searchInput.setText("")
            views.searchInput.requestFocus()
        }

        views.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                views.clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                // Typing closes the open category without closeCategory(),
                // which would search once more besides the delayed callback.
                if (opened != null) {
                    opened = null
                    updateHeader()
                }
                clock.removeCallbacks(runSearch)
                clock.postDelayed(runSearch, DEBOUNCE_MS)
            }
        })

        // Posted: called directly from onCreate, before the window has focus,
        // showSoftInput does nothing on most phones.
        views.searchInput.requestFocus()
        views.searchInput.post {
            val manager = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.showSoftInput(views.searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clock.removeCallbacks(runSearch)
        volume.release()
    }

    private fun refreshVolume() {
        val client = Session.client ?: return
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                try {
                    client.state()
                } catch (e: Exception) {
                    null
                }
            } ?: return@launch
            Session.state = state
        }
    }

    /** Sends the pill's level off the main thread. */
    private fun sendVolume(level: Int) {
        val client = Session.client ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    client.setVolume(level)
                } catch (e: Exception) {
                    // Ignored: the pill keeps the level it set.
                }
            }
        }
    }

    override fun onBackPressed() {
        // Back first leaves selection mode, as in the library tabs.
        if (adapter.selecting) {
            stopSelection()
            return
        }
        if (opened != null) {
            closeCategory()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun search() {
        val text = views.searchInput.text?.toString().orEmpty().trim()
        val library = Session.library
        if (text.isEmpty() || library == null) {
            adapter.submit(emptyList())
            views.emptyText.visibility = View.GONE
            return
        }

        val mine = ++generation
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val out = ArrayList<Row>()
                val tracks = library.search(text, PER_CATEGORY)
                if (tracks.isNotEmpty()) {
                    out.add(header(getString(R.string.tab_tracks)))
                    out.addAll(tracks)
                }
                val albums = library.albumsMatching(text, PER_CATEGORY)
                if (albums.isNotEmpty()) {
                    out.add(header(getString(R.string.tab_albums)))
                    // Per-row icons: this list mixes tracks, albums and artists.
                    out.addAll(albums.map { it.copy(iconRes = R.drawable.ic_album) })
                }
                val artists = library.artistsMatching(text, PER_CATEGORY)
                if (artists.isNotEmpty()) {
                    out.add(header(getString(R.string.tab_artists)))
                    out.addAll(artists.map { it.copy(iconRes = R.drawable.ic_artist) })
                }
                out
            }
            if (mine != generation) return@launch
            adapter.submit(rows)
            updateHeader()
            views.list.scrollToPosition(0)
            views.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** A section title row. */
    private fun header(label: String) = Row(title = label, subtitle = "", kind = Row.Kind.HEADER)

    private fun onRowClicked(at: Int, row: Row) {
        if (adapter.selecting) {
            if (selectable(row)) {
                if (adapter.toggle(at) == 0) stopSelection() else updateHeader()
            }
            return
        }
        when {
            row.kind == Row.Kind.HEADER -> Unit
            row.isTrack -> Session.client?.let { play(row) }
            else -> openCategory(row)
        }
    }

    private fun play(row: Row) {
        val category = opened
        val list = when {
            category == null -> "all"
            openedIsArtist -> "artist"
            else -> "album"
        }
        val value = category?.filter.orEmpty()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    Session.client?.playPath(row.path, list, value)
                } catch (e: Exception) {
                    // Ignored: the player's state shows the outcome.
                }
            }
        }
    }

    private fun openCategory(row: Row) {
        val library = Session.library ?: return
        val mine = ++generation
        // Artist rows have an empty album; album rows do not.
        openedIsArtist = row.album.isEmpty()
        opened = row
        updateHeader()

        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                if (openedIsArtist) library.tracksOfArtist(row.filter) else library.tracksOfAlbum(row.filter)
            }
            if (mine != generation) return@launch
            adapter.submit(rows)
            updateHeader()
            views.list.scrollToPosition(0)
            views.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun closeCategory() {
        opened = null
        updateHeader()
        search()
    }

    // -----------------------------------------------------------------------
    // Selection mode
    // -----------------------------------------------------------------------

    /**
     * The title row: the open album or artist with the way back, or in
     * selection mode the count and the actions. Hidden when it has neither.
     */
    private fun updateHeader() {
        val selecting = adapter.selecting
        val category = opened
        views.headerBack.visibility = if (!selecting && category != null) View.VISIBLE else View.GONE
        views.headerTitle.text = when {
            selecting -> getString(R.string.selected_count, adapter.selected.size)
            category != null -> category.title
            else -> ""
        }
        listOf(views.selQueue, views.selFavAdd, views.selFavRemove, views.selPlaylistAdd, views.selClose)
            .forEach { it.visibility = if (selecting) View.VISIBLE else View.GONE }
        views.header.visibility = if (selecting || category != null) View.VISIBLE else View.GONE
    }

    /** A track, an album or an artist: everything but the section titles. */
    private fun selectable(row: Row): Boolean =
        row.kind == Row.Kind.ROW && (row.isTrack || row.filter.isNotEmpty())

    private fun onRowLongPressed(at: Int, row: Row) {
        if (adapter.selecting) {
            onRowClicked(at, row)
            return
        }
        if (!selectable(row)) return
        adapter.startSelection(at)
        updateHeader()
    }

    private fun stopSelection() {
        adapter.stopSelection()
        updateHeader()
    }

    /** Which kind of category a result row is, by its icon. */
    private fun kindOf(row: Row): Section? = when (row.iconRes) {
        R.drawable.ic_album -> Section.ALBUMS
        R.drawable.ic_artist -> Section.ARTISTS
        else -> null
    }

    private fun actOnSelection(action: Selection.Action) {
        val rows = adapter.selected.mapNotNull { adapter.rowAt(it) }
        if (rows.isEmpty()) return
        lifecycleScope.launch {
            val paths = withContext(Dispatchers.IO) { Selection.paths(rows, ::kindOf) }
            if (paths.isEmpty()) return@launch
            when (action) {
                Selection.Action.QUEUE -> {
                    stopSelection()
                    perform(getString(R.string.done_queue)) { it.selection("queue_next", paths) }
                }
                Selection.Action.FAVOURITES_ADD -> {
                    stopSelection()
                    perform(getString(R.string.done_fav)) { it.selection("favourites_add", paths) }
                }
                Selection.Action.FAVOURITES_REMOVE -> {
                    stopSelection()
                    perform(getString(R.string.done_removed)) { it.selection("favourites_remove", paths) }
                }
                Selection.Action.PLAYLIST_ADD -> Selection.pickPlaylist(
                    this@SearchActivity,
                    lifecycleScope,
                ) { name ->
                    stopSelection()
                    perform(getString(R.string.done_playlist)) { it.selection("playlist_add", paths, name) }
                }
                Selection.Action.PLAYLIST_REMOVE -> Unit
            }
        }
    }

    /**
     * Sends a selection to the player and says how it went. The library tabs
     * behind this page catch up through the favourites and playlists revisions
     * once they are in front again.
     */
    private fun perform(done: String, action: (PlayerClient) -> Unit) {
        val client = Session.client ?: return
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) {
                try {
                    action(client)
                    false
                } catch (e: Exception) {
                    true
                }
            }
            val message = if (failed) getString(R.string.command_failed) else done
            com.google.android.material.snackbar.Snackbar
                .make(views.root, message, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .show()
        }
    }
}
