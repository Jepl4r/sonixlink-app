package com.mattiadoronzo.sonixlink

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayoutMediator
import com.mattiadoronzo.sonixlink.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The real screen: the library in six tabs and the controls at the bottom.
 *
 * The index is downloaded once and then queried locally, so scrolling six
 * thousand tracks puts nothing on the network. All that stays on the wire are
 * the commands and the state, which are two lines of JSON.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"

        private const val POLL_MS = 1000L

    }

    /** How the tabs hear that a star has changed. */
    interface LibraryListener {
        fun onFavouritesChanged()
    }

    private lateinit var views: ActivityMainBinding
    private lateinit var client: PlayerClient

    private val listeners = LinkedHashSet<LibraryListener>()

    /** The volume pill, the same one every other page has. */
    private lateinit var volume: VolumePill

    /** Kept in a field: the object itself is what removes itself. */
    private val accentWatch = { applyAccent(Session.accent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)

        // Before any early return: the volume keys arrive regardless, and this
        // screen can close at once to send the user elsewhere.
        volume = VolumePill(views.volume) { level -> send { it.setVolume(level) } }

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, PlayerClient.DEFAULT_PORT)
        if (host.isEmpty()) {
            backToConnect()
            return
        }
        // SyncActivity is what opens the index and the thumbnails. If they are
        // missing -- Android rebuilt the process and stood this screen up on its
        // own -- an empty library is not shown: it goes back through there.
        if (Session.library == null) {
            startActivity(
                Intent(this, SyncActivity::class.java)
                    .putExtra(SyncActivity.EXTRA_HOST, host)
                    .putExtra(SyncActivity.EXTRA_PORT, port)
                    .putExtra(SyncActivity.EXTRA_NAME, intent.getStringExtra(EXTRA_NAME).orEmpty())
            )
            finish()
            return
        }
        client = PlayerClient(host, port)
        Session.client = client

        setSupportActionBar(views.toolbar)
        supportActionBar?.title = intent.getStringExtra(EXTRA_NAME) ?: host
        supportActionBar?.subtitle = "$host:$port"

        views.pager.adapter = Sections(this)
        views.pager.offscreenPageLimit = 1
        TabLayoutMediator(views.tabs, views.pager) { tab, position ->
            val section = Section.entries[position]
            tab.setText(section.titleRes)
            tab.setIcon(section.icon)
        }.attach()

        // The title scrolls itself when it does not fit, as on the player and
        // in the now-playing screen.
        views.trackTitle.isSelected = true
        views.trackArtist.isSelected = true

        wireControls()
        // At once, not on the first state: SyncActivity has already asked the
        // player, and waiting for the poll would mean a second of the wrong blue.
        applyAccent(Session.accent)
        showLibraryCount()
        startPolling()
    }

    override fun onStart() {
        super.onStart()
        Session.watchAccent(accentWatch)
        // The accent may have changed while another screen was in front.
        accentWatch()
    }

    override fun onStop() {
        super.onStop()
        Session.unwatchAccent(accentWatch)
    }

    override fun onDestroy() {
        super.onDestroy()
        volume.release()
        // The session is not cleared here: this screen also closes to hand over
        // to syncing, which is already using the same index. Clearing belongs to
        // whoever goes back to choosing a player.
    }

    // -----------------------------------------------------------------------
    // The tabs
    // -----------------------------------------------------------------------

    private class Sections(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = Section.entries.size
        override fun createFragment(position: Int): Fragment =
            BrowseFragment.of(Section.entries[position])
    }

    fun addLibraryListener(listener: LibraryListener) {
        listeners.add(listener)
    }

    fun removeLibraryListener(listener: LibraryListener) {
        listeners.remove(listener)
    }

    // -----------------------------------------------------------------------
    // The index
    // -----------------------------------------------------------------------

    private fun showLibraryCount() {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { Session.library?.trackCount() ?: 0 }
            supportActionBar?.subtitle = if (count > 0) {
                getString(R.string.library_ready, count)
            } else {
                "${client.host}:${client.port}"
            }
        }
    }

    private fun announceFavourites() {
        listeners.toList().forEach { it.onFavouritesChanged() }
    }

    // -----------------------------------------------------------------------
    // The commands
    // -----------------------------------------------------------------------

    private fun wireControls() {
        views.playButton.setOnClickListener { send { it.toggle() } }
        views.prevButton.setOnClickListener { send { it.previous() } }
        views.nextButton.setOnClickListener { send { it.next() } }
        views.modeButton.setOnClickListener {
            val next = Session.state.mode.next()
            // The button changes face at once: confirmation comes with the next
            // poll, and waiting for it would make the button look broken.
            showMode(next)
            say(getString(next.label))
            send { it.setMode(next) }
        }

        views.favouriteButton.setOnClickListener {
            val path = Session.state.path
            if (path.isEmpty()) {
                return@setOnClickListener
            }
            val starred = !Session.state.favourite
            Session.state = Session.state.copy(favourite = starred)
            showFavourite(starred)
            // The Favourites tab is looking at the same list: it has to be redone.
            send(then = { announceFavourites() }) { it.setFavourite(path, starred) }
        }

        views.queueButton.setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }

        // The track details open the full screen, like touching the artwork on
        // the player.
        views.nowDetails.setOnClickListener {
            if (Session.state.hasTrack) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
                // It rises from the bottom instead of arriving from the right:
                // the same screen as before, seen closer.
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.slide_up, R.anim.stay)
            }
        }
    }

    /** Sends a command off the main thread, and says so when it does not land. */
    private fun send(then: () -> Unit = {}, action: (PlayerClient) -> Unit) {
        // The screen can be standing with no player: the volume keys reach here
        // too, and the system delivers those regardless.
        if (!::client.isInitialized) return
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) {
                try {
                    action(client)
                    false
                } catch (e: Exception) {
                    true
                }
            }
            if (failed) {
                say(getString(R.string.command_failed))
            } else {
                refreshState()
                then()
            }
        }
    }

    /** Starts one track, from the list it was touched in. */
    fun play(path: String, list: String = "all", value: String = "") {
        if (path.isEmpty()) return
        send { it.playPath(path, list, value) }
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private fun startPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refreshState()
                    delay(POLL_MS)
                }
            }
        }
    }

    private suspend fun refreshState() {
        val state = withContext(Dispatchers.IO) {
            try {
                client.state()
            } catch (e: Exception) {
                null
            }
        } ?: return
        Session.state = state
        showState(state)
    }

    /** The playing track's artwork: looked up only when the track changes. */
    private var coverPath = ""

    private fun showNowCover(path: String) {
        if (path == coverPath) return
        coverPath = path
        if (path.isEmpty()) {
            views.nowCover.visibility = View.INVISIBLE
            return
        }
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val row = Session.library?.tracksByPaths(listOf(path))?.get(path)
                if (row == null) {
                    null
                } else {
                    Session.covers?.forTrack(row.artPath, row.artMtime, row.artSize)
                }
            }
            // The track can have changed meanwhile: the old artwork must not
            // land on top of the current one.
            if (coverPath != path) return@launch
            if (bitmap != null) {
                views.nowCover.setImageBitmap(bitmap)
                views.nowCover.visibility = View.VISIBLE
            } else {
                // Invisible rather than gone: the square keeps its place and the
                // title does not jump left between one track and the next.
                views.nowCover.setImageDrawable(null)
                views.nowCover.visibility = View.INVISIBLE
            }
        }
    }

    private fun showState(state: PlayerState) {
        if (state.accent.isNotEmpty()) {
            val color = Session.parseAccent(state.accent)
            if (color != Session.accent) {
                // Only here: the watcher does the recolouring, and it reaches the
                // tabs and the other screens still open as well.
                Session.accent = color
                val text = state.accent
                lifecycleScope.launch(Dispatchers.IO) { Settings.rememberAccent(this@MainActivity, text) }
            }
        }
        showNowCover(state.path)

        setText(
            views.trackTitle,
            if (state.hasTrack) {
                state.title.ifEmpty { state.path.substringAfterLast('/') }
            } else {
                getString(R.string.nothing_playing)
            },
        )
        val by = listOf(state.artist, state.album).filter { it.isNotEmpty() }.joinToString(" — ")
        setText(views.trackArtist, by)
        views.trackArtist.visibility = if (by.isEmpty()) View.GONE else View.VISIBLE

        views.playButton.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        views.playButton.contentDescription =
            getString(if (state.isPlaying) R.string.cd_pause else R.string.cd_play)

        showMode(state.mode)

        views.progressTrack.progress = if (state.duration > 0) {
            (state.position.toLong() * 1000 / state.duration).toInt().coerceIn(0, 1000)
        } else {
            0
        }

        volume.follow(state.volume)

        showFavourite(state.favourite)
        views.favouriteButton.isEnabled = state.hasTrack
    }

    /**
     * Writing into a TextView restarts the marquee from the beginning, even when
     * the text is the same as before: the poll, once a second, had the title
     * moving in fits and starts.
     */
    private fun setText(view: android.widget.TextView, text: String) {
        if (view.text?.toString() != text) {
            view.text = text
        }
    }

    private fun showFavourite(starred: Boolean) {
        views.favouriteButton.setImageResource(
            if (starred) R.drawable.ic_favourite_filled else R.drawable.ic_favourite
        )
        views.favouriteButton.imageTintList = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(
                this,
                if (starred) R.color.favourite else R.color.text_secondary,
            )
        )
    }

    private fun showMode(mode: PlayMode) {
        views.modeButton.setImageResource(mode.icon)
        views.modeButton.imageTintList = Accent.onOff(this, mode != PlayMode.NORMAL)
    }

    /**
     * The app dresses itself in the accent chosen on the player. What follows it
     * is what the player itself paints with the accent: the play button, the
     * progress line, the volume fill, the open tab's indicator.
     */
    private fun applyAccent(color: Int) {
        val tint = android.content.res.ColorStateList.valueOf(color)
        views.progressTrack.progressTintList = tint
        volume.accent()
        views.tabs.setSelectedTabIndicatorColor(color)
        // The tabs' icons and labels: without these two Material tints the
        // selected tab with `colorPrimary`, the theme's starting blue.
        views.tabs.tabIconTint = Accent.tabColors(this)
        views.tabs.setTabTextColors(
            androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary),
            color,
        )
        // The play button sits inside its circle, as on the player.
        Accent.circleButton(views.playButton, 56)
        showMode(Session.state.mode)
    }

    /**
     * The phone's volume keys drive the player, not the phone's speaker: no sound
     * comes out here, and they are the handiest way to turn it up.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        volume.onKey(event) || super.dispatchKeyEvent(event)

    // -----------------------------------------------------------------------
    // The menu
    // -----------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_search -> {
            startActivity(Intent(this, SearchActivity::class.java))
            true
        }

        R.id.action_refresh -> if (!::client.isInitialized) {
            // The screen is already closing: there is nothing to refresh.
            true
        } else {
            // Downloading again means going back through the page that does it.
            startActivity(
                Intent(this, SyncActivity::class.java)
                    .putExtra(SyncActivity.EXTRA_HOST, client.host)
                    .putExtra(SyncActivity.EXTRA_PORT, client.port)
                    .putExtra(SyncActivity.EXTRA_NAME, supportActionBar?.title?.toString().orEmpty())
                    .putExtra(SyncActivity.EXTRA_FORCE, true)
            )
            finish()
            true
        }

        R.id.action_disconnect -> {
            Settings.forgetHost(this)
            backToConnect()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun backToConnect() {
        // Here it does: the index is of no use to anyone any more.
        Session.clear()
        startActivity(
            Intent(this, ConnectActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private fun say(message: String) {
        Snackbar.make(views.root, message, Snackbar.LENGTH_LONG).show()
    }
}
