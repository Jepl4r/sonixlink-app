package com.mattiadoronzo.sonixlink

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.widget.ImageButton
import androidx.core.content.ContextCompat

/**
 * The player's accent, applied by hand.
 *
 * In the theme, `colorPrimary` and `@color/accent` are the Adwaita blue, which
 * is only the starting value: the real accent is whatever the player says on
 * each connection. Everything that has to follow it goes through here, because
 * a tint left to the theme stays blue forever.
 */
object Accent {

    fun tint(): ColorStateList = ColorStateList.valueOf(Session.accent)

    /** On or off, for the icons that have two states. */
    fun onOff(context: Context, on: Boolean): ColorStateList = ColorStateList.valueOf(
        if (on) Session.accent else ContextCompat.getColor(context, R.color.text_secondary)
    )

    /**
     * A TabLayout's icons and labels: the selected one in the accent, the rest
     * in secondary grey. Without this Material tints the selected one with
     * `colorPrimary`, which is the theme's blue.
     */
    fun tabColors(context: Context): ColorStateList {
        val states = arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf())
        val colors = intArrayOf(Session.accent, ContextCompat.getColor(context, R.color.text_secondary))
        return ColorStateList(states, colors)
    }

    /**
     * The play button inside its circle, as on the player: a white disc with the
     * accent glyph over it, not the other way round. Play and pause are one
     * control in two states, and it is the glyph that carries the colour
     * (`player.c`, `apply_playback_status`); the disc changes only to stand out
     * from whatever is behind it.
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
