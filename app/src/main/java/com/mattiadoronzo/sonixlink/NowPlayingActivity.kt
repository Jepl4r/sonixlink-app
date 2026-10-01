package com.mattiadoronzo.sonixlink

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mattiadoronzo.sonixlink.databinding.ActivityNowPlayingBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The playing track, full screen.
 *
 * The artwork is the full-size image from `/api/art`, decoded on the phone, not
 * the list thumbnail. The background is the same image, blurred, as on the player.
 */
class NowPlayingActivity : AppCompatActivity() {

    companion object {
        private const val POLL_MS = 1000L

        /** How often the clock and the bar advance between reads. */
        private const val TICK_MS = 250L

        /** Decode size limit in pixels when the screen size is unknown. */
        private const val MAX_SIDE_FALLBACK = 1080

        /**
         * The background is scaled to this side and then box-blurred. Much
         * smaller and the squares show once stretched full screen.
         */
        private const val BLUR_SIDE = 128
        private const val BLUR_PASSES = 3
        private const val BLUR_RADIUS = 6
    }

    private lateinit var views: ActivityNowPlayingBinding

    /** The track whose artwork is already on screen. */
    private var artPath = ""

    /** The track with an artwork request in flight. */
    private var artAsked = ""

    /** Tracks the player reports as having no artwork; not asked for again. */
    private val artMissing = HashSet<String>()

    private var seeking = false
    private var seekHoldUntil = 0L

    private lateinit var volume: VolumePill

    /** Kept in a field: unwatchAccent removes it by identity. */
    private val accentWatch = { applyAccent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(views.root)

        // Before the early return: dispatchKeyEvent uses it regardless.
        volume = VolumePill(views.volume) { level -> send { it.setVolume(level) } }

        if (Session.client == null) {
            finish()
            return
        }

        // Selected so the marquee scrolls text that does not fit.
        views.trackTitle.isSelected = true
        views.trackArtist.isSelected = true

        applyAccent()
        wireControls()

        // Shows the last state read at once: the first read of this screen can
        // queue behind others on a Bluetooth link.
        if (Session.state.hasTrack) {
            show(Session.state)
            showArt(Session.state.path)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
        // The clock runs in its own loop so it keeps moving while a read is
        // held up on the link.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    showProgress()
                    delay(TICK_MS)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (dismissing) {
            // The sheet is already off screen; a window animation would
            // restart it from the top.
            overridePendingTransition(0, 0)
        } else {
            overridePendingTransition(R.anim.stay, R.anim.slide_down)
        }
    }

    // --- Dragging down ---

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragBlocked = false
    private var dismissing = false
    private val slop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }

    /**
     * A downward drag moves the sheet with the finger; released past a fifth of
     * the height, it closes the screen. Handled here so the gesture is seen
     * before the children consume the touches.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                // The seek bar and the open volume pill keep their own drags.
                dragBlocked = dismissing ||
                    volume.showing ||
                    over(views.progressSeek, event)
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragging && !dragBlocked) {
                    val down = event.rawY - downY
                    if (down > slop && down > kotlin.math.abs(event.rawX - downX)) {
                        dragging = true
                        // Cancels the touch for the children, or the button
                        // under the finger stays pressed and fires on release.
                        val cancel = android.view.MotionEvent.obtain(event)
                        cancel.action = android.view.MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (dragging) {
                    views.sheet.translationY = (event.rawY - downY - slop).coerceAtLeast(0f)
                    return true
                }
            }

            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    endDrag()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun over(view: View, event: android.view.MotionEvent): Boolean {
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        return event.rawX >= at[0] && event.rawX <= at[0] + view.width &&
            event.rawY >= at[1] && event.rawY <= at[1] + view.height
    }

    private fun endDrag() {
        val sheet = views.sheet
        if (sheet.translationY < sheet.height / 5f) {
            sheet.animate().translationY(0f).setDuration(160).start()
            return
        }
        dismissing = true
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(160)
            .withEndAction { finish() }
            .start()
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

    override fun onDestroy() {
        super.onDestroy()
        volume.release()
    }

    private fun applyAccent() {
        val accent = Session.accent
        Accent.circleButton(views.playButton, 64)
        views.progressSeek.progressTintList = android.content.res.ColorStateList.valueOf(accent)
        volume.accent()

        // The player's knob: an accent-filled circle with a white ring.
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
            setStroke(dp(2), 0xFFFFFFFF.toInt())
            setSize(dp(18), dp(18))
        }
        views.progressSeek.thumb = ring
        views.progressSeek.thumbOffset = dp(9)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** The phone's volume keys set the player's volume, not the ringer's. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        volume.onKey(event) || super.dispatchKeyEvent(event)

    private fun wireControls() {
        views.playButton.setOnClickListener { send { it.toggle() } }
        views.prevButton.setOnClickListener { send { it.previous() } }
        views.nextButton.setOnClickListener { send { it.next() } }
        views.modeButton.setOnClickListener {
            val next = Session.heldMode.resolve(Session.state.mode).next()
            Session.heldMode.set(next)
            showMode(next)
            send { it.setMode(next) }
        }
        views.favouriteButton.setOnClickListener {
            val path = Session.state.path
            if (path.isEmpty()) return@setOnClickListener
            val starred = !shownFavourite(Session.state)
            Session.heldFavourite.set(path to starred)
            showFavourite(starred)
            send { it.setFavourite(path, starred) }
        }
        views.queueButton.setOnClickListener {
            startActivity(Intent(this, QueueActivity::class.java))
        }

        views.progressSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = Session.state.duration
                if (duration > 0) {
                    views.elapsed.text = clock(value.toLong() * duration / 1000)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                seeking = false
                val duration = Session.state.duration
                if (duration <= 0) return
                val seconds = (bar.progress.toLong() * duration / 1000).toInt()
                // The player takes a moment to seek; the bar holds where the
                // finger left it until then.
                seekHoldUntil = android.os.SystemClock.uptimeMillis() + 1200
                send { it.seek(seconds) }
            }
        })
    }

    private fun send(action: (PlayerClient) -> Unit) {
        val client = Session.client ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    action(client)
                } catch (e: Exception) {
                    // The next poll shows the real state.
                }
            }
            refresh()
        }
    }

    private suspend fun refresh() {
        val client = Session.client ?: return
        val state = withContext(Dispatchers.IO) {
            try {
                client.state()
            } catch (e: Exception) {
                null
            }
        } ?: return
        Session.state = state
        // The other screens' polls are stopped while this one is in front, so
        // it picks up accent changes for all of them.
        if (state.accent.isNotEmpty()) {
            val color = Session.parseAccent(state.accent)
            if (color != Session.accent) {
                // Setting Session.accent notifies the watchers, which recolour
                // this screen and the ones beneath it.
                Session.accent = color
                val text = state.accent
                lifecycleScope.launch(Dispatchers.IO) { Settings.rememberAccent(this@NowPlayingActivity, text) }
            }
        }
        show(state)
        showArt(state.path)
    }

    /** The star as shown: a change made here wins until the player reports it. */
    private fun shownFavourite(state: PlayerState): Boolean {
        val (path, starred) = Session.heldFavourite.resolve(state.path to state.favourite)
        return if (path == state.path) starred else state.favourite
    }

    /** The last state read and its uptime, for the clock to run on between reads. */
    private var shownState: PlayerState = PlayerState.EMPTY
    private var shownAt = 0L

    /**
     * The current position in seconds: the last read plus the time elapsed
     * since, while playing, capped at the duration. Reads arrive at most once
     * a second, later over Bluetooth.
     */
    private fun livePosition(): Long {
        val state = shownState
        var seconds = state.position.toLong()
        if (state.isPlaying && shownAt > 0) {
            seconds += (android.os.SystemClock.uptimeMillis() - shownAt) / 1000
        }
        return if (state.duration > 0) seconds.coerceAtMost(state.duration.toLong()) else seconds
    }

    private fun showProgress() {
        if (seeking || android.os.SystemClock.uptimeMillis() < seekHoldUntil) return
        val state = shownState
        val position = livePosition()
        views.progressSeek.progress = if (state.duration > 0) {
            (position * 1000 / state.duration).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        views.elapsed.text = clock(position)
    }

    private fun show(state: PlayerState) {
        shownState = state
        shownAt = android.os.SystemClock.uptimeMillis()
        setText(views.trackTitle, state.title.ifEmpty { state.path.substringAfterLast('/') })
        setText(
            views.trackArtist,
            listOf(state.artist, state.album).filter { it.isNotEmpty() }.joinToString(" — "),
        )

        views.playButton.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        showMode(Session.heldMode.resolve(state.mode))
        showFavourite(shownFavourite(state))

        showProgress()
        views.total.text = clock(state.duration.toLong())

        volume.follow(state.volume)

        // The player's own "4/12": the place in the shuffled order under
        // shuffle, empty for a single track or a book.
        views.queuePosition.text = when {
            state.displayCount > 0 && state.displayPosition > 0 ->
                getString(R.string.queue_position, state.displayPosition, state.displayCount)
            state.displayCount == 0 -> ""
            state.queuePosition >= 0 && state.queueCount > 0 ->
                getString(R.string.queue_position, state.queuePosition + 1, state.queueCount)
            else -> ""
        }
    }

    /**
     * Sets the text only when it differs: any write restarts the marquee, even
     * with the same text, and the poll writes once a second.
     */
    private fun setText(view: android.widget.TextView, text: String) {
        if (view.text?.toString() != text) {
            view.text = text
        }
    }

    private fun showMode(mode: PlayMode) {
        views.modeButton.setImageResource(mode.icon)
        views.modeButton.imageTintList = Accent.onOff(this, mode != PlayMode.NORMAL)
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

    private fun clock(seconds: Long): String {
        if (seconds <= 0) return "0:00"
        val minutes = seconds / 60
        return if (minutes >= 60) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
        } else {
            String.format(java.util.Locale.US, "%d:%02d", minutes, seconds % 60)
        }
    }

    // --- The artwork ---

    /** The track the stand-in on screen belongs to. */
    private var standInPath = ""

    /**
     * Shows the cached list thumbnail while the full artwork loads, so the
     * previous track's cover does not stay under the new title.
     */
    private fun showStandIn(path: String) {
        if (path == standInPath) return
        standInPath = path
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                try {
                    val row = Session.library?.tracksByPaths(listOf(path))?.get(path) ?: return@withContext null
                    val thumb = Session.covers?.forTrack(row.artPath, row.artMtime, row.artSize)
                        ?: Session.covers?.forAlbum(row.album)
                        ?: return@withContext null
                    thumb to blur(thumb)
                } catch (e: Exception) {
                    null
                }
            }
            // Only if the track is unchanged and its full artwork is not shown yet.
            if (Session.state.path != path || artPath == path || standInPath != path) return@launch
            if (pair != null) {
                views.cover.setImageBitmap(pair.first)
                views.backdrop.setImageBitmap(pair.second)
                views.coverPlaceholder.visibility = View.GONE
            } else {
                views.cover.setImageDrawable(null)
                views.backdrop.setImageDrawable(null)
                views.coverPlaceholder.visibility = View.VISIBLE
            }
        }
    }

    private fun showArt(path: String) {
        // `artPath` is set only once the image is shown, so a failed request
        // is retried on the next poll.
        if (path == artPath || path == artAsked || path in artMissing) return
        artAsked = path
        if (path.isEmpty()) {
            artPath = path
            views.cover.setImageDrawable(null)
            views.backdrop.setImageDrawable(null)
            views.coverPlaceholder.visibility = View.VISIBLE
            return
        }

        val client = Session.client ?: return
        showStandIn(path)
        lifecycleScope.launch {
            // Only a "no artwork" answer from the player is remembered; a failed
            // request is made again on the next poll.
            var failed = false
            val pair = withContext(Dispatchers.IO) {
                try {
                    // Shared with the notification; skipped if the track has
                    // changed before its turn (see Artwork).
                    val bytes = Artwork.get(client, path) { Session.state.path == path }
                        ?: return@withContext null
                    val full = decode(bytes) ?: return@withContext null
                    full to blur(full)
                } catch (e: Exception) {
                    failed = true
                    null
                } catch (e: OutOfMemoryError) {
                    // Oversized artwork leaves the placeholder instead of crashing.
                    null
                }
            }
            artAsked = ""
            // The track can have changed between request and answer.
            if (Session.state.path != path) return@launch
            if (failed) return@launch
            if (pair == null) {
                artMissing.add(path)
                views.cover.setImageDrawable(null)
                views.backdrop.setImageDrawable(null)
                views.coverPlaceholder.visibility = View.VISIBLE
                return@launch
            }
            artPath = path
            views.cover.setImageBitmap(pair.first)
            views.backdrop.setImageBitmap(pair.second)
            views.coverPlaceholder.visibility = View.GONE
        }
    }

    /** Decodes with a power-of-two sample size so the longer side fits the screen. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val limit = maxOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels,
        ).takeIf { it > 0 } ?: MAX_SIDE_FALLBACK
        var sample = 1
        val side = maxOf(bounds.outWidth, bounds.outHeight)
        while (side / sample > limit) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * The background: the artwork scaled to BLUR_SIDE and box-blurred
     * BLUR_PASSES times, in plain Kotlin (RenderEffect needs Android 12,
     * RenderScript is deprecated).
     */
    private fun blur(source: Bitmap): Bitmap {
        // Always copy: createScaledBitmap returns the source when the size
        // matches, and a BitmapFactory bitmap is not mutable.
        val small = Bitmap.createScaledBitmap(source, BLUR_SIDE, BLUR_SIDE, true)
            .copy(Bitmap.Config.ARGB_8888, true) ?: return source
        val pixels = IntArray(BLUR_SIDE * BLUR_SIDE)
        small.getPixels(pixels, 0, BLUR_SIDE, 0, 0, BLUR_SIDE, BLUR_SIDE)
        repeat(BLUR_PASSES) {
            boxBlur(pixels, BLUR_SIDE, BLUR_SIDE, BLUR_RADIUS)
        }
        small.setPixels(pixels, 0, BLUR_SIDE, 0, 0, BLUR_SIDE, BLUR_SIDE)
        return small
    }

    /** A separable box blur: the mean along each row, then along each column. */
    private fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        val temp = IntArray(pixels.size)
        blurAxis(pixels, temp, width, height, radius, horizontal = true)
        blurAxis(temp, pixels, width, height, radius, horizontal = false)
    }

    private fun blurAxis(
        source: IntArray,
        target: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ) {
        val outer = if (horizontal) height else width
        val inner = if (horizontal) width else height
        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (k in -radius..radius) {
                    val at = i + k
                    if (at < 0 || at >= inner) continue
                    val pixel = if (horizontal) source[o * width + at] else source[at * width + o]
                    r += (pixel shr 16) and 0xFF
                    g += (pixel shr 8) and 0xFF
                    b += pixel and 0xFF
                    n++
                }
                val value = 0xFF000000.toInt() or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                if (horizontal) {
                    target[o * width + i] = value
                } else {
                    target[i * width + o] = value
                }
            }
        }
    }
}
