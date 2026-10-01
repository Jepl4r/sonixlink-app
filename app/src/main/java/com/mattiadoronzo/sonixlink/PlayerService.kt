package com.mattiadoronzo.sonixlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player as a media session on the phone, running for as long as the app
 * is connected, with or without a screen open.
 *
 * - Reads the player's state every few seconds. This is also the heartbeat:
 *   the player shows SonixLink's status bar icon only while requests keep
 *   arriving.
 * - Publishes the state as a media session with a notification (shade and lock
 *   screen controls). The phone's volume keys set the player's volume through
 *   the same session.
 *
 * Started by [MainActivity] once a player is chosen; stopped by "Change
 * device" or the notification's Disconnect action, both of which send
 * /api/bye so the player drops its icon at once.
 */
class PlayerService : Service() {

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_NAME = "name"

        private const val ACTION_PREVIOUS = "com.mattiadoronzo.sonixlink.PREVIOUS"
        private const val ACTION_TOGGLE = "com.mattiadoronzo.sonixlink.TOGGLE"
        private const val ACTION_NEXT = "com.mattiadoronzo.sonixlink.NEXT"
        private const val ACTION_STOP = "com.mattiadoronzo.sonixlink.STOP"

        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1

        /**
         * How often the state is read while playing and while paused. Both
         * must stay well inside the player's PEER_ALIVE_MS.
         */
        private const val PLAYING_MS = 2000L
        private const val PAUSED_MS = 5000L

        /** A state read by a screen this recently is used instead of a new read. */
        private const val FRESH_MS = 1500L

        /**
         * Consecutive failed reads before the player is taken to be gone and
         * the service stops, and the retry interval in between.
         */
        private const val GIVE_UP_AFTER = 3
        private const val RETRY_MS = 2000L

        /**
         * Whether the service is up. [MainActivity] restarts it when it reaches
         * the player after the service has given up.
         */
        @Volatile
        var running = false
            private set

        /** Notification artwork is subsampled down to no less than this many pixels a side. */
        private const val ART_SIDE = 512

        /** Volume change per volume key press, as on the player. */
        private const val VOLUME_STEP = 1

        /** Starts the service for a player, or points the running one at it. */
        fun start(context: Context, host: String, port: Int, name: String) {
            val intent = Intent(context, PlayerService::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_PORT, port)
                .putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, intent)
        }

        /** Sends the player a goodbye, removes the notification and stops the service. */
        fun stop(context: Context) {
            context.startService(Intent(context, PlayerService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poller: Job? = null

    private var client: PlayerClient? = null
    private var name = ""

    private lateinit var session: MediaSessionCompat
    private lateinit var volume: VolumeProviderCompat

    private var state = PlayerState.EMPTY

    /** The artwork shown, and the track it belongs to. */
    @Volatile
    private var artPath: String? = null
    private var art: Bitmap? = null

    /**
     * Held while the player plays, so the reads go on with the phone's screen
     * off; released in pause.
     */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()

        volume = object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, 100, 0) {
            override fun onSetVolumeTo(volume: Int) {
                setVolume(volume)
            }

            override fun onAdjustVolume(direction: Int) {
                if (direction != 0) {
                    setVolume(currentVolume + direction * VOLUME_STEP)
                }
            }
        }

        session = MediaSessionCompat(this, "SonixLink").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = send { it.play() }
                override fun onPause() = send { it.pause() }
                override fun onSkipToNext() = send { it.next() }
                override fun onSkipToPrevious() = send { it.previous() }
                override fun onSeekTo(pos: Long) = send { it.seek((pos / 1000).toInt()) }
                override fun onStop() = disconnect()
            })
            // The volume keys move the player's volume, 0 to 100.
            setPlaybackToRemote(volume)
            setSessionActivity(openApp())
            setActive(true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                disconnect()
                return START_NOT_STICKY
            }
            ACTION_PREVIOUS -> send { it.previous() }
            ACTION_TOGGLE -> send { it.toggle() }
            ACTION_NEXT -> send { it.next() }
            else -> {
                // Only this path comes through startForegroundService, which
                // requires startForeground within seconds; the actions arrive
                // through plain startService.
                goForeground()
                val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
                if (host.isNotEmpty()) {
                    val port = intent?.getIntExtra(EXTRA_PORT, PlayerClient.DEFAULT_PORT) ?: PlayerClient.DEFAULT_PORT
                    name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
                    val current = client
                    if (current == null || current.host != host || current.port != port) {
                        client = PlayerClient(host, port)
                        state = PlayerState.EMPTY
                        artPath = null
                        art = null
                        startPolling()
                    }
                } else if (client == null) {
                    // Restarted by the system without a player to follow.
                    stopEverything()
                    return START_NOT_STICKY
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        poller?.cancel()
        scope.cancel()
        releaseLocks()
        session.setActive(false)
        session.release()
        super.onDestroy()
    }

    // --- The reads ---

    private fun startPolling() {
        poller?.cancel()
        failures = 0
        poller = scope.launch {
            while (isActive) {
                val playing = refresh()
                if (failures >= GIVE_UP_AFTER) {
                    lostPlayer()
                    return@launch
                }
                delay(
                    when {
                        failures > 0 -> RETRY_MS
                        playing -> PLAYING_MS
                        else -> PAUSED_MS
                    }
                )
            }
        }
    }

    /**
     * One read of the state; true while the player is playing. Unless [force],
     * a state a screen read within FRESH_MS is used instead, which serves as
     * the heartbeat too and keeps Bluetooth requests, sent one at a time, free.
     */
    private suspend fun refresh(force: Boolean = false): Boolean {
        val current = client ?: return false
        val age = android.os.SystemClock.elapsedRealtime() - Session.stateAt
        if (!force && age in 0 until FRESH_MS && Session.state !== state) {
            failures = 0
            state = Session.state
            show(state)
            return state.isPlaying
        }
        val read = withContext(Dispatchers.IO) {
            try {
                current.state()
            } catch (e: Exception) {
                null
            }
        }
        if (read == null) {
            failures++
            return state.isPlaying
        }
        failures = 0
        if (current !== client) return false
        state = read
        Session.state = read
        show(read)
        return read.isPlaying
    }

    private fun send(action: (PlayerClient) -> Unit) {
        val current = client ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    action(current)
                } catch (e: Exception) {
                    // The forced read below shows the outcome.
                }
            }
            refresh(force = true)
        }
    }

    private fun setVolume(level: Int) {
        val target = level.coerceIn(0, 100)
        volume.currentVolume = target
        send { it.setVolume(target) }
    }

    // --- The session and the notification ---

    private fun show(state: PlayerState) {
        if (state.path != artPath) {
            artPath = state.path
            art = null
            loadArt(state.path)
        }
        publishMetadata(state)

        val playback = when (state.playState) {
            1 -> PlaybackStateCompat.STATE_PLAYING
            2 -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
                )
                // The system advances the bar between reads from position and speed.
                .setState(playback, state.position * 1000L, if (state.isPlaying) 1f else 0f)
                .build()
        )
        if (volume.currentVolume != state.volume) {
            volume.currentVolume = state.volume.coerceIn(0, 100)
        }

        if (state.isPlaying) holdLocks() else releaseLocks()
        notifyNow()
    }

    private fun publishMetadata(state: PlayerState) {
        val title = if (state.hasTrack) {
            state.title.ifEmpty { state.path.substringAfterLast('/') }
        } else {
            getString(R.string.nothing_playing)
        }
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, state.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.duration * 1000L)
        art?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(builder.build())
    }

    /**
     * Loads the artwork of [path] through [Artwork], shared with the
     * now-playing screen, falling back to the stored thumbnail. A track
     * skipped before its fetch starts is not fetched.
     */
    private fun loadArt(path: String) {
        val current = client ?: return
        if (path.isEmpty()) return
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    Artwork.get(current, path) { artPath == path }?.let { decode(it) } ?: thumbnail(path)
                } catch (e: Artwork.Stale) {
                    null
                } catch (e: Exception) {
                    thumbnail(path)
                } catch (e: OutOfMemoryError) {
                    thumbnail(path)
                }
            }
            if (artPath != path || bitmap == null) return@launch
            art = bitmap
            publishMetadata(state)
            notifyNow()
        }
    }

    private fun thumbnail(path: String): Bitmap? {
        val row = Session.library?.tracksByPaths(listOf(path))?.get(path) ?: return null
        return Session.covers?.forTrack(row.artPath, row.artMtime, row.artSize)
            ?: Session.covers?.forAlbum(row.album)
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        while (side / (sample * 2) >= ART_SIDE) {
            sample *= 2
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.notification_channel),
            // Low importance: updates make no sound.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val title = if (state.hasTrack) {
            state.title.ifEmpty { state.path.substringAfterLast('/') }
        } else {
            getString(R.string.notification_connected, name.ifEmpty { client?.label.orEmpty() })
        }
        val text = listOf(state.artist, state.album).filter { it.isNotEmpty() }.joinToString(" — ")

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(name.ifEmpty { null })
            .setLargeIcon(art)
            .setContentIntent(openApp())
            .setDeleteIntent(action(ACTION_STOP))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setColor(Session.accent)
            .addAction(R.drawable.ic_prev, getString(R.string.cd_prev), action(ACTION_PREVIOUS))
            .addAction(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (state.isPlaying) R.string.cd_pause else R.string.cd_play),
                action(ACTION_TOGGLE),
            )
            .addAction(R.drawable.ic_next, getString(R.string.cd_next), action(ACTION_NEXT))
            .addAction(R.drawable.ic_close, getString(R.string.notification_disconnect), action(ACTION_STOP))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun notifyNow() {
        if (client == null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // No permission: the session still shows on the lock screen and in
            // the system media controls.
            return
        }
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this,
        name.hashCode(),
        Intent(this, PlayerService::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Opens the app as the launcher would: the screen that was open, or the
     * start screen when none was.
     */
    private fun openApp(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, ConnectActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // --- Locks ---

    private fun holdLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SonixLink:playback").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        // Only a Wi-Fi link needs the Wi-Fi lock.
        if (wifiLock == null && client?.isBluetooth == false) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SonixLink:playback")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // --- Leaving ---

    /** Reads that have failed in a row; see GIVE_UP_AFTER. */
    private var failures = 0

    /**
     * The player stopped answering: removes the session and notification and
     * stops the service, without a goodbye. The link is left open for the
     * screens, which restart the service once they reach the player again.
     */
    private fun lostPlayer() {
        client = null
        poller?.cancel()
        session.setActive(false)
        stopEverything()
    }

    /** Says goodbye to the player, then goes. */
    private fun disconnect() {
        val current = client
        client = null
        poller?.cancel()
        if (current != null) {
            // A plain thread: the goodbye must outlive the service and its scope.
            Thread {
                try {
                    current.bye()
                } catch (e: Exception) {
                    // The player drops the phone on its own after PEER_ALIVE_MS.
                }
                // Closes the Bluetooth link; must come after the goodbye, which
                // travels on it.
                PlayerClient.release(current.host, current.port)
            }.start()
        }
        stopEverything()
    }

    private fun stopEverything() {
        releaseLocks()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }
}
