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
 * The artwork here is the real thing, not the thumbnail: the player sends it
 * whole and untouched (`/api/art`), and the phone, which has cycles to spare,
 * decodes it. The background is the same image, blurred, as on the player.
 */
class NowPlayingActivity : AppCompatActivity() {

    companion object {
        private const val POLL_MS = 1000L

        /**
         * Past this the decoder scales down. The measure is the screen's own:
         * artwork wider than the display is megabytes of pixels nobody will see.
         */
        private const val MAX_SIDE_FALLBACK = 1080

        /**
         * The background shrinks to here and is then really blurred. At
         * twenty-four pixels the squares showed: small enough to cost nothing,
         * but stretched full screen that is not a blur, it is a mosaic. A
         * hundred and twenty-eight plus three box passes give what the player
         * puts behind its artwork.
         */
        private const val BLUR_SIDE = 128
        private const val BLUR_PASSES = 3
        private const val BLUR_RADIUS = 6
    }

    private lateinit var views: ActivityNowPlayingBinding

    /** The track whose artwork is already on screen. */
    private var artPath = ""

    /** The one with a request in flight, so as not to make two. */
    private var artAsked = ""

    /** Tracks that have no artwork: not asked for once a second. */
    private val artMissing = HashSet<String>()

    private var seeking = false
    private var seekHoldUntil = 0L

    /** The volume pill, the same one every other page has. */
    private lateinit var volume: VolumePill

    /** Kept in a field: the object itself is what removes itself. */
    private val accentWatch = { applyAccent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(views.root)

        // Before the early return: the volume keys arrive regardless.
        volume = VolumePill(views.volume) { level -> send { it.setVolume(level) } }

        if (Session.client == null) {
            finish()
            return
        }

        // Marquee: they scroll themselves when they do not fit, as on the player.
        views.trackTitle.isSelected = true
        views.trackArtist.isSelected = true

        applyAccent()
        wireControls()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    refresh()
                    delay(POLL_MS)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (dismissing) {
            // The sheet has already gone down behind the finger: the window
            // animation would put it back at the top to send it down again, and
            // the jump would show.
            overridePendingTransition(0, 0)
        } else {
            // It goes back down the way it came up.
            overridePendingTransition(R.anim.stay, R.anim.slide_down)
        }
    }

    // -----------------------------------------------------------------------
    // Dragging down
    // -----------------------------------------------------------------------

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragBlocked = false
    private var dismissing = false
    private val slop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }

    /**
     * A finger going down takes the screen with it, and past a fifth of the
     * height closes it. Here rather than on a listener at the root because the
     * children take the touches: from here they are all seen, before them.
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                dragging = false
                // Something under the finger drags on its own, or the volume
                // pill is up: those come first.
                dragBlocked = dismissing ||
                    volume.showing ||
                    over(views.progressSeek, event)
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (!dragging && !dragBlocked) {
                    val down = event.rawY - downY
                    if (down > slop && down > kotlin.math.abs(event.rawX - downX)) {
                        dragging = true
                        // The children already have the touch: without a cancel
                        // the button under the finger stays pressed all the way
                        // down and fires on release.
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

    // -----------------------------------------------------------------------

    private fun applyAccent() {
        val accent = Session.accent
        Accent.circleButton(views.playButton, 64)
        views.progressSeek.progressTintList = android.content.res.ColorStateList.valueOf(accent)
        volume.accent()

        // The player's knob: round, filled with the accent, with the white ring
        // around it.
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

    /**
     * The phone's keys drive the player from this screen too: without this they
     * turned up the ringer and the player stayed where it was.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        volume.onKey(event) || super.dispatchKeyEvent(event)

    private fun wireControls() {
        views.playButton.setOnClickListener { send { it.toggle() } }
        views.prevButton.setOnClickListener { send { it.previous() } }
        views.nextButton.setOnClickListener { send { it.next() } }
        views.modeButton.setOnClickListener {
            val next = Session.state.mode.next()
            Session.state = Session.state.copy(mode = next)
            showMode(next)
            send { it.setMode(next) }
        }
        views.favouriteButton.setOnClickListener {
            val path = Session.state.path
            if (path.isEmpty()) return@setOnClickListener
            val starred = !Session.state.favourite
            Session.state = Session.state.copy(favourite = starred)
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
                // The player takes a moment to actually move: until then the bar
                // stays where the finger left it.
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
                    // The next poll will say how it really went.
                }
            }
            refresh()
        }
    }

    // -----------------------------------------------------------------------

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
        // The accent can change while this screen is in front: the other one's
        // poll is stopped, so this one picks it up.
        if (state.accent.isNotEmpty()) {
            val color = Session.parseAccent(state.accent)
            if (color != Session.accent) {
                // Recolouring is the watcher's job: this screen is the only one
                // polling while it is in front, but there are others beneath.
                Session.accent = color
                val text = state.accent
                lifecycleScope.launch(Dispatchers.IO) { Settings.rememberAccent(this@NowPlayingActivity, text) }
            }
        }
        show(state)
        showArt(state.path)
    }

    private fun show(state: PlayerState) {
        setText(views.trackTitle, state.title.ifEmpty { state.path.substringAfterLast('/') })
        setText(
            views.trackArtist,
            listOf(state.artist, state.album).filter { it.isNotEmpty() }.joinToString(" — "),
        )

        views.playButton.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        showMode(state.mode)
        showFavourite(state.favourite)

        if (!seeking && android.os.SystemClock.uptimeMillis() >= seekHoldUntil) {
            views.progressSeek.progress = if (state.duration > 0) {
                (state.position.toLong() * 1000 / state.duration).toInt().coerceIn(0, 1000)
            } else {
                0
            }
            views.elapsed.text = clock(state.position.toLong())
        }
        views.total.text = clock(state.duration.toLong())

        volume.follow(state.volume)

        views.queuePosition.text = if (state.queuePosition >= 0 && state.queueCount > 0) {
            getString(R.string.queue_position, state.queuePosition + 1, state.queueCount)
        } else {
            ""
        }
    }

    /**
     * Writing into a TextView restarts the marquee from the beginning, even when
     * the text is the same as before. The poll put the title back once a second:
     * it scrolled for half a second and returned to the start, forever.
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

    // -----------------------------------------------------------------------
    // The artwork
    // -----------------------------------------------------------------------

    private fun showArt(path: String) {
        // `artPath` moves only when the image is really there: a request that
        // went wrong has to be worth making again next round, rather than leaving
        // that track blank for its whole length.
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
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) {
                try {
                    val bytes = client.artwork(path) ?: return@withContext null
                    val full = decode(bytes) ?: return@withContext null
                    full to blur(full)
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    // Huge artwork must not take the app with it: without it the
                    // icon remains, and the track plays all the same.
                    null
                }
            }
            artAsked = ""
            // The track can have changed between request and answer.
            if (Session.state.path != path) return@launch
            if (pair == null) {
                // The player has answered that there is none: do not ask again.
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

    /** Decoded down: 3000-pixel artwork is no use to a 1080-pixel screen. */
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
     * The background: the artwork scaled down and then run three times through a
     * box blur. On 128x128 that is a few tens of thousands of additions -- no
     * RenderEffect, which is Android 12 and up, and no RenderScript, which has
     * been deprecated for years.
     */
    private fun blur(source: Bitmap): Bitmap {
        // Always copy: createScaledBitmap returns the source when the size
        // matches, and what BitmapFactory hands back is not writable.
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

    /** A running mean along a row and then a column: two passes, not r*r. */
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
