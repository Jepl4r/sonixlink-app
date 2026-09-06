package com.mattiadoronzo.sonixlink

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mattiadoronzo.sonixlink.databinding.ActivitySyncBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Syncing, on a page of its own.
 *
 * Brings the two files the app needs onto the phone -- the library index and the
 * player's thumbnails -- and only opens the real screen once both are there.
 * Before that there is nothing to show: an empty list reads as an empty library,
 * and transport controls over a library that has not arrived are just confusing.
 */
class SyncActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"

        /** Download again even what still looks good. */
        const val EXTRA_FORCE = "force"
    }

    private lateinit var views: ActivitySyncBinding
    private lateinit var client: PlayerClient
    private var playerName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivitySyncBinding.inflate(layoutInflater)
        setContentView(views.root)

        val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, PlayerClient.DEFAULT_PORT)
        playerName = intent.getStringExtra(EXTRA_NAME).orEmpty().ifEmpty { host }
        if (host.isEmpty()) {
            backToConnect()
            return
        }

        client = PlayerClient(host, port)
        Session.client = client
        // The accent last seen, until the player says its own.
        Settings.accent(this).takeIf { it.isNotEmpty() }?.let { Session.accent = Session.parseAccent(it) }
        // Always fresh: the screen that sent us here may close afterwards and
        // take the previous ones with it.
        Session.library?.close()
        Session.covers?.close()
        Session.library = Library(this)
        Session.covers = Covers(this)

        views.retryButton.setOnClickListener { start() }
        start()
    }

    private fun start() {
        val force = intent.getBooleanExtra(EXTRA_FORCE, false)
        views.retryButton.visibility = View.GONE
        views.syncProgress.visibility = View.VISIBLE
        views.syncText.setText(R.string.syncing)
        views.syncDetail.text = ""

        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    client.info()
                } catch (e: Exception) {
                    null
                }
            }
            if (info == null) {
                failed(getString(R.string.sync_unreachable))
                return@launch
            }
            if (info.scanning) {
                failed(getString(R.string.scanning_wait))
                return@launch
            }

            if (!syncLibrary(info, force)) {
                failed(getString(R.string.sync_no_library))
                return@launch
            }
            syncCovers(info, force)

            // The player's accent before the screen opens, so it does not start
            // blue and change a second later.
            if (info.accent.isNotEmpty()) {
                Session.accent = Session.parseAccent(info.accent)
                withContext(Dispatchers.IO) { Settings.rememberAccent(this@SyncActivity, info.accent) }
            }
            done()
        }
    }

    /** The index. Nothing goes on without it: it is the library. */
    private suspend fun syncLibrary(info: PlayerInfo, force: Boolean): Boolean {
        val library = Session.library ?: return false
        val (stampHost, stampMtime) = withContext(Dispatchers.IO) { Settings.databaseStamp(this@SyncActivity) }
        val stale = stampHost != client.host || stampMtime != info.dbMtime || info.dbMtime == 0L

        if (info.dbAvailable && (force || stale || !library.exists)) {
            views.syncDetail.setText(R.string.sync_library)
            val ok = withContext(Dispatchers.IO) {
                try {
                    library.close()
                    client.downloadDatabase(library.databaseFile)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                withContext(Dispatchers.IO) { Settings.rememberDatabaseStamp(this@SyncActivity, client.host, info.dbMtime) }
            }
        }
        return withContext(Dispatchers.IO) { library.open() }
    }

    /**
     * The thumbnails. Without them the library still reads, with icons: not a
     * reason to stop everything.
     */
    private suspend fun syncCovers(info: PlayerInfo, force: Boolean) {
        val covers = Session.covers ?: return
        val (stampHost, stampMtime) = withContext(Dispatchers.IO) { Settings.coversStamp(this@SyncActivity) }
        val stale = stampHost != client.host || stampMtime != info.coversMtime || info.coversMtime == 0L

        if (info.coversAvailable && (force || stale || !covers.exists)) {
            views.syncDetail.setText(R.string.sync_covers)
            val ok = withContext(Dispatchers.IO) {
                try {
                    covers.close()
                    client.downloadCovers(covers.databaseFile)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                withContext(Dispatchers.IO) { Settings.rememberCoversStamp(this@SyncActivity, client.host, info.coversMtime) }
            }
        }
        withContext(Dispatchers.IO) { covers.open() }

        // Which track stands in as each album's cover. Done here, once, and not
        // row by row while scrolling: the player only has thumbnails for tracks
        // it has actually drawn, and picking any track of the album left records
        // blank that do have artwork.
        val library = Session.library
        if (library != null) {
            views.syncDetail.setText(R.string.sync_covers)
            withContext(Dispatchers.IO) { covers.indexAlbums(library.tracks()) }
        }
    }

    private fun done() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_HOST, client.host)
                .putExtra(MainActivity.EXTRA_PORT, client.port)
                .putExtra(MainActivity.EXTRA_NAME, playerName)
        )
        finish()
    }

    private fun failed(message: String) {
        views.syncProgress.visibility = View.GONE
        views.syncText.text = message
        views.syncDetail.text = ""
        views.retryButton.visibility = View.VISIBLE
    }

    private fun backToConnect() {
        startActivity(Intent(this, ConnectActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }
}
