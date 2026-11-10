package com.listingprep

import android.content.Context
import android.os.Build
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection
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

    /**
     * The crash relay (a Cloudflare Worker) that files reports on the public issue board.
     * Blank = automatic sending is off in this build.
     */
    const val RELAY = ""
    const val ISSUES_PAGE = "https://github.com/JackLongman12/marketplace-listing-prep/issues?q=label%3Acrash"

    val canSend get() = RELAY.isNotBlank()

    enum class Sent { YES, NO_RETRY_LATER, REJECTED }

    /**
     * Sends [report] (exactly the text shown in the crash dialog, nothing added) to the relay.
     * HTTPS only, no redirects, short timeouts. Blocking.
     */
    fun send(report: String): Sent {
        if (!canSend) return Sent.NO_RETRY_LATER
        return try {
            val conn = (URL(RELAY).openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                when (conn.responseCode) {
                    in 200..299 -> Sent.YES
                    400, 404, 413 -> Sent.REJECTED // never going to be accepted: don't keep retrying
                    else -> Sent.NO_RETRY_LATER
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Sent.NO_RETRY_LATER
        }
    }
}
