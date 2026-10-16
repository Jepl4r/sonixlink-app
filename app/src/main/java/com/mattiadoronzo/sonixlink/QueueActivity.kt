package com.mattiadoronzo.sonixlink

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.mattiadoronzo.sonixlink.databinding.ActivityQueueBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player's queue page, on the phone: what has been played above, under
 * History and a heading per day; Now playing, the track playing, and what is
 * to play after it below.
 *
 * The page opens with Now playing at the top. Pulled down from there the list
 * holds back, moving two fifths of the finger, until it has been pulled
 * [TENSION_DP] down; let go before that, it settles back on Now playing.
 *
 * As on the player, only what comes after the track playing can be changed:
 * the select button picks rows to take out with the bin, the reorder button
 * shows a grip to drag a row elsewhere, and a long press on an entry of the
 * history drags a copy of it into what is to play. A tap on an entry of the
 * history plays it right after the track playing.
 *
 * The player sends the queue as paths, a page at a time, and the history whole
 * (its latest [HISTORY_LIMIT] entries); titles, artists and artwork come from
 * the app's local index where it has the track. Every change is made on the
 * player and read back from it: the queue revision and the history revision,
 * read with the state, say when the copy here is stale.
 */
class QueueActivity : AppCompatActivity() {

    private companion object {
        /** How often the state is read while this page is open. */
        const val REFRESH_MS = 1000L

        /** Rows beyond the visible ones fetched ahead of the scroll. */
        const val AHEAD = 40

        /** The entries of the history asked for. */
        const val HISTORY_LIMIT = 200

        /** How far the history can be pulled into view before it gives way. */
        const val TENSION_DP = 56

        /** The share of the finger the list follows while held back. */
        const val TENSION_NUM = 2
        const val TENSION_DEN = 5

        /** Room left under the last row, so Now playing can always reach the top. */
        const val ROOM_KEPT_DP = 96
    }

    private enum class Mode { IDLE, SELECT, REORDER }

    private lateinit var views: ActivityQueueBinding
    private lateinit var adapter: RowAdapter
    private lateinit var manager: QueueLayoutManager
    private lateinit var touch: ItemTouchHelper

    /** The queue as last read: its revision, its length and where playback sits. */
    private var revision = Long.MIN_VALUE
    private var count = -1
    private var position = -1

    /**
     * The same three for the rows on screen, which are laid out again only
     * between drags: every index worked out from a row, and every edit sent,
     * goes by these.
     */
    private var laidRevision = Long.MIN_VALUE
    private var laidCount = -1
    private var laidPosition = -1

    /**
     * The rows of the queue that have arrived, by queue index, for the queue
     * revision they were read in: a track change lays the list out again
     * without sending them for a second time.
     */
    private val queueRows = HashMap<Int, Row>()
    private var queueRowsRevision = Long.MIN_VALUE

    /**
     * One page in flight at a time: the player keeps a single queue window,
     * and requests for different pages would move it back and forth.
     */
    private var fetching = false

    /** The history as last read, and its rows, one for one. */
    private var historyRevision = Long.MIN_VALUE
    private var history: List<HistoryEntry> = emptyList()
    private var historyRows: List<Row> = emptyList()

    /** The entry of the history at each list position that shows one. */
    private val historyAt = HashMap<Int, HistoryEntry>()

    /** The list position of the Now playing heading, -1 with no queue. */
    private var nowAt = -1

    /** The track playing, right under its heading. */
    private val currentAt: Int get() = if (nowAt < 0) -1 else nowAt + 1

    private var mode = Mode.IDLE

    /** A row is being carried: the list is not laid out again under it. */
    private var dragging = false
    private var dragFrom = -1
    private var dragAt = -1
    private var dragFromHistory = false
    private var relayoutAfterDrag = false

    /** Whether the list has been put on Now playing since it was first laid out. */
    private var placed = false

    /** Repaints the bound rows, whose playing row carries the accent. */
    private val accentWatch = {
        if (::adapter.isInitialized) adapter.repaint()
        if (::volume.isInitialized) volume.accent()
        paintBar()
    }

    private lateinit var volume: VolumePill

    /** The phone's volume keys set the player's volume. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        volume.onKey(event) || super.dispatchKeyEvent(event)

    override fun onDestroy() {
        super.onDestroy()
        volume.release()
    }

    override fun onStart() {
        super.onStart()
        Session.watchAccent(accentWatch)
        accentWatch()
    }

    override fun onStop() {
        super.onStop()
        Session.unwatchAccent(accentWatch)
        // Leaving the page closes every mode, as on the player.
        setMode(Mode.IDLE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityQueueBinding.inflate(layoutInflater)
        setContentView(views.root)

        volume = VolumePill(views.volume) { level -> sendVolume(level) }

        setSupportActionBar(views.toolbar)
        supportActionBar?.title = getString(R.string.queue_title)
        views.toolbar.setNavigationOnClickListener {
            if (mode != Mode.IDLE) setMode(Mode.IDLE) else finish()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode != Mode.IDLE) {
                    setMode(Mode.IDLE)
                } else {
                    finish()
                }
            }
        })

        views.queueSelect.setOnClickListener { setMode(Mode.SELECT) }
        views.queueReorder.setOnClickListener { setMode(if (mode == Mode.REORDER) Mode.IDLE else Mode.REORDER) }
        views.queueRemove.setOnClickListener { removeChosen() }
        views.queueClose.setOnClickListener { setMode(Mode.IDLE) }

        adapter = RowAdapter(R.drawable.ic_track) { at, row -> onRowClicked(at, row) }
        adapter.onLongPress = { at, row -> onRowLongPressed(at, row) }
        adapter.onGripPressed = { holder -> touch.startDrag(holder) }

        manager = QueueLayoutManager()
        views.list.layoutManager = manager
        views.list.adapter = adapter
        // A row rebound while it is carried (a thumbnail arriving) would get a
        // new holder, and the drag would lose it.
        (views.list.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        touch = ItemTouchHelper(DragCallback())
        touch.attachToRecyclerView(views.list)

        views.list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        manager.held = true
                        // Held back only when the pull starts from Now playing.
                        manager.tense = pulled() == 0
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> manager.held = false
                }
                return false
            }
        })
        views.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // A fling up the queue stops on Now playing rather than running
                // on into the history.
                if (recyclerView.scrollState == RecyclerView.SCROLL_STATE_SETTLING && dy < 0 &&
                    !manager.tense && !manager.held && pulled() > 0 && history.isNotEmpty() &&
                    manager.flingFromBelow
                ) {
                    recyclerView.stopScroll()
                    settleOnNowPlaying(false)
                }
                fetchVisible()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> manager.flingFromBelow = pulled() < 0
                    RecyclerView.SCROLL_STATE_SETTLING, RecyclerView.SCROLL_STATE_IDLE -> {
                        // Let go before the pull gave way: back on Now playing.
                        val pull = pulled()
                        if (manager.tense && !manager.held && pull in 1 until tensionPx()) {
                            manager.tense = false
                            // Not from inside the fling's own start.
                            recyclerView.post {
                                recyclerView.stopScroll()
                                settleOnNowPlaying(true)
                            }
                        }
                    }
                }
            }
        })
        views.list.addOnLayoutChangeListener { list, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                val room = (bottom - top) - dp(ROOM_KEPT_DP)
                list.post { list.setPadding(0, 0, 0, room.coerceAtLeast(0)) }
            }
        }

        paintBar()

        // Polls while visible: the queue, the history and the position change
        // from elsewhere too.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Reading the player
    // -----------------------------------------------------------------------

    /**
     * Reads the state, and the history when it has changed. A different queue,
     * history or position lays the rows out afresh.
     */
    private suspend fun refresh() {
        val client = Session.client
        if (client == null) {
            finish()
            return
        }
        val state = withContext(Dispatchers.IO) {
            try {
                client.state()
            } catch (e: Exception) {
                null
            }
        }
        if (state == null) {
            if (count < 0) {
                views.progress.visibility = View.GONE
                views.emptyText.visibility = View.VISIBLE
            }
            return
        }
        Session.state = state

        var relayout = false
        if (state.historyRevision != historyRevision && loadHistory(client)) {
            historyRevision = state.historyRevision
            relayout = true
        }
        if (state.queueRevision != revision || state.queueCount != count || state.queuePosition != position) {
            revision = state.queueRevision
            count = state.queueCount.coerceAtLeast(0)
            position = state.queuePosition
            relayout = true
        }
        if (relayout) {
            if (dragging) relayoutAfterDrag = true else layOut()
        }

        supportActionBar?.subtitle = if (position in 0 until count) {
            getString(R.string.queue_position, position + 1, count)
        } else {
            getString(R.string.tracks_count, count)
        }
        fetchVisible()
    }

    /**
     * The history and its rows. A player without the route has none; false
     * when the player did not answer, to be asked again on the next read.
     */
    private suspend fun loadHistory(client: PlayerClient): Boolean {
        val entries: List<HistoryEntry> = withContext(Dispatchers.IO) {
            try {
                client.history(HISTORY_LIMIT)
            } catch (e: PlayerException) {
                // A player from before the history: "404 ...".
                if (e.message?.startsWith("404") == true) emptyList<HistoryEntry>() else null
            } catch (e: Exception) {
                null
            }
        } ?: return false
        val rows: List<Row> = withContext(Dispatchers.IO) {
            val known = Session.library?.tracksByPaths(entries.map { it.path }).orEmpty()
            entries.map { entry ->
                known[entry.path] ?: Row(
                    title = entry.title.ifEmpty { entry.path.substringAfterLast('/') },
                    subtitle = entry.artist,
                    path = entry.path,
                )
            }
        }
        history = entries
        historyRows = rows
        return true
    }

    // -----------------------------------------------------------------------
    // Laying out
    // -----------------------------------------------------------------------

    private fun header(text: String) = Row(title = text, subtitle = "", kind = Row.Kind.HEADER)

    /** The year and day of the year of a time, or null for an entry with none. */
    private fun dayKey(at: Long): Long? {
        if (at <= 0) return null
        val day = Calendar.getInstance().apply { timeInMillis = at * 1000 }
        return day.get(Calendar.YEAR) * 1000L + day.get(Calendar.DAY_OF_YEAR)
    }

    /** Today, Yesterday, or the day and the month, with the year when it is not this one. */
    private fun dayText(at: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = at * 1000 }
        val now = Calendar.getInstance()
        val before = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            same(then, now) -> getString(R.string.history_today)
            same(then, before) -> getString(R.string.history_yesterday)
            else -> {
                val skeleton = if (then.get(Calendar.YEAR) == now.get(Calendar.YEAR)) "dMMMM" else "dMMMMyyyy"
                val locale = Locale.getDefault()
                val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
                SimpleDateFormat(pattern, locale).format(then.time)
            }
        }
    }

    /** The queue index a list position shows, or -1 when it shows none. */
    private fun queueIndexAt(at: Int): Int =
        if (currentAt >= 0 && at >= currentAt && at < currentAt + (laidCount - laidPosition)) {
            laidPosition + (at - currentAt)
        } else {
            -1
        }

    /** A row after the track playing: the only ones that can change. */
    private fun isUpcoming(at: Int): Boolean = currentAt >= 0 && at > currentAt && queueIndexAt(at) >= 0

    /**
     * Every row afresh: the history with its days, Now playing, the track
     * playing and what follows it, as placeholders until their page arrives.
     * The list stays where it was: on Now playing when it rested there, on
     * the same rows otherwise.
     */
    private fun layOut() {
        val oldNowAt = nowAt
        val wasOnNowPlaying = placed && pulled() == 0
        val firstSeen = manager.findFirstVisibleItemPosition()
        val firstView = if (firstSeen >= 0) manager.findViewByPosition(firstSeen) else null
        val firstOffset = (firstView?.top ?: 0) - views.list.paddingTop

        // What was chosen, as queue indices, to be chosen again on the same
        // queue: a track change keeps the choices, a different queue drops them.
        val chosen = if (mode == Mode.SELECT) adapter.selected.map { queueIndexAt(it) }.filter { it >= 0 } else emptyList()
        val sameLength = count == laidCount && revision == laidRevision
        // The queue entry at the top of the view, to keep it there.
        val firstIndex = queueIndexAt(firstSeen)

        if (revision != queueRowsRevision) {
            queueRows.clear()
            queueRowsRevision = revision
        }
        laidRevision = revision
        laidCount = count
        laidPosition = position

        val rows = ArrayList<Row>()
        historyAt.clear()
        if (history.isNotEmpty()) {
            rows.add(header(getString(R.string.queue_history)))
            var day = Long.MIN_VALUE
            history.forEachIndexed { i, entry ->
                val key = dayKey(entry.playedAt)
                // An entry from before the clock was set has no day, and goes
                // under whatever heading is above it.
                if (key != null && key != day) {
                    day = key
                    rows.add(header(dayText(entry.playedAt)))
                }
                historyAt[rows.size] = entry
                rows.add(historyRows[i])
            }
        }
        nowAt = if (count > 0 && position in 0 until count) rows.size else -1
        if (nowAt >= 0) {
            rows.add(header(getString(R.string.queue_now_playing)))
            val placeholder = Row(title = "…", subtitle = "", kind = Row.Kind.PENDING, iconRes = R.drawable.ic_track)
            for (index in position until count) rows.add(queueRows[index] ?: placeholder)
        }

        adapter.submit(rows)
        adapter.highlightIndex = currentAt
        views.progress.visibility = View.GONE
        views.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        when {
            mode == Mode.SELECT && chosen.isNotEmpty() && sameLength -> {
                adapter.startEmptySelection()
                chosen.filter { it > laidPosition }.forEach { adapter.toggle(currentAt + (it - laidPosition)) }
            }
            mode == Mode.SELECT -> {
                if (sameLength) adapter.startEmptySelection() else setMode(Mode.IDLE)
            }
            mode == Mode.REORDER -> adapter.gripAt = { at -> mode == Mode.REORDER && isUpcoming(at) }
            else -> Unit
        }
        if (nowAt < 0 && mode != Mode.IDLE) setMode(Mode.IDLE)

        if (!placed || wasOnNowPlaying) {
            if (nowAt >= 0) {
                placed = true
                manager.scrollToPositionWithOffset(nowAt, 0)
            }
        } else if (firstSeen >= 0) {
            // The same rows in view: an entry of the queue where it now is,
            // Now playing for one that has been played since, and a row of
            // the history where it was.
            val target = when {
                firstIndex > position && nowAt >= 0 -> currentAt + (firstIndex - position)
                firstIndex >= 0 || (oldNowAt >= 0 && firstSeen >= oldNowAt) -> nowAt.coerceAtLeast(0)
                else -> firstSeen
            }
            manager.scrollToPositionWithOffset(target.coerceIn(0, (rows.size - 1).coerceAtLeast(0)), firstOffset)
        }
        paintBar()
        fetchVisible()
    }

    /** Asks for the next page the visible rows need, if one is missing. */
    private fun fetchVisible() {
        if (fetching || dragging || nowAt < 0 || laidCount <= 0) return
        var first = manager.findFirstVisibleItemPosition()
        var last = manager.findLastVisibleItemPosition()
        if (first < 0 || last < 0) {
            first = currentAt
            last = currentAt
        }
        val from = queueIndexAt((first - AHEAD).coerceAtLeast(currentAt)).coerceAtLeast(laidPosition)
        val to = queueIndexAt((last + AHEAD).coerceAtMost(currentAt + laidCount - laidPosition - 1))
        if (to < 0) return
        val missing = (from..to).firstOrNull { it !in queueRows } ?: return
        fetchPage(missing)
    }

    private fun fetchPage(from: Int) {
        val client = Session.client ?: return
        val wantedRevision = laidRevision
        fetching = true
        lifecycleScope.launch {
            val page = withContext(Dispatchers.IO) {
                try {
                    client.queuePage(from)
                } catch (e: Exception) {
                    null
                }
            }
            fetching = false
            // Drops a page from a queue that has since changed; the next state
            // read lays the new one out.
            if (page == null || page.paths.isEmpty() || laidRevision != wantedRevision ||
                (page.revision >= 0 && page.revision != wantedRevision)
            ) {
                return@launch
            }
            val rows = withContext(Dispatchers.IO) {
                val known = Session.library?.tracksByPaths(page.paths).orEmpty()
                page.paths.map { path ->
                    known[path] ?: Row(
                        title = path.substringAfterLast('/'),
                        subtitle = "",
                        path = path,
                    )
                }
            }
            if (laidRevision != wantedRevision) return@launch
            rows.forEachIndexed { i, row -> queueRows[page.first + i] = row }
            // Kept for the next layout either way; not put in under a drag.
            if (dragging) return@launch
            // Only what is from the track playing on is shown.
            val skip = (laidPosition - page.first).coerceAtLeast(0)
            if (skip < rows.size) {
                adapter.replace(currentAt + (page.first + skip - laidPosition), rows.drop(skip))
            }
            fetchVisible()
        }
    }

    // -----------------------------------------------------------------------
    // Holding back the history
    // -----------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun tensionPx(): Int = dp(TENSION_DP)

    /**
     * How far Now playing sits below the top of the list: 0 resting on it, more
     * with the history pulled into view, -1 when it has gone up past the top or
     * there is none.
     */
    private fun pulled(): Int {
        if (nowAt < 0) return -1
        val view = manager.findViewByPosition(nowAt)
        if (view == null) {
            val first = manager.findFirstVisibleItemPosition()
            return if (first < 0 || first > nowAt) -1 else Int.MAX_VALUE
        }
        return view.top - views.list.paddingTop
    }

    private fun settleOnNowPlaying(animate: Boolean) {
        if (nowAt < 0) return
        if (!animate) {
            manager.scrollToPositionWithOffset(nowAt, 0)
            return
        }
        val scroller = object : LinearSmoothScroller(this) {
            override fun getVerticalSnapPreference(): Int = SNAP_TO_START
        }
        scroller.targetPosition = nowAt
        manager.startSmoothScroll(scroller)
    }

    /**
     * The list, held back while the history is pulled down from Now playing:
     * it moves two fifths of the finger until the pull has given way.
     */
    private inner class QueueLayoutManager : LinearLayoutManager(this@QueueActivity) {
        var held = false
        var tense = false
        var flingFromBelow = false

        override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler?, state: RecyclerView.State?): Int {
            if (dy < 0 && held && tense && history.isNotEmpty()) {
                val pull = pulled()
                if (pull in 0 until tensionPx()) {
                    var eased = dy * TENSION_NUM / TENSION_DEN
                    if (eased == 0) eased = -1
                    super.scrollVerticallyBy(eased, recycler, state)
                    // The whole of the finger counts as used, or the list
                    // would hand the rest on as an overscroll.
                    return dy
                }
            }
            return super.scrollVerticallyBy(dy, recycler, state)
        }
    }

    // -----------------------------------------------------------------------
    // Modes and rows
    // -----------------------------------------------------------------------

    private fun setMode(next: Mode) {
        if (next == mode) {
            paintBar()
            return
        }
        if (mode == Mode.SELECT) adapter.stopSelection()
        mode = next
        when (next) {
            Mode.SELECT -> {
                adapter.gripAt = null
                adapter.startEmptySelection()
            }
            Mode.REORDER -> adapter.gripAt = { at -> mode == Mode.REORDER && isUpcoming(at) }
            Mode.IDLE -> adapter.gripAt = null
        }
        paintBar()
    }

    /** The toolbar as the mode says: title, and which corner buttons show. */
    private fun paintBar() {
        if (!::views.isInitialized) return
        val any = laidCount > 0 && nowAt >= 0
        views.queueSelect.visibility = if (mode == Mode.IDLE && any) View.VISIBLE else View.GONE
        views.queueReorder.visibility = if (mode != Mode.SELECT && any) View.VISIBLE else View.GONE
        views.queueRemove.visibility = if (mode == Mode.SELECT) View.VISIBLE else View.GONE
        views.queueClose.visibility = if (mode == Mode.SELECT) View.VISIBLE else View.GONE

        val chosen = if (mode == Mode.SELECT) adapter.selected.size else 0
        views.queueRemove.isEnabled = chosen > 0
        views.queueRemove.alpha = if (chosen > 0) 1f else 0.4f
        views.queueReorder.imageTintList = android.content.res.ColorStateList.valueOf(
            if (mode == Mode.REORDER) {
                Session.accent
            } else {
                androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
            }
        )
        supportActionBar?.title = if (mode == Mode.SELECT) {
            getString(R.string.selected_count, chosen)
        } else {
            getString(R.string.queue_title)
        }
    }

    private fun onRowClicked(at: Int, row: Row) {
        if (row.kind == Row.Kind.HEADER || dragging) return
        val entry = historyAt[at]
        when (mode) {
            Mode.SELECT -> {
                if (isUpcoming(at)) {
                    adapter.toggle(at)
                    paintBar()
                }
            }
            Mode.REORDER -> Unit // the rows are being arranged, not played
            Mode.IDLE -> if (entry != null) {
                act { it.historyPlay(entry.id) }
            } else {
                val index = queueIndexAt(at)
                if (index >= 0) jumpTo(index)
            }
        }
    }

    /**
     * A long press on an entry of the history picks it up, to be put down in
     * what is to play. On any other row it is a slow tap.
     */
    private fun onRowLongPressed(at: Int, row: Row) {
        if (mode == Mode.IDLE && at in historyAt && nowAt >= 0) {
            views.list.findViewHolderForAdapterPosition(at)?.let { touch.startDrag(it) }
            return
        }
        onRowClicked(at, row)
    }

    private fun removeChosen() {
        val indices = adapter.selected.map { queueIndexAt(it) }.filter { it > laidPosition }
        if (indices.isEmpty()) return
        val seen = laidRevision
        setMode(Mode.IDLE)
        act(getString(R.string.done_queue_removed)) { it.queueRemove(indices, seen) }
    }

    /** What a drop means, worked out once the row is let go of. */
    private fun dropped() {
        val from = dragFrom
        val at = dragAt
        val wasHistory = dragFromHistory
        val stale = relayoutAfterDrag
        dragging = false
        dragFrom = -1
        dragAt = -1
        relayoutAfterDrag = false
        // The finger that carried the row is up, but the list never heard it.
        manager.held = false
        val seen = laidRevision

        if (wasHistory) {
            // The entry left its place above the track playing, which is then
            // one row higher, and counts only when put down after it.
            val entry = historyAt[from]
            val current = currentAt - 1
            if (!stale && entry != null && from != at && at > current) {
                val slot = laidPosition + (at - current)
                act(relayout = true) { it.historyInsert(entry.id, slot, seen) }
            }
            // The entry stays in the history: back where it was, until the
            // player's answer lays the list out again.
            if (stale) layOut() else adapter.move(at, from)
            return
        }
        if (stale) {
            // The queue changed under the drag: the move was worked out against
            // rows that are no longer there.
            layOut()
            return
        }
        val fromIndex = laidPosition + (from - currentAt)
        val toIndex = laidPosition + (at - currentAt)
        if (from >= 0 && at >= 0 && fromIndex != toIndex) {
            act(relayout = true) { it.queueMove(fromIndex, toIndex, seen) }
        }
    }

    /**
     * Carrying a row: one of what is to play while rearranging, or an entry of
     * the history anywhere, judged when it is let go of.
     */
    private inner class DragCallback : ItemTouchHelper.Callback() {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val at = viewHolder.bindingAdapterPosition
            // Asked again while the row is carried, from where it now is.
            val movable = dragging || (mode == Mode.REORDER && isUpcoming(at)) ||
                (mode == Mode.IDLE && at in historyAt && nowAt >= 0)
            return if (movable) makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) else 0
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            // Rearranging stays below the track playing.
            if (!dragFromHistory && to <= currentAt) return false
            adapter.move(from, to)
            dragAt = to
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                dragging = true
                dragFrom = viewHolder.bindingAdapterPosition
                dragAt = dragFrom
                dragFromHistory = dragFrom in historyAt
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            // After the settle, and not from inside a scroll or a layout, which
            // is where clearView can be called from.
            recyclerView.post { if (dragging) dropped() }
        }
    }

    // -----------------------------------------------------------------------
    // Asking the player
    // -----------------------------------------------------------------------

    private fun jumpTo(index: Int) {
        val client = Session.client ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    client.playQueueIndex(index)
                } catch (e: Exception) {
                    // The next state read shows the outcome.
                }
            }
            refresh()
        }
    }

    /**
     * Sends an edit and reads the page back. `done`, when given, is said once
     * the player has taken it. With `relayout` the rows are laid out afresh
     * from what the player says, changed or not: the player drops an edit for
     * a queue that has moved on, and the rows rearranged here must not stay.
     */
    private fun act(done: String? = null, relayout: Boolean = false, action: (PlayerClient) -> Unit) {
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
            if (message != null) {
                Snackbar.make(views.root, message, Snackbar.LENGTH_LONG).show()
            }
            if (!relayout) {
                refresh()
                return@launch
            }
            // The player applies an edit on its next turn, after answering.
            // Still the same queue a moment later: it dropped the edit, and the
            // rows rearranged here go back as the player has them.
            val before = laidRevision
            delay(REFRESH_MS)
            refresh()
            if (revision == before) {
                revision = Long.MIN_VALUE
                refresh()
            }
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
                    // The next poll shows the real level.
                }
            }
        }
    }
}
