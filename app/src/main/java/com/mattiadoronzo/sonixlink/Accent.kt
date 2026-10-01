package com.mattiadoronzo.sonixlink

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.ImageButton
import androidx.core.content.ContextCompat

/**
 * The player's accent, applied by hand.
 *
 * The theme's `colorPrimary` and `@color/accent` are a fixed Adwaita blue; the
 * real accent arrives from the player on each connection. Anything that must
 * follow it is tinted through here, since theme tints never change.
 */
object Accent {

    fun tint(): ColorStateList = ColorStateList.valueOf(Session.accent)

    /** On or off, for the icons that have two states. */
    fun onOff(context: Context, on: Boolean): ColorStateList = ColorStateList.valueOf(
        if (on) Session.accent else ContextCompat.getColor(context, R.color.text_secondary)
    )

    /**
     * A TabLayout's icons and labels: the selected one in the accent, the rest
     * in secondary grey. Replaces Material's default `colorPrimary` tint.
     */
    fun tabColors(context: Context): ColorStateList {
        val states = arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf())
        val colors = intArrayOf(Session.accent, ContextCompat.getColor(context, R.color.text_secondary))
        return ColorStateList(states, colors)
    }

    /**
     * The play button inside its circle, as on the player: a white disc with the
     * accent glyph over it (`player.c`, `apply_playback_status`).
     */
    fun circleButton(button: ImageButton, diameterDp: Int) {
        val density = button.resources.displayMetrics.density
        val size = (diameterDp * density).toInt()
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFFFFFFF.toInt())
            setSize(size, size)
        }
        button.imageTintList = ColorStateList.valueOf(Session.accent)
    }
}
