package com.mattiadoronzo.sonixlink

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import android.view.MotionEvent
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattiadoronzo.sonixlink.databinding.FragmentBrowseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One tab of the library.
 *
 * One class for every [Section]: only the query differs, and the Folders tab
 * asks the player instead of the index. Categories open inside the tab, and
 * back returns to the list of categories.
 */
class BrowseFragment : Fragment(), MainActivity.LibraryListener {

    companion object {
        private const val ARG_SECTION = "section"

        /** The player's INDEX_HINT_MS: the letter outstays the finger a moment. */
        private const val HINT_MS = 450L

        fun of(section: Section): BrowseFragment = BrowseFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, section.name) }
        }
    }

    private var views: FragmentBrowseBinding? = null
    private lateinit var section: Section
    private lateinit var adapter: RowAdapter

    /** The open category, if one was entered. */
    private var opened: Row? = null

    /**
     * The Folders tab: the folder on screen ("" before the first answer, which
     * is the card's root), the one above it ("" at the root), and where each
     * level above was scrolled to, for the way back up.
     */
    private var folderPath = ""
    private var folderParent = ""
    private val folderScroll = ArrayList<Pair<Int, Int>>()

    /**
     * The scroll position of the list when a category or folder was opened:
     * the first visible row and its top offset in pixels, restored on the way back.
     */
    private var savedRow = 0
    private var savedOffset = 0

    /** The big letter's disc, and which accent the drawn one is. */
    private val hint = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideHint = Runnable { views?.indexHint?.visibility = View.GONE }
    private var hintAccent = 0

    /**
     * Repaints rows and header when the player's accent changes. Held in a
     * field so the same instance can be passed to `Session.unwatchAccent`.
     */
    private val accentWatch = {
        hintAccent = 0
        if (::adapter.isInitialized) {
            adapter.repaint()
            updateHeader()
        }
    }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // Back first leaves selection mode, as on the player.
            if (adapter.selecting) {
                stopSelection()
                return
            }
            if (section == Section.FOLDERS) {
                if (folderParent.isNotEmpty()) {
                    val scroll = folderScroll.removeLastOrNull()
                    loadFolder(folderParent, scroll)
                }
                return
            }
            opened = null
            updateBack()
            load(restore = true)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        section = Section.valueOf(arguments?.getString(ARG_SECTION) ?: Section.TRACKS.name)
        val binding = FragmentBrowseBinding.inflate(inflater, container, false)
        views = binding
        // The hint disc belonged to the previous view: force a rebuild.
        hintAccent = 0

        adapter = RowAdapter(section.icon, leadingFor(opened)) { at, row -> onRowClicked(at, row) }
        adapter.onLongPress = { at, row -> onRowLongPressed(at, row) }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.headerBack.setOnClickListener { back.handleOnBackPressed() }
        binding.playMenu.setOnClickListener { showPlayMenu() }
        binding.selClose.setOnClickListener { stopSelection() }
        binding.selQueue.setOnClickListener { actOnSelection(Action.QUEUE) }
        binding.selFavAdd.setOnClickListener { actOnSelection(Action.FAVOURITES_ADD) }
        binding.selFavRemove.setOnClickListener { actOnSelection(Action.FAVOURITES_REMOVE) }
        binding.selPlaylistAdd.setOnClickListener { actOnSelection(Action.PLAYLIST_ADD) }
        binding.selPlaylistRemove.setOnClickListener { actOnSelection(Action.PLAYLIST_REMOVE) }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (section == Section.FOLDERS) {
            loadFolder(folderPath, null)
        } else {
            load()
        }
    }

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.addLibraryListener(this)
        Session.watchAccent(accentWatch)
    }

    override fun onStop() {
        super.onStop()
        (activity as? MainActivity)?.removeLibraryListener(this)
        Session.unwatchAccent(accentWatch)
    }

    // Neighbouring tabs are built too and register their own callbacks, and the
    // dispatcher picks the last enabled one: the callback is enabled only while
    // this tab is resumed.
    override fun onResume() {
        super.onResume()
        updateBack()
    }

    /** Back counts while this tab is in front and has somewhere to go back from. */
    private fun updateBack() {
        back.isEnabled = isResumed &&
            (adapter.selecting || if (section == Section.FOLDERS) folderParent.isNotEmpty() else opened != null)
    }

    override fun onPause() {
        super.onPause()
        back.isEnabled = false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        hint.removeCallbacks(hideHint)
        views = null
    }

    override fun onOrderChanged() {
        if (section != Section.FOLDERS && section != Section.PLAYLISTS) {
            load(restore = false)
        }
    }

    override fun onFavouritesChanged() {
        if (section == Section.FAVOURITES && opened == null) {
            load()
        }
    }

    private fun onRowClicked(at: Int, row: Row) {
        if (adapter.selecting) {
            if (selectable(row)) {
                if (adapter.toggle(at) == 0) stopSelection() else updateHeader()
            }
            return
        }
        if (section == Section.FOLDERS) {
            if (row.isTrack) {
                // As in the player's browser, the folder becomes the queue.
                (activity as? MainActivity)?.play(row.path, "folder", "")
            } else {
                rememberPosition()
                folderScroll.add(savedRow to savedOffset)
                loadFolder(row.filter, null)
            }
            return
        }
        if (row.isTrack) {
            // The queue becomes the list on screen; the player builds it from
            // the list name and value sent here.
            val category = opened
            val (list, value) = when {
                section == Section.FAVOURITES -> "favourites" to ""
                category == null -> "all" to ""
                section == Section.ALBUMS -> "album" to category.filter
                section == Section.ARTISTS -> "artist" to category.filter
                section == Section.ALBUM_ARTISTS -> "album_artist" to category.filter
                // The player takes the playlist's name, not its "M3U_" table name.
                section == Section.PLAYLISTS -> "playlist" to category.filter.removePrefix("M3U_")
                else -> "all" to ""
            }
            (activity as? MainActivity)?.play(row.path, list, value)
            return
        }
        rememberPosition()
        opened = row
        updateBack()
        load()
    }

    private fun rememberPosition() {
        val manager = views?.list?.layoutManager as? LinearLayoutManager ?: return
        savedRow = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        savedOffset = manager.findViewByPosition(savedRow)?.top ?: 0
    }

    private fun load(restore: Boolean = false) {
        val binding = views ?: return
        val library = Session.library
        val category = opened

        updateHeader()
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                if (library == null) emptyList() else rowsFor(library, category)
            }
            val current = views ?: return@launch
            adapter.leading = leadingFor(category)
            current.progress.visibility = View.GONE
            adapter.submit(rows)
            current.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

            val manager = current.list.layoutManager as? LinearLayoutManager
            if (restore) {
                // Coming back from a category, the list stays where it was.
                manager?.scrollToPositionWithOffset(savedRow, savedOffset)
            } else {
                current.list.scrollToPosition(0)
            }
            buildLetterStrip()
            updateHeader()
        }
    }

    // -----------------------------------------------------------------------
    // The letter strip
    // -----------------------------------------------------------------------

    /**
     * Builds the strip from the letters the list has, as on the player. Shown
     * only on top-level lists sorted by name, with at least four letters.
     */
    private fun buildLetterStrip() {
        val binding = views ?: return
        val strip = binding.letterStrip
        strip.removeAllViews()

        // A list sorted by date added gets no strip.
        val kind = when (section) {
            Section.TRACKS -> SortPrefs.TRACKS
            Section.ALBUMS -> SortPrefs.ALBUMS
            Section.ARTISTS -> SortPrefs.ARTISTS
            Section.ALBUM_ARTISTS -> SortPrefs.ALBUM_ARTISTS
            else -> -1
        }
        val wanted = opened == null && kind >= 0 && !Session.sort.byDate(kind)
        val letters = if (wanted) adapter.letters() else emptyList()
        if (letters.size < 4) {
            strip.visibility = View.GONE
            binding.indexHint.visibility = View.GONE
            hint.removeCallbacks(hideHint)
            binding.list.setPadding(0, 0, 0, 0)
            return
        }
        // The strip floats over the list: the padding keeps it off the chevrons
        // and their taps.
        binding.list.setPadding(0, 0, (26 * resources.displayMetrics.density).toInt(), 0)

        val secondary = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary)
        for (letter in letters) {
            val label = TextView(requireContext()).apply {
                text = letter.toString()
                textSize = 10f
                setTextColor(secondary)
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            }
            strip.addView(label)
        }
        strip.visibility = View.VISIBLE

        // Dragging along the strip scrolls the list, as on the player.
        strip.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val count = letters.size
                    if (count > 0 && view.height > 0) {
                        val at = (event.y / view.height * count).toInt().coerceIn(0, count - 1)
                        showHint(letters[at])
                        jumpTo(letters[at])
                    }
                    true
                }

                else -> {
                    hideHintSoon()
                    view.performClick()
                    true
                }
            }
        }
    }

    /**
     * The letter under the finger, drawn large on an accent disc like the
     * player's `index_hint`. It stays [HINT_MS] after the finger lifts.
     */
    private fun showHint(letter: Char) {
        val binding = views ?: return
        // Runs on every touch move: the disc is rebuilt only when the accent changes.
        if (hintAccent != Session.accent) {
            hintAccent = Session.accent
            binding.indexHint.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26 * resources.displayMetrics.density
                setColor(hintAccent)
                alpha = 230
            }
        }
        binding.indexHintLetter.text = letter.toString()
        binding.indexHint.visibility = View.VISIBLE
        hint.removeCallbacks(hideHint)
    }

    private fun hideHintSoon() {
        hint.removeCallbacks(hideHint)
        hint.postDelayed(hideHint, HINT_MS)
    }

    private fun jumpTo(letter: Char) {
        val binding = views ?: return
        val at = adapter.firstStartingWith(letter)
        if (at < 0) return
        (binding.list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(at, 0)
    }

    /**
     * The square on the left: none on the top-level lists of artists, album
     * artists and playlists; inside any category it keeps track titles aligned
     * where artwork is missing.
     */
    private fun leadingFor(category: Row?): RowAdapter.Leading = when {
        category != null -> RowAdapter.Leading.ICON
        section == Section.ARTISTS || section == Section.ALBUM_ARTISTS || section == Section.PLAYLISTS ->
            RowAdapter.Leading.NONE
        else -> RowAdapter.Leading.ICON
    }

    // -----------------------------------------------------------------------
    // The title row: where it is, circle-play, selection mode
    // -----------------------------------------------------------------------

    /**
     * The row above the list, as the player's title row: the way back and the
     * name of what is open, circle-play where "play all of this" means
     * something, and in selection mode the count and what can be done with
     * the chosen tracks. Shown only when it has something to show.
     */
    private fun updateHeader() {
        val binding = views ?: return
        val selecting = adapter.selecting
        val category = opened
        val inFolder = section == Section.FOLDERS && folderParent.isNotEmpty()
        val canGoBack = !selecting && (category != null || inFolder)
        val play = !selecting && playTarget() != null

        binding.headerBack.visibility = if (canGoBack) View.VISIBLE else View.GONE
        binding.headerTitle.text = when {
            selecting -> getString(R.string.selected_count, adapter.selected.size)
            category != null -> category.title
            inFolder -> folderPath.substringAfterLast('/')
            play -> getString(section.titleRes)
            else -> ""
        }

        val inFavourites = section == Section.FAVOURITES
        val inPlaylist = section == Section.PLAYLISTS && category != null
        fun show(button: View, wanted: Boolean) {
            button.visibility = if (wanted) View.VISIBLE else View.GONE
        }
        show(binding.selQueue, selecting)
        show(binding.selFavAdd, selecting && !inFavourites)
        show(binding.selPlaylistAdd, selecting)
        show(binding.selFavRemove, selecting && inFavourites)
        show(binding.selPlaylistRemove, selecting && inPlaylist)
        show(binding.selClose, selecting)
        show(binding.playMenu, play)
        if (play) {
            binding.playMenu.imageTintList = android.content.res.ColorStateList.valueOf(Session.accent)
        }

        binding.header.visibility = if (selecting || canGoBack || play) View.VISIBLE else View.GONE
        updateBack()
    }

    /**
     * What circle-play plays here, as the player's lists offer it: all the
     * tracks, all the records, one record, an artist, the favourites, a
     * playlist. Null where the player's list has no such button.
     */
    private fun playTarget(): Pair<String, String>? {
        val category = opened
        return when (section) {
            Section.TRACKS -> if (category == null) "all" to "" else null
            Section.ALBUMS -> if (category == null) "albums" to "" else "album" to category.filter
            Section.ARTISTS -> category?.let { "artist" to it.filter }
            Section.ALBUM_ARTISTS -> category?.let { "album_artist" to it.filter }
            Section.FAVOURITES -> "favourites" to ""
            Section.PLAYLISTS -> category?.let { "playlist" to it.filter.removePrefix("M3U_") }
            Section.FOLDERS -> null
        }
    }

    /** The card the player opens from circle-play: the same three rows. */
    private fun showPlayMenu() {
        val (list, value) = playTarget() ?: return
        val main = activity as? MainActivity ?: return
        val choices = arrayOf(
            getString(R.string.play_random),
            getString(R.string.play_sequence),
            getString(R.string.play_random_track),
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> main.perform { it.playAll(list, value, "shuffle") }
                    1 -> main.perform { it.playAll(list, value, "sequence") }
                    else -> main.perform(done = getString(R.string.done_queue)) {
                        it.playAll(list, value, "random")
                    }
                }
            }
            .show()
    }

    /**
     * What a row can be in a selection: a track, or a record, an artist or a
     * playlist -- which stand for their tracks. Not a folder of the card,
     * which would mean listing everything under it.
     */
    private fun selectable(row: Row): Boolean = when {
        row.kind != Row.Kind.ROW -> false
        row.isTrack -> true
        section == Section.FOLDERS -> false
        else -> row.filter.isNotEmpty()
    }

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

    private enum class Action { QUEUE, FAVOURITES_ADD, FAVOURITES_REMOVE, PLAYLIST_ADD, PLAYLIST_REMOVE }

    /**
     * The chosen rows as tracks, in the list's order and each once: a record
     * or an artist stands for its tracks, as the player's selection does.
     */
    private fun gatherPaths(positions: List<Int>): List<String> {
        val library = Session.library
        val out = LinkedHashSet<String>()
        for (at in positions) {
            val row = adapter.rowAt(at) ?: continue
            if (row.isTrack) {
                out.add(row.path)
                continue
            }
            if (library == null || row.filter.isEmpty()) continue
            val tracks = when (section) {
                Section.ALBUMS -> library.tracksOfAlbum(row.filter)
                Section.ARTISTS -> library.tracksOfArtist(row.filter)
                Section.ALBUM_ARTISTS -> library.tracksOfAlbumArtist(row.filter)
                Section.PLAYLISTS -> library.tracksOfPlaylist(row.filter)
                else -> emptyList()
            }
            tracks.forEach { if (it.path.isNotEmpty()) out.add(it.path) }
        }
        return out.toList()
    }

    private fun actOnSelection(action: Action) {
        val main = activity as? MainActivity ?: return
        val positions = adapter.selected
        if (positions.isEmpty()) return
        val playlistName = opened?.filter?.removePrefix("M3U_").orEmpty()

        viewLifecycleOwner.lifecycleScope.launch {
            val paths = withContext(Dispatchers.IO) { gatherPaths(positions) }
            if (paths.isEmpty()) return@launch
            when (action) {
                Action.QUEUE -> {
                    stopSelection()
                    main.perform(done = getString(R.string.done_queue)) { it.selection("queue_next", paths) }
                }
                Action.FAVOURITES_ADD -> {
                    stopSelection()
                    // The player stars them asynchronously: the Favourites tab
                    // reloads after a delay.
                    main.perform(done = getString(R.string.done_fav), then = { main.favouritesChanged(1500) }) {
                        it.selection("favourites_add", paths)
                    }
                }
                Action.FAVOURITES_REMOVE -> {
                    stopSelection()
                    main.perform(done = getString(R.string.done_removed), then = { main.favouritesChanged() }) {
                        it.selection("favourites_remove", paths)
                    }
                }
                Action.PLAYLIST_REMOVE -> {
                    if (playlistName.isEmpty()) return@launch
                    main.perform(done = getString(R.string.done_removed), then = { adapter.removeAt(positions); updateHeader() }) {
                        it.selection("playlist_remove", paths, playlistName)
                    }
                }
                Action.PLAYLIST_ADD -> pickPlaylist { name ->
                    stopSelection()
                    main.perform(done = getString(R.string.done_playlist)) {
                        it.selection("playlist_add", paths, name)
                    }
                }
            }
        }
    }

    /**
     * Which playlist: the player's, as the index has them, or a new one by
     * name -- the player makes it when it is not there.
     */
    private fun pickPlaylist(chosen: (String) -> Unit) {
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) {
                Session.library?.playlists().orEmpty().map { it.title }
            }
            val items = (listOf(getString(R.string.playlist_new)) + names).toTypedArray()
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.sel_playlist_add)
                .setItems(items) { _, which ->
                    if (which == 0) askPlaylistName(chosen) else chosen(names[which - 1])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun askPlaylistName(chosen: (String) -> Unit) {
        val context = context ?: return
        val field = android.widget.EditText(context).apply {
            setHint(R.string.playlist_name_hint)
            setSingleLine(true)
        }
        val box = android.widget.FrameLayout(context).apply {
            val side = (20 * resources.displayMetrics.density).toInt()
            setPadding(side, side / 2, side, 0)
            addView(field)
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
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

    // -----------------------------------------------------------------------
    // The Folders tab
    // -----------------------------------------------------------------------

    /**
     * Asks the player for one folder and shows it: its folders first, then what
     * can be played, in the order the player's own browser lists them. A track
     * the index knows gets its artwork from the thumbnails; the name on the row
     * is the file's, as on the player.
     */
    private fun loadFolder(path: String, scroll: Pair<Int, Int>?) {
        val binding = views ?: return
        val client = Session.client ?: return
        binding.progress.visibility = View.VISIBLE
        binding.emptyText.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val folder = client.browse(path)
                    val files = folder.entries.filter { !it.isFolder }.map { it.path }
                    val known = Session.library?.tracksByPaths(files).orEmpty()
                    folder to folder.entries.map { entry ->
                        if (entry.isFolder) {
                            Row(title = entry.name, subtitle = "", filter = entry.path, iconRes = R.drawable.ic_folder)
                        } else {
                            val indexed = known[entry.path]
                            Row(
                                title = entry.name,
                                subtitle = "",
                                path = entry.path,
                                album = indexed?.album.orEmpty(),
                                artPath = indexed?.artPath.orEmpty(),
                                artMtime = indexed?.artMtime ?: 0,
                                artSize = indexed?.artSize ?: 0,
                                iconRes = R.drawable.ic_track,
                            )
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            val current = views ?: return@launch
            current.progress.visibility = View.GONE
            if (result == null) {
                current.emptyText.visibility = View.VISIBLE
                return@launch
            }
            val (folder, rows) = result
            folderPath = folder.path
            folderParent = folder.parent
            updateBack()

            adapter.leading = RowAdapter.Leading.ICON
            adapter.submit(rows)
            current.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            val manager = current.list.layoutManager as? LinearLayoutManager
            if (scroll != null) {
                manager?.scrollToPositionWithOffset(scroll.first, scroll.second)
            } else {
                current.list.scrollToPosition(0)
            }
            current.letterStrip.visibility = View.GONE
            updateHeader()
        }
    }

    private fun rowsFor(library: Library, category: Row?): List<Row> = when (section) {
        Section.FOLDERS -> emptyList()
        Section.TRACKS -> library.tracks()
        Section.FAVOURITES -> favourites(library)
        Section.ALBUMS ->
            if (category == null) library.albums() else library.tracksOfAlbum(category.filter)
        Section.ARTISTS ->
            if (category == null) library.artists() else library.tracksOfArtist(category.filter)
        Section.ALBUM_ARTISTS ->
            if (category == null) library.albumArtists() else library.tracksOfAlbumArtist(category.filter)
        Section.PLAYLISTS ->
            if (category == null) library.playlists() else library.tracksOfPlaylist(category.filter)
    }

    /**
     * Favourites come from the player, so recent stars are included; when it
     * does not answer, the downloaded index's list is used.
     *
     * The player's answer carries path, name and artist; album and the artwork
     * fields the thumbnail key needs are filled in from the index.
     */
    private fun favourites(library: Library): List<Row> {
        val fresh = try {
            Session.client?.favourites()
        } catch (e: Exception) {
            null
        }
        if (fresh == null) {
            return library.favourites()
        }
        val known = library.tracksByPaths(fresh.map { it.path })
        // The player sends them newest first; its own list shows oldest first
        // unless reversed.
        val ordered = if (Session.sort.favouritesReversed) fresh else fresh.asReversed()
        return ordered.map { row ->
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
