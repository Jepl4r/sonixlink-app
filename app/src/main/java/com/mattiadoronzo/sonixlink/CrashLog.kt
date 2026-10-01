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
 * An uncaught exception's stack trace is written to a file in filesDir, so the
 * next launch can show it and let it be copied without logcat.
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
                // A failed write must not hide the original crash.
            }
            // Hand over to the previous handler so the process still dies as usual.
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
