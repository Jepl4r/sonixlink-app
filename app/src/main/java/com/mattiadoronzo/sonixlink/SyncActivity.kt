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
 * The sync screen shown before [MainActivity].
 *
 * Downloads the library index when it is missing or stale and fetches the
 * player's list of thumbnail keys, then opens the main screen. Without the
 * index it stops with a retry button, since an empty list would read as an
 * empty library.
 */
class SyncActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_NAME = "name"

        /** Download again even what is up to date. */
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
        // The last known accent, until the player reports its own.
        Settings.accent(this).takeIf { it.isNotEmpty() }?.let { Session.accent = Session.parseAccent(it) }
        // Always new instances: the calling screen may close the previous ones
        // when it finishes.
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
            // The player's sort order, set before the first list is drawn.
            info.sort?.let { Session.sort = it }

            if (!syncLibrary(info, force)) {
                failed(getString(R.string.sync_no_library))
                return@launch
            }
            syncCovers(info, force)

            // Set before the main screen opens, so it does not start with the
            // default color and switch.
            if (info.accent.isNotEmpty()) {
                Session.accent = Session.parseAccent(info.accent)
                withContext(Dispatchers.IO) { Settings.rememberAccent(this@SyncActivity, info.accent) }
            }
            done()
        }
    }

    /** Downloads the index if needed and opens it. False when there is no usable library. */
    private suspend fun syncLibrary(info: PlayerInfo, force: Boolean): Boolean {
        val library = Session.library ?: return false
        val (stampOwner, stampVersion) = withContext(Dispatchers.IO) { Settings.databaseStamp(this@SyncActivity) }
        val owner = info.identity(client.host)
        val stale = stampOwner != owner || stampVersion != info.dbVersion || info.dbMtime == 0L

        if (info.dbAvailable && (force || stale || !library.exists)) {
            views.syncDetail.setText(R.string.sync_library)
            showAmount(0, info.dbSize)
            val ok = withContext(Dispatchers.IO) {
                try {
                    library.close()
                    client.downloadDatabase(library.databaseFile) { done, total ->
                        val size = if (total > 0) total else info.dbSize
                        runOnUiThread { showAmount(done, size) }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }
            hideAmount()
            if (ok) {
                withContext(Dispatchers.IO) { Settings.rememberDatabaseStamp(this@SyncActivity, owner, info.dbVersion) }
            }
        }
        return withContext(Dispatchers.IO) { library.open() }
    }

    /**
     * Opens the thumbnail store and fetches the player's list of thumbnail
     * keys when it has changed; the thumbnails themselves are fetched as the
     * lists scroll (see [Covers]). Failures here do not stop the sync.
     */
    private suspend fun syncCovers(info: PlayerInfo, force: Boolean) {
        val covers = Session.covers ?: return
        withContext(Dispatchers.IO) { covers.open() }
        val fetchWith = client
        covers.source = { keys -> fetchWith.thumbs(keys) }
        covers.slowLink = fetchWith.isBluetooth

        val (stampOwner, stampVersion) = withContext(Dispatchers.IO) { Settings.coversStamp(this@SyncActivity) }
        val owner = info.identity(client.host)
        val stale = stampOwner != owner || stampVersion != info.coversVersion || info.coversMtime == 0L

        val haveList = !force && !stale && withContext(Dispatchers.IO) { covers.loadRemoteKeys() }
        if (!haveList) {
            if (info.coversAvailable) {
                views.syncDetail.setText(R.string.sync_covers)
                val keys = withContext(Dispatchers.IO) {
                    try {
                        client.thumbKeys()
                    } catch (e: Exception) {
                        null
                    }
                }
                if (keys != null) {
                    withContext(Dispatchers.IO) {
                        covers.setRemoteKeys(keys)
                        Settings.rememberCoversStamp(this@SyncActivity, owner, info.coversVersion)
                    }
                } else {
                    // The player cannot list them: only stored thumbnails are
                    // shown, nothing is fetched.
                    covers.clearRemoteKeys()
                }
            } else {
                covers.setRemoteKeys(emptySet())
            }
        }

        // Picks, once, the track that stands in as each album's cover.
        val library = Session.library
        if (library != null) {
            withContext(Dispatchers.IO) { covers.indexAlbums(library.tracks()) }
        }
    }

    /** Shows how much of the index has arrived, as a bar and "1.2 MB of 4.8 MB". */
    private fun showAmount(done: Long, total: Long) {
        views.syncBar.visibility = View.VISIBLE
        views.syncAmount.visibility = View.VISIBLE
        views.syncProgress.visibility = View.GONE
        if (total > 0) {
            views.syncBar.setProgressCompat((done * 1000 / total).toInt().coerceIn(0, 1000), true)
            views.syncAmount.text = getString(
                R.string.sync_progress,
                android.text.format.Formatter.formatShortFileSize(this, done),
                android.text.format.Formatter.formatShortFileSize(this, total),
            )
        } else {
            // Unknown total: the amount alone.
            views.syncAmount.text = android.text.format.Formatter.formatShortFileSize(this, done)
        }
        views.syncBar.setIndicatorColor(Session.accent)
    }

    private fun hideAmount() {
        views.syncBar.visibility = View.GONE
        views.syncAmount.visibility = View.GONE
        views.syncProgress.visibility = View.VISIBLE
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
        hideAmount()
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
