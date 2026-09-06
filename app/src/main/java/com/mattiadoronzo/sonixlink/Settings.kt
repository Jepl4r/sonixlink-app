package com.mattiadoronzo.sonixlink

import android.content.Context

/**
 * What is worth remembering between one launch and the next: the last player
 * that answered, so the second time the app goes straight in.
 */
object Settings {

    private const val FILE = "sonixlink"
    private const val KEY_HOST = "last_host"
    private const val KEY_PORT = "last_port"
    private const val KEY_DB_HOST = "db_host"
    private const val KEY_DB_MTIME = "db_mtime"
    private const val KEY_COVERS_HOST = "covers_host"
    private const val KEY_COVERS_MTIME = "covers_mtime"
    private const val KEY_ACCENT = "accent"

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
     * Whose index is on the phone, and from when. This is how a rescan on the
     * player is noticed and the index fetched again on its own, rather than
     * showing a stale library until someone presses refresh.
     */
    fun databaseStamp(context: Context): Pair<String, Long> {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return preferences.getString(KEY_DB_HOST, "").orEmpty() to preferences.getLong(KEY_DB_MTIME, 0)
    }

    fun rememberDatabaseStamp(context: Context, host: String, mtime: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_DB_HOST, host)
            .putLong(KEY_DB_MTIME, mtime)
            .apply()
    }

    fun coversStamp(context: Context): Pair<String, Long> {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return preferences.getString(KEY_COVERS_HOST, "").orEmpty() to preferences.getLong(KEY_COVERS_MTIME, 0)
    }

    fun rememberCoversStamp(context: Context, host: String, mtime: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_COVERS_HOST, host)
            .putLong(KEY_COVERS_MTIME, mtime)
            .apply()
    }

    /** The accent last seen, so the app does not start blue and then change. */
    fun accent(context: Context): String =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_ACCENT, "").orEmpty()

    fun rememberAccent(context: Context, accent: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCENT, accent)
            .apply()
    }

    fun forgetHost(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(KEY_HOST)
            .remove(KEY_PORT)
            .apply()
    }
}
