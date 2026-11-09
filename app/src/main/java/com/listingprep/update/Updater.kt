package com.listingprep.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Updates the app from this project's GitHub releases, so you don't have to download each
 * build by hand.
 *
 * Safety checks before anything is installed:
 * - HTTPS only, to GitHub hosts only (also enforced by the network security config);
 *   redirects to any other host are refused. No token, no account, nothing about you is sent.
 * - The APK must match the SHA-256 published next to it in the release.
 * - The APK must be this app (same package name), a newer build, and signed with the SAME
 *   key as the installed app. A build signed by anyone else is rejected before Android is
 *   even asked (Android would refuse it too).
 */
object Updater {
    private const val REPO = "JackLongman12/marketplace-listing-prep"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private val HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private const val MAX_APK = 250L * 1024 * 1024

    data class Release(val build: Int, val apkUrl: String, val shaUrl: String, val size: Long, val notes: String = "")

    fun installedBuild(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun apkFile(context: Context) = File(context.cacheDir, "update/update.apk")

    /** Blocking. The newest release if it's newer than what's installed, else null. */
    fun check(context: Context): Release? {
        val json = JSONObject(String(fetch(URL(LATEST), 1 shl 20), Charsets.UTF_8))
        val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return null
        if (build <= installedBuild(context)) return null
        val assets = json.optJSONArray("assets") ?: return null
        var apk: Pair<String, Long>? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            val url = a.optString("browser_download_url")
            if (name == "ListingPrep-$build.apk") apk = url to a.optLong("size")
            if (name == "ListingPrep-$build.apk.sha256") sha = url
        }
        val (apkUrl, size) = apk ?: return null
        if (sha == null || size <= 0 || size > MAX_APK) return null
        // "What's new" from the release page, as plain text (untrusted: shortened, no markup).
        val notes = json.optString("body")
            .substringAfter("## What's new", json.optString("body"))
            .substringBefore("\n---")
            .lines().map { it.trim().removePrefix("### ").replace("**", "") }
            .filter { line -> line.isNotEmpty() && line.none { it.isISOControl() } }
            .joinToString("\n").take(2000)
        return Release(build, apkUrl, sha, size, notes)
    }

    /** Blocking. Downloads and fully verifies; returns the APK, or throws with a reason. */
    fun download(context: Context, release: Release, onProgress: (Float) -> Unit): File {
        val expected = String(fetch(URL(release.shaUrl), 4096), Charsets.UTF_8).trim().take(64).lowercase()
        require(expected.length == 64 && expected.all { it in "0123456789abcdef" }) { "Release has no valid checksum" }

        val file = apkFile(context).apply { parentFile?.mkdirs(); delete() }
        val digest = MessageDigest.getInstance("SHA-256")
        open(URL(release.apkUrl)).use { (conn, input) ->
            file.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_APK) throw IOException("Update is larger than expected")
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    onProgress(total.toFloat() / release.size)
                }
            }
            conn.disconnect()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            file.delete()
            throw IOException("Downloaded update didn't match its checksum, so it was deleted")
        }
        verifyIdentity(context, file, release.build)
        return file
    }

    /** Same package, newer build, same signing key as the app that's installed now. */
    private fun verifyIdentity(context: Context, apk: File, build: Int) {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
        val mine = pm.getPackageInfo(context.packageName, flags)
        fun certs(info: android.content.pm.PackageInfo?) =
            info?.signingInfo?.apkContentsSigners?.map { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }?.toSet().orEmpty()
        val ok = archive != null &&
            archive.packageName == context.packageName &&
            archive.longVersionCode == build.toLong() &&
            archive.longVersionCode > mine.longVersionCode &&
            certs(archive).isNotEmpty() && certs(archive) == certs(mine)
        if (!ok) {
            apk.delete()
            throw SecurityException("The update isn't signed by the same key as this app, so it was rejected")
        }
    }

    /** Hands the verified APK to Android. Without user action when Android allows it (after the first update). */
    /** base.dm: the baseline profile the build put inside the APK, in the form the installer reads. */
    private fun dexMetadata(apk: File): File? = runCatching {
        java.util.zip.ZipFile(apk).use { zip ->
            val prof = zip.getEntry("assets/dexopt/baseline.prof") ?: return null
            val profm = zip.getEntry("assets/dexopt/baseline.profm")
            val dm = File(apk.parentFile, "base.dm")
            java.util.zip.ZipOutputStream(dm.outputStream()).use { out ->
                out.putNextEntry(java.util.zip.ZipEntry("primary.prof"))
                zip.getInputStream(prof).use { it.copyTo(out) }
                out.closeEntry()
                if (profm != null) {
                    out.putNextEntry(java.util.zip.ZipEntry("primary.profm"))
                    zip.getInputStream(profm).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            dm
        }
    }.getOrNull()

    fun install(context: Context, apk: File, withProfile: Boolean = true) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                session.fsync(out)
            }
            // Android pre-compiles the app's hot code (lists, scrolling) at install time when the
            // install includes this profile, like Play Store installs do. Smooth from the first launch.
            if (withProfile) dexMetadata(apk)?.let { dm ->
                session.openWrite("base.dm", 0, dm.length()).use { out ->
                    dm.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                dm.delete()
            }
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java).putExtra(UpdateReceiver.EXTRA_WITH_PROFILE, withProfile),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, // the installer adds the result
            )
            session.commit(callback.intentSender)
        }
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    // ---- strict HTTPS fetch -------------------------------------------------

    private class Opened(val conn: HttpsURLConnection, val input: InputStream) : AutoCloseable {
        operator fun component1() = conn
        operator fun component2() = input
        override fun close() { input.close(); conn.disconnect() }
    }

    private fun open(start: URL): Opened {
        var url = start
        repeat(5) {
            require(url.protocol == "https" && url.host in HOSTS) { "Blocked host ${url.host}" }
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
                setRequestProperty("User-Agent", "ListingPrep-updater")
            }
            when (val code = conn.responseCode) {
                200 -> return Opened(conn, conn.inputStream)
                301, 302, 303, 307, 308 -> {
                    val next = URL(url, conn.getHeaderField("Location") ?: throw IOException("Redirect without location"))
                    conn.disconnect()
                    url = next
                }
                else -> {
                    conn.disconnect()
                    throw IOException("GitHub answered HTTP $code")
                }
            }
        }
        throw IOException("Too many redirects")
    }

    private fun fetch(url: URL, max: Int): ByteArray = open(url).use { (_, input) ->
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("Reply too large")
        }
        out.toByteArray()
    }
}
