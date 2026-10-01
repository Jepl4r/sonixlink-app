package com.mattiadoronzo.sonixlink

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mattiadoronzo.sonixlink.databinding.ActivityQueueBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player's whole queue, in its order, with the playing row highlighted.
 *
 * The player sends only paths, a page at a time. Rows are laid out for the full
 * length at once and filled as the pages near the visible ones arrive; titles,
 * artists and artwork come from the app's local index.
 *
 * The queue revision, read with the state, changes whenever the player's queue
 * does (a new list, shuffle, a track added); the pages held are then dropped
 * and fetched again.
 */
class QueueActivity : AppCompatActivity() {

    private companion object {
        /** Rows per page; matches the player's SONIXLINK_QUEUE_WINDOW. */
        const val PAGE = 200

        /** How often the state is read while this page is open. */
        const val REFRESH_MS = 1000L

        /** Rows beyond the visible ones fetched ahead of the scroll. */
        const val AHEAD = 40
    }

    private lateinit var views: ActivityQueueBinding
    private lateinit var adapter: RowAdapter

    /** The queue the rows belong to: its revision and its length. */
    private var revision = Long.MIN_VALUE
    private var count = -1

    /** Where playback sits in the queue. */
    private var position = -1

    /** The pages held for [revision], by the index of their first row. */
    private val loaded = HashSet<Int>()

    /**
     * One page in flight at a time: the player keeps a single queue window,
     * and requests for different pages would move it back and forth.
     */
    private var fetching = false

    /** Whether the list has been scrolled to the playing row since it was laid out. */
    private var placed = false

    /** Repaints the bound rows, whose playing row carries the accent. */
    private val accentWatch = {
        if (::adapter.isInitialized) adapter.repaint()
        if (::volume.isInitialized) volume.accent()
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityQueueBinding.inflate(layoutInflater)
        setContentView(views.root)

        volume = VolumePill(views.volume) { level -> sendVolume(level) }

        setSupportActionBar(views.toolbar)
        supportActionBar?.title = getString(R.string.queue_title)
        views.toolbar.setNavigationOnClickListener { finish() }

        adapter = RowAdapter(R.drawable.ic_track) { at, _ -> jumpTo(at) }
        views.list.layoutManager = LinearLayoutManager(this)
        views.list.adapter = adapter
        views.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                fetchVisible()
            }
        })

        // Polls while visible: the queue and position change from elsewhere too.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refresh()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    /**
     * Reads the state: the queue's length, revision and position. A different
     * queue lays the rows out afresh; the same one only moves the highlight.
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

        if (state.queueRevision != revision || state.queueCount != count) {
            layOut(state.queueCount, state.queueRevision)
        }
        position = state.queuePosition
        adapter.highlightIndex = position

        supportActionBar?.subtitle = if (position >= 0) {
            getString(R.string.queue_position, position + 1, count)
        } else {
            getString(R.string.tracks_count, count)
        }

        // On first open and after a new queue, scrolls to the playing row.
        if (!placed && count > 0) {
            placed = true
            if (position in 0 until count) {
                views.list.scrollToPositionWithOffset(position)
            }
        }
        fetchVisible()
    }

    /** A new queue: every row a placeholder until its page arrives. */
    private fun layOut(total: Int, newRevision: Long) {
        revision = newRevision
        count = total.coerceAtLeast(0)
        loaded.clear()
        placed = false
        val placeholder = Row(title = "…", subtitle = "", kind = Row.Kind.PENDING, iconRes = R.drawable.ic_track)
        adapter.submit(List(count) { placeholder })
        views.progress.visibility = View.GONE
        views.emptyText.visibility = if (count == 0) View.VISIBLE else View.GONE
    }

    /** Asks for the next page the visible rows need, if one is missing. */
    private fun fetchVisible() {
        if (fetching || count <= 0) return
        val manager = views.list.layoutManager as? LinearLayoutManager ?: return
        var first = manager.findFirstVisibleItemPosition()
        var last = manager.findLastVisibleItemPosition()
        if (first < 0 || last < 0) {
            // Nothing laid out yet: the rows around the playing one.
            first = position.coerceAtLeast(0)
            last = first
        }
        first = (first - AHEAD).coerceAtLeast(0)
        last = (last + AHEAD).coerceAtMost(count - 1)

        val page = (first / PAGE..last / PAGE)
            .map { it * PAGE }
            .firstOrNull { it !in loaded }
            ?: return
        fetchPage(page)
    }

    private fun fetchPage(from: Int) {
        val client = Session.client ?: return
        val wanted = revision
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
            if (page == null || revision != wanted || (page.revision >= 0 && page.revision != wanted)) {
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
            if (revision != wanted) return@launch
            loaded.add(from)
            adapter.replace(page.first, rows)
            fetchVisible()
        }
    }

    /** Puts the row a third of the way down, not stuck to the top. */
    private fun RecyclerView.scrollToPositionWithOffset(at: Int) {
        val manager = layoutManager as? LinearLayoutManager ?: return
        manager.scrollToPositionWithOffset(at, height / 3)
    }

    private fun jumpTo(index: Int) {
        val client = Session.client ?: return
        // The highlight moves at once; the next state read confirms it.
        adapter.highlightIndex = index
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
