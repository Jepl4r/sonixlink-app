package com.mattiadoronzo.sonixlink

import android.os.SystemClock

/**
 * A value the app has set ahead of the player, held until the player agrees.
 *
 * A read already in flight when a command is sent still carries the old value.
 * The value set here wins over the player's until the player reports the same,
 * or until [holdMs] milliseconds pass, after which the player's value wins.
 */
class Held<T>(private val holdMs: Long = 3000) {
    private var value: T? = null
    private var until = 0L

    fun set(wanted: T) {
        value = wanted
        until = SystemClock.uptimeMillis() + holdMs
    }

    /** What to show, given what the player says. */
    fun resolve(fromPlayer: T): T {
        val wanted = value ?: return fromPlayer
        if (wanted == fromPlayer || SystemClock.uptimeMillis() > until) {
            value = null
            return fromPlayer
        }
        return wanted
    }
}
