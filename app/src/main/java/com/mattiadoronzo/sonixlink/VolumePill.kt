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
 * The volume pill shared by every screen: it handles the phone's volume keys,
 * throttles the levels sent to the player, and hides itself after a pause.
 */
class VolumePill(
    private val views: VolumePillBinding,
    /** Sends the level to the player. Called from the main thread. */
    private val send: (Int) -> Unit,
) {

    private companion object {
        /** One step per press, as on the player. */
        const val STEP = 1

        /** Minimum gap between two sends while dragging. */
        const val SEND_MS = 100L

        const val HIDE_MS = 1600L

        /**
         * How long after a change the player's reported level is ignored: until
         * the player applies it, the state still carries the old level.
         */
        const val HOLD_MS = 700L

        /**
         * The player's loud threshold: from here the icon is the high one, and
         * above it the number turns red.
         */
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
        // A touch outside the scale hides it, as on the player.
        views.root.setOnClickListener { hide() }

        views.volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                number(value)
                // Sent while dragging, not only on release.
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

    /** Tints the scale's fill with the player's accent. */
    fun accent() {
        views.volumeSeek.progressTintList = ColorStateList.valueOf(Session.accent)
    }

    /** Call in onDestroy: the pending callbacks hold a reference to the screen. */
    fun release() {
        clock.removeCallbacks(hideLater)
        clock.removeCallbacks(flushLater)
    }

    /**
     * Turns the phone's volume keys into player volume steps. Returns true for
     * either volume key, release included, or the system shows its own volume bar.
     */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val step = if (code == KeyEvent.KEYCODE_VOLUME_UP) STEP else -STEP
            // Steps from the pill's own level while it is shown: the poll
            // overwrites Session.state with levels not yet applied, which
            // would swallow quick presses.
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
     * Shows the player's reported level, except while a finger is on the scale,
     * a value is waiting to be sent, or within HOLD_MS of a change: the report
     * may still carry the old level.
     */
    fun follow(level: Int) {
        if (!showing || touching || pending >= 0) return
        if (SystemClock.uptimeMillis() < holdUntil) return
        views.volumeSeek.progress = level.coerceIn(0, 100)
        number(level)
    }

    private fun keepUp() {
        clock.removeCallbacks(hideLater)
        // No countdown while a finger is on the scale.
        if (!touching) {
            clock.postDelayed(hideLater, HIDE_MS)
        }
    }

    private fun number(level: Int) {
        val context = views.root.context
        views.volumeText.text = level.toString()
        // Red above HIGH, as on the player.
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
