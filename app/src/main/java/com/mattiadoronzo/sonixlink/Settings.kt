package com.mattiadoronzo.sonixlink

import android.content.Context

/**
 * Preferences kept between launches: the last player that answered, the stamps
 * of the downloaded index and covers, the accent, and the notification prompt.
 */
object Settings {

    private const val FILE = "sonixlink"
    private const val KEY_HOST = "last_host"
    private const val KEY_PORT = "last_port"
    private const val KEY_DB_OWNER = "db_owner"
    private const val KEY_DB_VERSION = "db_version"
    private const val KEY_COVERS_OWNER = "covers_owner"
    private const val KEY_COVERS_VERSION = "covers_version"
    private const val KEY_ACCENT = "accent"
    private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"

    fun lastHost(context: Context): Pair<String, Int>? {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val host = preferences.getString(KEY_HOST, null) ?: return null
        if (host.isBlank()) return null
        return host to preferences.getInt(KEY_PORT, PlayerClient.DEFAULT_PORT)
    }

    fun rememberHost(context: Context, host: String, port: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .apply()
    }

    /**
     * The owner and version of the index on the phone: the player's serial
     * (identical over Wi-Fi and Bluetooth) and the index's fingerprint. A
     * mismatch with the player's means the index must be fetched again.
     */
    fun databaseStamp(context: Context): Pair<String, String> {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return preferences.getString(KEY_DB_OWNER, "").orEmpty() to preferences.getString(KEY_DB_VERSION, "").orEmpty()
    }

    fun rememberDatabaseStamp(context: Context, owner: String, version: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_DB_OWNER, owner)
            .putString(KEY_DB_VERSION, version)
            .apply()
    }

    fun coversStamp(context: Context): Pair<String, String> {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return preferences.getString(KEY_COVERS_OWNER, "").orEmpty() to
            preferences.getString(KEY_COVERS_VERSION, "").orEmpty()
    }

    fun rememberCoversStamp(context: Context, owner: String, version: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_COVERS_OWNER, owner)
            .putString(KEY_COVERS_VERSION, version)
            .apply()
    }

    /** The accent last seen, applied at startup before the player reports one. */
    fun accent(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ACCENT, "").orEmpty()

    fun rememberAccent(context: Context, accent: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCENT, accent)
            .apply()
    }

    /** Whether the notification permission has been asked for already. */
    fun askedNotifications(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ASKED_NOTIFICATIONS, false)

    fun rememberAskedNotifications(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ASKED_NOTIFICATIONS, true)
            .apply()
    }

    fun forgetHost(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .apply()
    }
}
