package com.mattiadoronzo.sonixlink

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash report.
 *
 * An app that closes itself on a phone with no cable to a computer leaves
 * nothing readable behind: logcat lives on the developer's machine. Here the
 * trace goes to a file, and on the next launch the app shows it and lets it be
 * copied, so the line that blew up is known rather than guessed at.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val directory = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = StringWriter()
                PrintWriter(text).use { writer ->
                    val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    writer.println("SonixLink ${BuildConfig.VERSION_NAME} — $when_")
                    writer.println("thread: ${thread.name}")
                    writer.println("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    writer.println("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    writer.println()
                    error.printStackTrace(writer)
                }
                File(directory, FILE).writeText(text.toString())
            } catch (ignored: Throwable) {
                // A report that cannot be written still leaves the crash itself,
                // which beats swallowing both.
            }
            // A crash stays a crash: the system takes the process down as usual.
            previous?.uncaughtException(thread, error)
        }
    }

    fun pending(context: Context): String? {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}
