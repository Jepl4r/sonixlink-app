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
 * The main screen: the library in tabs and the playback controls at the bottom.
 *
 * The library is queried from the local copy of the index; only commands and
 * state polls go over the connection.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"

        private const val POLL_MS = 1000L

        /** Minimum wait before an empty cover square is requested again. */
        private const val COVER_RETRY_MS = 5000L

    }

    /** Notifies the tabs of favourite changes and of changes to the player's sort order. */
    interface LibraryListener {
        fun onFavouritesChanged()

        /** Called when the player's sort order changes; the tab should requery its list. */
        fun onOrderChanged() {}
    }

    private lateinit var views: ActivityMainBinding

    /** The player's name, used when restarting the service. */
    private var playerName = ""
    private lateinit var client: PlayerClient

    private val listeners = LinkedHashSet<LibraryListener>()

    private lateinit var volume: VolumePill

    /** Held in a field: unwatchAccent needs the same instance that was registered. */
    private val accentWatch = { applyAccent(Session.accent) }

    /**
     * Retries the playing track's thumbnail when new covers arrive, if the
     * square is still empty.
     */
    private val coversWatch = {
        if (views.nowCover.visibility != View.VISIBLE && coverPath.isNotEmpty()) {
            val path = coverPath
            coverPath = ""
            showNowCover(path)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityMainBinding.inflate(layoutInflater)
        setContentView(views.root)

        // Before any early return: volume keys can arrive even while the screen
        // is finishing, and dispatchKeyEvent uses this.
        volume = VolumePill(views.volume) { level -> send { it.setVolume(level) } }

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, PlayerClient.DEFAULT_PORT)
        if (host.isEmpty()) {
            backToConnect()
            return
        }
        // SyncActivity opens the index and the thumbnails. If the process was
        // recreated straight into this screen they are missing, so go through
        // SyncActivity again instead of showing an empty library.
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

        // The service keeps the heartbeat and the notification running while
        // this screen is in the background.
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        playerName = name
        PlayerService.start(this, host, port, name)
        askForNotifications()

        setSupportActionBar(views.toolbar)
        supportActionBar?.title = name.ifEmpty { client.label }
        supportActionBar?.subtitle = client.label

        views.pager.adapter = Sections(this)
        views.pager.offscreenPageLimit = 1
        TabLayoutMediator(views.tabs, views.pager) { tab, position ->
            val section = Section.entries[position]
            tab.setText(section.titleRes)
            tab.setIcon(section.icon)
        }.attach()

        // Selected so the marquee scrolls text that does not fit.
        views.trackTitle.isSelected = true
        views.trackArtist.isSelected = true

        wireControls()
        // Applied immediately: SyncActivity has already read the accent from
        // the player, so there is no need to wait for the first poll.
        applyAccent(Session.accent)
        showLibraryCount()
        startPolling()
    }

    override fun onStart() {
        super.onStart()
        Session.watchAccent(accentWatch)
        Covers.watch(coversWatch)
        // Catch up on accent and cover changes made while not watching.
        accentWatch()
        coversWatch()
    }

    override fun onStop() {
        super.onStop()
        Session.unwatchAccent(accentWatch)
        Covers.unwatch(coversWatch)
    }

    override fun onDestroy() {
        super.onDestroy()
        volume.release()
        // The session is not cleared here: this screen also finishes to hand
        // over to SyncActivity, which uses the same index. backToConnect clears it.
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
                client.label
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
            val next = Session.heldMode.resolve(Session.state.mode).next()
            Session.heldMode.set(next)
            // The icon updates before the player confirms; the mode name goes
            // into the content description for TalkBack.
            showMode(next)
            views.modeButton.contentDescription = getString(next.label)
            send { it.setMode(next) }
        }

        views.favouriteButton.setOnClickListener {
            val path = Session.state.path
            if (path.isEmpty()) {
                return@setOnClickListener
            }
            val starred = !shownFavourite(Session.state)
            Session.heldFavourite.set(path to starred)
            showFavourite(starred)
            // The Favourites tab requeries once the command has landed.
            send(then = { announceFavourites() }) { it.setFavourite(path, starred) }
        }

        views.queueButton.setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }

        // The track details open the now-playing screen, sliding up from the bottom.
        views.nowDetails.setOnClickListener {
            if (Session.state.hasTrack) {
                startActivity(Intent(this, NowPlayingActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.slide_up, R.anim.stay)
            }
        }
    }

    /**
     * Runs a command on the IO dispatcher. On success refreshes the state and
     * runs [then]; on failure shows a snackbar.
     */
    private fun send(then: () -> Unit = {}, action: (PlayerClient) -> Unit) {
        // Volume keys can reach here before the client exists.
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

    /**
     * Sends a command on behalf of a tab. Once it lands, shows [done] (if any)
     * and then runs [then].
     */
    fun perform(done: String? = null, then: () -> Unit = {}, action: (PlayerClient) -> Unit) {
        send(then = {
            if (done != null) say(done)
            then()
        }, action = action)
    }

    /**
     * Tells the tabs the favourites changed on the player. [afterMs] delays
     * the notice for changes the player applies asynchronously, after the
     * command has returned.
     */
    fun favouritesChanged(afterMs: Long = 0) {
        if (afterMs <= 0) {
            announceFavourites()
        } else {
            views.root.postDelayed({ announceFavourites() }, afterMs)
        }
    }

    /** Plays [path] within the list (and list value) it was chosen from. */
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
        // The service stops itself and its notification when the player stops
        // answering; restart it once the player answers again.
        if (!PlayerService.running) {
            PlayerService.start(this, client.host, client.port, playerName)
        }
    }

    /** Path of the track whose cover is shown; the cover is looked up only when it changes. */
    private var coverPath = ""

    /** Uptime of the last cover lookup that came back empty. */
    private var coverMissedAt = 0L

    /**
     * Retries an empty cover square every [COVER_RETRY_MS]. A background app
     * can have no network, so the request may fail with the screen off.
     */
    private fun retryNowCover() {
        if (coverPath.isEmpty() || views.nowCover.visibility == View.VISIBLE) return
        if (android.os.SystemClock.uptimeMillis() - coverMissedAt < COVER_RETRY_MS) return
        val path = coverPath
        coverPath = ""
        showNowCover(path)
    }

    private fun showNowCover(path: String) {
        if (path == coverPath) return
        coverPath = path
        if (path.isEmpty()) {
            views.nowCover.visibility = View.INVISIBLE
            return
        }
        val side = (48 * resources.displayMetrics.density).toInt().coerceAtLeast(48)
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val row = Session.library?.tracksByPaths(listOf(path))?.get(path)
                val thumb = row?.let {
                    Session.covers?.forTrack(it.artPath, it.artMtime, it.artSize)
                        ?: Session.covers?.forAlbum(it.album)
                }
                // Tracks without a list thumbnail fall back to the full cover
                // from Artwork, which is often already cached.
                thumb ?: smallArtwork(path, side)
            }
            // Drop the result if the track changed during the lookup.
            if (coverPath != path) return@launch
            if (bitmap != null) {
                views.nowCover.setImageBitmap(bitmap)
                views.nowCover.visibility = View.VISIBLE
            } else {
                // INVISIBLE rather than GONE keeps the title from shifting left.
                coverMissedAt = android.os.SystemClock.uptimeMillis()
                views.nowCover.setImageDrawable(null)
                views.nowCover.visibility = View.INVISIBLE
            }
        }
    }

    /** The track's cover from [Artwork], subsampled to no smaller than [side] pixels. */
    private fun smallArtwork(path: String, side: Int): android.graphics.Bitmap? {
        val client = Session.client ?: return null
        val bytes = try {
            Artwork.get(client, path) { coverPath == path }
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
            android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun showState(state: PlayerState) {
        // A sort order changed on the player makes the tabs requery.
        val sort = state.sort
        if (sort != null && sort != Session.sort) {
            Session.sort = sort
            listeners.toList().forEach { it.onOrderChanged() }
        }
        if (state.accent.isNotEmpty()) {
            val color = Session.parseAccent(state.accent)
            if (color != Session.accent) {
                // Setting it notifies the accent watchers, which recolour this
                // screen and any other open one.
                Session.accent = color
                val text = state.accent
                lifecycleScope.launch(Dispatchers.IO) { Settings.rememberAccent(this@MainActivity, text) }
            }
        }
        showNowCover(state.path)
        retryNowCover()

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

        showMode(Session.heldMode.resolve(state.mode))

        views.progressTrack.progress = if (state.duration > 0) {
            (state.position.toLong() * 1000 / state.duration).toInt().coerceIn(0, 1000)
        } else {
            0
        }

        volume.follow(state.volume)

        showFavourite(shownFavourite(state))
        views.favouriteButton.isEnabled = state.hasTrack
    }

    /**
     * Sets the text only if it differs: any write restarts the marquee, even
     * with the same text, and the poll writes every second.
     */
    private fun setText(view: android.widget.TextView, text: String) {
        if (view.text?.toString() != text) {
            view.text = text
        }
    }

    /** The star to show: a value just set here wins until the player agrees (see [Held]). */
    private fun shownFavourite(state: PlayerState): Boolean {
        val (path, starred) = Session.heldFavourite.resolve(state.path to state.favourite)
        return if (path == state.path) starred else state.favourite
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
     * Tints with the player's accent the same elements the player tints: the
     * play button, the progress line, the volume fill and the selected tab.
     */
    private fun applyAccent(color: Int) {
        val tint = android.content.res.ColorStateList.valueOf(color)
        views.progressTrack.progressTintList = tint
        volume.accent()
        views.tabs.setSelectedTabIndicatorColor(color)
        // Without these two tints Material colours the selected tab's icon and
        // label with the theme's `colorPrimary`.
        views.tabs.tabIconTint = Accent.tabColors(this)
        views.tabs.setTabTextColors(
            androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary),
            color,
        )
        Accent.circleButton(views.playButton, 56)
        showMode(Session.heldMode.resolve(Session.state.mode))
    }

    /** Routes the phone's volume keys to the player's volume. */
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
            // onCreate returned early and the screen is finishing.
            true
        } else {
            // A forced download goes through SyncActivity.
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
            // Stopping the service closes the link to the player.
            PlayerService.stop(this)
            backToConnect()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    /**
     * POST_NOTIFICATIONS is required from Android 13 (API 33) for the playback
     * notification to show. It is requested once; a refusal is not asked again.
     */
    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    private fun askForNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (Settings.askedNotifications(this)) return
        Settings.rememberAskedNotifications(this)
        notificationPermission.launch(permission)
    }

    private fun backToConnect() {
        // Leaving the player: the session's index and covers are closed here.
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
