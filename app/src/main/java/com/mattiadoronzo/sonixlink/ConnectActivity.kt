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
 * The first screen: picks the player to connect to, from those found on the
 * network (mDNS and the UDP beacon) or from the phone's paired Bluetooth devices.
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var views: ActivityConnectBinding
    private lateinit var discovery: Discovery
    private val found = ArrayList<Discovery.Found>()
    private lateinit var adapter: FoundAdapter

    // When to show the "nothing found" hint. Discovery keeps running after it.
    private val giveUpAfterMs = 10_000L
    private val clock = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { showNothingFound() }

    /** Discovery reports from its own thread; results that land after a stop are dropped. */
    private var listening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(views.root)

        adapter = FoundAdapter { connect(it.host, it.port) }
        views.foundList.layoutManager = LinearLayoutManager(this)
        views.foundList.adapter = adapter
        views.rescanButton.setOnClickListener { restartDiscovery() }
        views.bluetoothButton.setOnClickListener { pickBluetooth() }

        discovery = Discovery(this)

        // A crash trace from the previous run is shown before reconnecting, so
        // it can be read before the same crash happens again.
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
        // The "no player found" hint hides once a player is listed; the retry
        // button stays.
        views.hintText.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Runs when [giveUpAfterMs] expires. The retry button appears even when the
     * list is not empty: a listed player may be the wrong one or may have stopped
     * answering.
     */
    private fun showNothingFound() {
        views.nothingFound.visibility = View.VISIBLE
        views.hintText.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
        if (found.isEmpty()) {
            views.progress.visibility = View.GONE
        }
    }

    private fun connect(host: String, port: Int, silentFailure: Boolean = false, shownName: String = host) {
        views.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            var why = ""
            val info = withContext(Dispatchers.IO) {
                try {
                    PlayerClient(host, port).info()
                } catch (e: Exception) {
                    why = e.message.orEmpty()
                    null
                }
            }
            views.progress.visibility = View.GONE
            if (info == null) {
                if (!silentFailure) {
                    // Over Bluetooth the error is shown: "no service" and
                    // "connection refused" need different fixes.
                    val detail = if (BluetoothTransport.isBluetooth(host) && why.isNotEmpty()) "\n$why" else ""
                    views.statusText.text = getString(R.string.connect_failed, shownName) + detail
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
    // Bluetooth
    // -----------------------------------------------------------------------

    /** Asked for on Android 12 and up, before the paired devices can be read. */
    private val bluetoothPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pickBluetooth()
        } else {
            views.statusText.text = getString(R.string.bluetooth_denied)
        }
    }

    /**
     * Lists the phone's paired devices to choose the player from. Any paired
     * device can be picked; one without the SonixLink RFCOMM service fails to
     * connect.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun pickBluetooth() {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        @Suppress("DEPRECATION")
        val bluetooth = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
        if (bluetooth == null || !bluetooth.isEnabled) {
            views.statusText.text = getString(R.string.bluetooth_off)
            return
        }
        val devices = try {
            bluetooth.bondedDevices.orEmpty().sortedBy { it.name.orEmpty().lowercase() }
        } catch (e: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.bluetooth_pick)
                .setMessage(R.string.bluetooth_none)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val names = devices.map { it.name?.takeIf { name -> name.isNotBlank() } ?: it.address }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.bluetooth_pick)
            .setItems(names) { _, which ->
                val device = devices[which]
                connect(BluetoothTransport.PREFIX + device.address, 0, shownName = names[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

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
