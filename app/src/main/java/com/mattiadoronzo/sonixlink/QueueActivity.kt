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
 * The player's queue.
 *
 * The player sends paths and nothing else -- a window around the playing track,
 * not the whole queue, which can be the entire library. Titles, artists and
 * artwork come out of the index the app already holds, so what crosses the
 * network is a few tens of kilobytes of text.
 */
class QueueActivity : AppCompatActivity() {

    private lateinit var views: ActivityQueueBinding
    private lateinit var adapter: RowAdapter

    /** Where the first shown row sits in the queue, for jumping. */
    private var firstIndex = 0

    /** How often the queue is asked for while this page is open. */
    private val refreshMs = 1500L

    /** The queue as shown, so nothing is redrawn while it has not changed. */
    private var shownSignature = ""

    /**
     * One round at a time. The periodic poll and a jump start from two different
     * coroutines: without this the older answer could land last and put the
     * previous window back, undoing the jump.
     */
    private var loading = false

    /**
     * The playing track carries the accent here too: when it changes on the
     * player while this page is open, bound rows have to be repainted.
     */
    private val accentWatch = {
        if (::adapter.isInitialized) adapter.repaint()
        if (::volume.isInitialized) volume.accent()
    }

    /** The volume pill, the same one every other page has. */
    private lateinit var volume: VolumePill

    /**
     * The phone's keys drive the player from here as well: the queue is where
     * what plays gets chosen, and not being able to turn it up in the same place
     * is a door shut in the middle of the room.
     */
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
        // One read: nothing polls state here, and the first key press would
        // start from the volume as it was when this page opened.
        refreshVolume()
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

        adapter = RowAdapter(R.drawable.ic_track) { at, row -> jumpTo(at, row) }
        views.list.layoutManager = LinearLayoutManager(this)
        views.list.adapter = adapter

        // The queue moves underfoot: a track started from another screen, the
        // player advancing on its own, a jump in here.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    // A real wait: without it, a slow network would have three
                    // requests in flight at once and the oldest could land last,
                    // rewriting the list with stale rows.
                    load()
                    delay(refreshMs)
                }
            }
        }
    }

    /**
     * Asks for the queue and redraws it if it has changed. `force` redraws it
     * either way, without putting the spinner back over a list that is already
     * there or sending the scroll to the top.
     */
    private suspend fun load(force: Boolean = false) {
        val client = Session.client
        if (client == null) {
            finish()
            return
        }
        if (loading && !force) {
            return
        }
        loading = true
        try {
            loadNow(client, force)
        } finally {
            loading = false
        }
    }

    private suspend fun loadNow(client: PlayerClient, force: Boolean) {
        val firstTime = shownSignature.isEmpty()
        if (firstTime) {
            views.progress.visibility = View.VISIBLE
        }
        val previousFirst = firstIndex

        val window = withContext(Dispatchers.IO) {
            try {
                client.queue()
            } catch (e: Exception) {
                null
            }
        }
        views.progress.visibility = View.GONE
        if (window == null) {
            if (firstTime) {
                views.emptyText.visibility = View.VISIBLE
            }
            return
        }

        // The window itself says what is playing, not the other screen's state:
        // that one is stopped while this is in front.
        val playingAt = window.position - window.first
        val playing = window.paths.getOrNull(playingAt).orEmpty()
        // The window is centred on the position: as it slides, the rows under
        // the eye become other tracks unless the view returns to the one playing.
        val movedWindow = !firstTime && window.first != previousFirst

        // Nothing to redraw while the queue and the position are the same:
        // redrawing would throw the scroll away every round.
        val signature = "${window.count}|${window.position}|${window.first}|${window.paths.size}"
        if (!force && signature == shownSignature) {
            adapter.highlightPath = playing
            return
        }
        val rows = withContext(Dispatchers.IO) {
            val known = Session.library?.tracksByPaths(window.paths).orEmpty()
            window.paths.map { path ->
                known[path] ?: Row(
                    title = path.substringAfterLast('/'),
                    subtitle = "",
                    path = path,
                )
            }
        }

        // The rows and where they start move together: there was a suspension
        // between the two, and a tap landing in the gap worked the position out
        // from the old window with the new window's numbers.
        shownSignature = signature
        firstIndex = window.first
        adapter.highlightPath = playing
        adapter.submit(rows)
        views.emptyText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE

        supportActionBar?.subtitle = if (window.position >= 0) {
            getString(R.string.queue_position, window.position + 1, window.count)
        } else {
            getString(R.string.tracks_count, window.count)
        }

        // On first open, and after a jump, the list goes to the playing track.
        // The window the player sends is centred on the position, so after a jump
        // the rows slide underfoot: without this the chosen track ends up off
        // screen.
        if ((firstTime || force || movedWindow) && playingAt in rows.indices) {
            views.list.scrollToPositionWithOffset(playingAt)
        }
    }

    /** Puts the row a third of the way down, not stuck to the top. */
    private fun RecyclerView.scrollToPositionWithOffset(at: Int) {
        val manager = layoutManager as? LinearLayoutManager ?: return
        manager.scrollToPositionWithOffset(at, height / 3)
    }

    private fun jumpTo(at: Int, row: Row) {
        val client = Session.client ?: return
        val index = firstIndex + at

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    client.playQueueIndex(index)
                } catch (e: Exception) {
                    // The row stays put: the next poll will say how it went.
                }
            }
            adapter.highlightPath = row.path
            // The count at the top follows the jump, not the round after it.
            load(force = true)
        }
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

    /** The level the pill settled on, sent off the main thread. */
    private fun sendVolume(level: Int) {
        val client = Session.client ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    client.setVolume(level)
                } catch (e: Exception) {
                    // The next poll will say how it really went.
                }
            }
        }
    }
}
