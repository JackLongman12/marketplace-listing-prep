package com.listingprep

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a short report when the app crashes, on the phone only.
 *
 * Privacy: the report holds only what's needed to find the bug:
 * - app build, Android version and phone model (e.g. SM-S948B);
 * - the day it happened (no time);
 * - the error's type and the lines of code it went through.
 * Error *messages* are left out on purpose, because they can contain text from your listings,
 * file names, web addresses or keys. No photos, items, settings, accounts or identifiers.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText(report(app, error)) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The private-safe report for [error] (see the class comment for what it contains). */
    fun report(context: Context, error: Throwable): String {
        val build = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        return buildString {
            appendLine("Listing Prep build $build, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MODEL}")
            appendLine("Crash on ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}")
            append(trace(error))
        }.take(8_000)
    }

    /** Error types and code locations only; never the messages. */
    internal fun trace(error: Throwable): String = buildString {
        var e: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (e != null && depth < 5 && seen.add(e)) {
            appendLine()
            appendLine(if (depth == 0) e.javaClass.name else "Caused by ${e.javaClass.name}")
            e.stackTrace.take(25).forEach { f ->
                appendLine("  at ${f.className}.${f.methodName}(${f.fileName ?: "?"}:${f.lineNumber})")
            }
            e = e.cause
            depth++
        }
    }

    fun read(context: Context): String? {
        val text = file(context).takeIf { it.exists() }?.readText() ?: return null
        // Reports from older builds included error messages: drop them rather than show or share them.
        if (!text.contains("\nCrash on ")) { clear(context); return null }
        return text
    }

    fun clear(context: Context) {
        file(context).delete()
    }
}
