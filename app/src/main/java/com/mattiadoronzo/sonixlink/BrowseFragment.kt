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
 * The same class for all six: only the query changes. Categories open inside the
 * tab, without a change of screen, so the back button returns to the list of
 * categories instead of closing the app.
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
     * Where the list of categories was when one was opened: going back puts it
     * there again, not at the top. Two numbers and not one because the row alone
     * is not enough -- without the offset the list jumps half a row.
     */
    private var savedRow = 0
    private var savedOffset = 0

    /** The big letter's disc, and which accent the drawn one is. */
    private val hint = android.os.Handler(android.os.Looper.getMainLooper())
    private val hideHint = Runnable { views?.indexHint?.visibility = View.GONE }
    private var hintAccent = 0

    /**
     * The accent has changed on the player: the playing track's title carries it,
     * and rows already bound would not know. Kept in a field because the object
     * itself is what removes itself from the list.
     */
    private val accentWatch = {
        hintAccent = 0
        if (::adapter.isInitialized) {
            adapter.repaint()
        }
    }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            opened = null
            isEnabled = false
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
        // New view, disc to redraw: the old one went with the old view.
        hintAccent = 0

        adapter = RowAdapter(section.icon, leadingFor(opened)) { _, row -> onRowClicked(row) }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter
        binding.headerBack.setOnClickListener { back.handleOnBackPressed() }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
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

    // With the neighbouring tabs already built, back would go to whichever
    // registered last rather than the one being looked at: the callback counts
    // only while this tab is in front.
    override fun onResume() {
        super.onResume()
        back.isEnabled = opened != null
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

    // -----------------------------------------------------------------------

    override fun onFavouritesChanged() {
        if (section == Section.FAVOURITES && opened == null) {
            load()
        }
    }

    private fun onRowClicked(row: Row) {
        if (row.isTrack) {
            // The queue becomes the list being looked at, not the whole
            // library: the player builds it, but has to be told which.
            val category = opened
            val (list, value) = when {
                section == Section.FAVOURITES -> "favourites" to ""
                category == null -> "all" to ""
                section == Section.ALBUMS -> "album" to category.filter
                section == Section.ARTISTS -> "artist" to category.filter
                section == Section.ALBUM_ARTISTS -> "album_artist" to category.filter
                // The player knows a playlist by name, not by the name of the
                // table it put it in.
                section == Section.PLAYLISTS -> "playlist" to category.filter.removePrefix("M3U_")
                else -> "all" to ""
            }
            (activity as? MainActivity)?.play(row.path, list, value)
            return
        }
        rememberPosition()
        opened = row
        back.isEnabled = true
        load()
    }

    private fun rememberPosition() {
        val manager = views?.list?.layoutManager as? LinearLayoutManager ?: return
        savedRow = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        savedOffset = manager.findViewByPosition(savedRow)?.top ?: 0
    }

    // -----------------------------------------------------------------------

    private fun load(restore: Boolean = false) {
        val binding = views ?: return
        val library = Session.library
        val category = opened

        binding.header.visibility = if (category == null) View.GONE else View.VISIBLE
        binding.headerTitle.text = category?.title.orEmpty()
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
        }
    }

    // -----------------------------------------------------------------------
    // The letter strip
    // -----------------------------------------------------------------------

    /**
     * The letters the list actually has, as on the player's strip. Only where it
     * earns its place: inside an album the tracks are in disc order, and an A-Z
     * over twelve rows helps nobody.
     */
    private fun buildLetterStrip() {
        val binding = views ?: return
        val strip = binding.letterStrip
        strip.removeAllViews()

        val wanted = opened == null &&
            (section == Section.TRACKS || section == Section.ALBUMS ||
                section == Section.ARTISTS || section == Section.ALBUM_ARTISTS)
        val letters = if (wanted) adapter.letters() else emptyList()
        if (letters.size < 4) {
            strip.visibility = View.GONE
            binding.indexHint.visibility = View.GONE
            hint.removeCallbacks(hideHint)
            binding.list.setPadding(0, 0, 0, 0)
            return
        }
        // The strip floats over the list: without this it covers the chevron at
        // the end of every row and eats the taps in that column.
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

        // The finger slides down the strip and the list follows, as on the
        // player: no need to hit the letter, dragging is enough.
        strip.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    // No searching in here: every letter's position was worked
                    // out when the list arrived.
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
     * The letter under the finger, drawn large in the middle of the screen like
     * the player's `index_hint`: the same accent disc, and the same moment more
     * after the finger has gone (INDEX_HINT_MS), because vanishing on the same
     * instant as the touch reads as a flicker.
     */
    private fun showHint(letter: Char) {
        val binding = views ?: return
        // The disc is rebuilt only when the accent has changed: this runs on
        // every movement of the finger, and rebuilding each time would be one
        // new object per pixel travelled.
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
     * The square on the left. Lists of names -- artists, album artists,
     * playlists -- have no use for one; inside one of those categories there are
     * tracks, though, and there the square keeps the titles aligned even where
     * the artwork is missing.
     */
    private fun leadingFor(category: Row?): RowAdapter.Leading = when {
        category != null -> RowAdapter.Leading.ICON
        section == Section.ARTISTS || section == Section.ALBUM_ARTISTS || section == Section.PLAYLISTS ->
            RowAdapter.Leading.NONE
        else -> RowAdapter.Leading.ICON
    }

    private fun rowsFor(library: Library, category: Row?): List<Row> = when (section) {
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
     * Favourites are asked of the player, not of the downloaded index: a star set
     * a minute ago has to be there. If the player does not answer, the index's
     * own remain -- old, but real.
     *
     * The player's answer carries path, name and artist; the file's mtime and
     * size, which the thumbnail key needs, are put back here from the index.
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
        return fresh.map { row ->
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
