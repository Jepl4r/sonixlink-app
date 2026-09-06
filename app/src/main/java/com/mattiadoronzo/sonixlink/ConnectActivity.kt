package com.mattiadoronzo.sonixlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattiadoronzo.sonixlink.databinding.ActivityConnectBinding
import com.mattiadoronzo.sonixlink.databinding.RowFoundBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The first screen: which player.
 *
 * The player announces itself two different ways (mDNS and a UDP broadcast), so
 * nothing is typed here: whatever appears is tapped.
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var views: ActivityConnectBinding
    private lateinit var discovery: Discovery
    private val found = ArrayList<Discovery.Found>()
    private lateinit var adapter: FoundAdapter

    // How long before saying there is nothing. The search carries on either
    // way: this is only the point where saying so becomes worthwhile.
    private val giveUpAfterMs = 10_000L
    private val clock = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { showNothingFound() }

    /** Discovery runs on a thread of its own: what lands after stop is dropped. */
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(views.root)

        adapter = FoundAdapter { connect(it.host, it.port) }
        views.foundList.layoutManager = LinearLayoutManager(this)
        views.foundList.adapter = adapter
        views.rescanButton.setOnClickListener { restartDiscovery() }

        discovery = Discovery(this)

        // If the app closed itself last time, the trace is sitting there. Show
        // it before going on, or it reconnects and crashes again with nobody
        // having read a thing.
        val crash = CrashLog.pending(this)
        if (crash != null) {
            showCrash(crash)
            return
        }

        reconnect()
    }

    override fun onStart() {
        super.onStart()
        restartDiscovery()
    }

    override fun onStop() {
        super.onStop()
        listening = false
        discovery.stop()
        clock.removeCallbacks(giveUp)
    }

    /** A player that has worked before is returned to without asking. */
    private fun reconnect() {
        Settings.lastHost(this)?.let { (host, port) ->
            connect(host, port, silentFailure = true)
        }
    }

    private fun restartDiscovery() {
        listening = false
        discovery.stop()
        found.clear()
        adapter.submit(found)
        views.nothingFound.visibility = View.GONE
        showSearching()

        clock.removeCallbacks(giveUp)
        clock.postDelayed(giveUp, giveUpAfterMs)

        listening = true
        discovery.start { player ->
            if (!listening) return@start
            found.add(player)
            adapter.submit(found)
            showSearching()
        }
    }

    private fun showSearching() {
        views.progress.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        views.statusText.text = if (found.isEmpty()) {
            getString(R.string.connect_searching)
        } else {
            resources.getQuantityString(R.plurals.players_found, found.size, found.size)
        }
        // If a player has turned up meanwhile the explanation goes: the retry
        // button stays, but "no player found" over a found player does not.
        views.hintText.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Once the ten seconds are up. The retry button appears either way: even with
     * something in the list it may be the wrong player, or one that has stopped
     * answering, and a screen with no way out is the worst it could be.
     */
    private fun showNothingFound() {
        views.nothingFound.visibility = View.VISIBLE
        views.hintText.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        if (found.isEmpty()) {
            views.progress.visibility = View.GONE
        }
    }

    private fun connect(host: String, port: Int, silentFailure: Boolean = false) {
        views.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    PlayerClient(host, port).info()
                } catch (e: Exception) {
                    null
                }
            }
            views.progress.visibility = View.GONE
            if (info == null) {
                if (!silentFailure) {
                    views.statusText.text = getString(R.string.connect_failed, host)
                }
                return@launch
            }
            Settings.rememberHost(this@ConnectActivity, host, port)
            startActivity(
                Intent(this@ConnectActivity, SyncActivity::class.java)
                    .putExtra(SyncActivity.EXTRA_HOST, host)
                    .putExtra(SyncActivity.EXTRA_PORT, port)
                    .putExtra(SyncActivity.EXTRA_NAME, info.name)
            )
            finish()
        }
    }

    // -----------------------------------------------------------------------

    private fun showCrash(text: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("SonixLink", text))
                CrashLog.clear(this)
                reconnect()
            }
            .setNegativeButton(R.string.crash_dismiss) { _, _ ->
                CrashLog.clear(this)
                reconnect()
            }
            .show()
    }

    // -----------------------------------------------------------------------

    private class FoundAdapter(val onClick: (Discovery.Found) -> Unit) :
        androidx.recyclerview.widget.RecyclerView.Adapter<FoundAdapter.Holder>() {

        private val items = ArrayList<Discovery.Found>()

        fun submit(list: List<Discovery.Found>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        class Holder(val views: RowFoundBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(views.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder =
            Holder(RowFoundBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.views.name.text = item.name
            holder.views.address.text = "${item.host}:${item.port}"
            holder.views.root.setOnClickListener { onClick(item) }
        }
    }
}
