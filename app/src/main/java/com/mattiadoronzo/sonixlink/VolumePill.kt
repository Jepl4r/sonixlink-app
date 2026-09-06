package com.mattiadoronzo.sonixlink

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import com.mattiadoronzo.sonixlink.databinding.VolumePillBinding

/**
 * The volume pill, with everything it needs inside it: the phone's hardware
 * keys, the gap between one send and the next, and the countdown that takes it
 * away.
 *
 * One class rather than four copies: only the main screen and the now-playing
 * screen had one, each its own, and in the queue and the search the volume keys
 * went to the phone's ringer -- where no sound comes out, since the player is
 * what is playing.
 */
class VolumePill(
    private val views: VolumePillBinding,
    /** Sends the level to the player. Called from the main thread. */
    private val send: (Int) -> Unit,
) {

    private companion object {
        /** One step per press, as on the player. */
        const val STEP = 1

        /** Dragging produces dozens a second: one goes out every so often. */
        const val SEND_MS = 100L

        const val HIDE_MS = 1600L

        /**
         * The player takes a poll to apply it: until then the state coming back
         * is the old one, and it must not drag the knob backwards.
         */
        const val HOLD_MS = 700L

        /** Where the player changes icon and turns the number red. */
        const val HIGH = 55
    }

    private val clock = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { hide() }
    private val flushLater = Runnable { flush() }

    private var touching = false
    private var pending = -1
    private var sentAt = 0L
    private var holdUntil = 0L

    val showing: Boolean
        get() = views.root.visibility == View.VISIBLE

    init {
        // A touch outside sends it away, like the player's own veil.
        views.root.setOnClickListener { hide() }

        views.volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                number(value)
                // The player follows the finger, not only where it lifts.
                queue(value)
                keepUp()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                touching = true
                clock.removeCallbacks(hideLater)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                touching = false
                queue(bar.progress, immediate = true)
                keepUp()
            }
        })

        accent()
    }

    /** The scale's fill carries the accent chosen on the player. */
    fun accent() {
        views.volumeSeek.progressTintList = ColorStateList.valueOf(Session.accent)
    }

    /** Call in onDestroy: the two delayed callbacks hold on to the screen. */
    fun release() {
        clock.removeCallbacks(hideLater)
        clock.removeCallbacks(flushLater)
    }

    /**
     * The phone's volume keys drive the player. Returns true when the key was one
     * of the two: the release is consumed as well, or the phone shows its own
     * volume bar over ours.
     */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = if (code == KeyEvent.KEYCODE_VOLUME_UP) STEP else -STEP
            // The starting level is the pill's own while it is up:
            // Session.state is rewritten by the poll every second, and five
            // quick presses would come out as two or three steps.
            val from = if (showing) views.volumeSeek.progress else Session.state.volume
            val target = (from + step).coerceIn(0, 100)
            show(target)
            queue(target)
        }
        return true
    }

    /** Shows it at the given level and restarts the countdown. */
    fun show(level: Int) {
        views.volumeSeek.progress = level.coerceIn(0, 100)
        number(level)
        views.root.visibility = View.VISIBLE
        keepUp()
    }

    fun hide() {
        views.root.visibility = View.GONE
    }

    /**
     * Volume changed on the player shows here too, but not while a finger is on
     * the scale or a value is waiting to go out: the player would answer with
     * the old one and the knob would jump back.
     */
    fun follow(level: Int) {
        if (!showing || touching || pending >= 0) return
        if (SystemClock.uptimeMillis() < holdUntil) return
        views.volumeSeek.progress = level.coerceIn(0, 100)
        number(level)
    }

    // -----------------------------------------------------------------------

    private fun keepUp() {
        clock.removeCallbacks(hideLater)
        // Not while a finger is on the scale: the pill must not vanish from
        // under whoever is using it.
        if (!touching) {
            clock.postDelayed(hideLater, HIDE_MS)
        }
    }

    private fun number(level: Int) {
        val context = views.root.context
        views.volumeText.text = level.toString()
        // Past the point where every step is loud the number turns red, which
        // is what the player does.
        views.volumeText.setTextColor(
            ContextCompat.getColor(
                context,
                if (level > HIGH) R.color.volume_warn else R.color.text_primary,
            )
        )
        views.volumeIcon.setImageResource(
            when {
                level <= 0 -> R.drawable.ic_volume_mute
                level < HIGH -> R.drawable.ic_volume_low
                else -> R.drawable.ic_volume_high
            }
        )
    }

    private fun queue(value: Int, immediate: Boolean = false) {
        pending = value.coerceIn(0, 100)
        Session.state = Session.state.copy(volume = pending)

        val now = SystemClock.uptimeMillis()
        holdUntil = now + HOLD_MS
        val since = now - sentAt
        clock.removeCallbacks(flushLater)
        if (immediate || since >= SEND_MS) {
            flush()
        } else {
            clock.postDelayed(flushLater, SEND_MS - since)
        }
    }

    private fun flush() {
        val value = pending
        if (value < 0) return
        pending = -1
        sentAt = SystemClock.uptimeMillis()
        send(value)
    }
}
