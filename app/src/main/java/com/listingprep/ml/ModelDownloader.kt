package com.listingprep.ml

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Downloads Gemma 3n from Hugging Face with Android's DownloadManager (background,
 * resumable, progress notification).
 *
 * Security:
 * - The repo is gated, so a Hugging Face read token is needed. The token is only ever
 *   sent to huggingface.co by this class to obtain a short-lived signed CDN link; the
 *   DownloadManager (a separate system app with its own database) never sees it.
 * - The finished file is checked against Hugging Face's published SHA-256 before use.
 */
object ModelDownloader {
    const val MODEL_PAGE = "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview"
    const val TOKEN_PAGE = "https://huggingface.co/settings/tokens/new?tokenType=read"
    const val FILE_NAME = "gemma-3n-E4B-it-int4.task"
    const val SIZE_BYTES = 4_405_655_031L
    /** From huggingface.co/api/models/google/gemma-3n-E4B-it-litert-preview (LFS oid). */
    const val SHA256 = "2b8e9d04bf8c5c50346d248c5e24a7e65102251c94dee6f04d5dce5ce3e6ac4f"
    private const val URL_RESOLVE = "$MODEL_PAGE/resolve/main/$FILE_NAME"
    private const val PART_NAME = "$FILE_NAME.download"
    private const val TOKEN_HOST = "huggingface.co"

    class Refused(val code: Int) : IOException("HTTP $code")

    data class Progress(val status: Int, val downloaded: Long, val total: Long, val reason: Int)

    enum class Finish { OK, INCOMPLETE, BAD_CHECKSUM }

    fun freeBytes(context: Context): Long = StatFs(ModelFiles.dir(context).path).availableBytes

    /** Blocking network call: run off the main thread. */
    fun start(context: Context, token: String, wifiOnly: Boolean): Long {
        val signedUrl = resolveSignedUrl(token.trim())
        File(ModelFiles.dir(context), PART_NAME).delete()
        val request = DownloadManager.Request(Uri.parse(signedUrl))
            .setTitle("AI model for Listing Prep")
            .setDescription("Gemma 3n, about 4.4 GB")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(!wifiOnly)
            .setDestinationInExternalFilesDir(context, null, PART_NAME)
        return context.getSystemService(DownloadManager::class.java).enqueue(request)
    }

    /**
     * Follows Hugging Face's redirects by hand. The token goes only to huggingface.co;
     * the first hop to another host is the signed CDN link, returned without the token.
     */
    private fun resolveSignedUrl(token: String): String {
        var url = URL(URL_RESOLVE)
        repeat(6) {
            require(url.protocol == "https") { "Refusing non-HTTPS link" }
            if (url.host != TOKEN_HOST) {
                require(isHuggingFaceHost(url.host)) { "Unexpected download host ${url.host}" }
                return url.toString()
            }
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "HEAD"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                        url = URL(url, location)
                    }
                    401, 403 -> throw Refused(code)
                    404 -> throw Refused(404)
                    else -> throw IOException("Unexpected response from Hugging Face (HTTP $code)")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects from Hugging Face")
    }

    private fun isHuggingFaceHost(host: String) =
        host == "hf.co" || host.endsWith(".hf.co") || host.endsWith(".huggingface.co")

    fun query(context: Context, id: Long): Progress? {
        val dm = context.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            fun long(col: String) = c.getLong(c.getColumnIndexOrThrow(col))
            return Progress(
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloaded = long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                total = long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }
        return null
    }

    fun cancel(context: Context, id: Long) {
        context.getSystemService(DownloadManager::class.java).remove(id)
        File(ModelFiles.dir(context), PART_NAME).delete()
    }

    /** Verifies the finished download and moves it into place. Blocking (hashes 4.4 GB). */
    fun finish(context: Context): Finish {
        val dir = ModelFiles.dir(context)
        val part = File(dir, PART_NAME)
        if (!part.exists() || part.length() != SIZE_BYTES) {
            part.delete()
            return Finish.INCOMPLETE
        }
        if (!isOfficialModel(part)) {
            part.delete()
            return Finish.BAD_CHECKSUM
        }
        dir.listFiles()?.filter { it.name.endsWith(".task") }?.forEach { it.delete() }
        return if (part.renameTo(File(dir, FILE_NAME))) Finish.OK else Finish.INCOMPLETE
    }

    fun isOfficialModel(file: File): Boolean = sha256(file) == SHA256

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun refusedMessage(code: Int): String = when (code) {
        404 -> "The model file wasn't found on Hugging Face (HTTP 404). It may have moved; use Import model file instead."
        else -> "Hugging Face refused the download (HTTP $code). Open the model page, make sure you're signed " +
            "in and have accepted the Gemma license (approval can take a few minutes), and check the token " +
            "is a Read token from the same account."
    }

    fun failureMessage(reason: Int): String = when (reason) {
        401, 403, 404 -> refusedMessage(reason)
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough free space for the 4.4 GB model."
        DownloadManager.ERROR_CANNOT_RESUME -> "The download link expired before it could resume. Tap Download again."
        else -> "Model download failed (code $reason). Tap Download to try again."
    }

    fun pausedMessage(reason: Int): String = when (reason) {
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi…"
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a connection…"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection dropped, retrying…"
        else -> "Paused…"
    }
}
