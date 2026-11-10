package com.listingprep

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a short report when the app crashes, on the phone only (nothing is sent anywhere).
 * Next time the app opens it offers to copy the report, so a bug can be fixed from it.
 * The report is the error and where in the code it happened; no photos or listing text.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, error, fatal = true, where = thread.name) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Also used for errors that were caught (fatal = false), so they can be reported too. */
    fun record(context: Context, error: Throwable, fatal: Boolean, where: String = "") {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val build = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        val text = buildString {
            appendLine("Listing Prep build $build, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MODEL}")
            appendLine("${if (fatal) "Crash" else "Error"} at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}${if (where.isNotBlank()) " on $where" else ""}")
            appendLine()
            append(trace.lineSequence().take(60).joinToString("\n"))
        }
        if (fatal || !file(context).exists()) file(context).writeText(text.take(12_000))
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
