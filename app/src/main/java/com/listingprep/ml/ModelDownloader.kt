package com.listingprep.ml

import android.app.DownloadManager
import android.content.Context
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Downloads Gemma 3n from Hugging Face. The transfer itself runs in [ModelDownloadService];
 * this object holds the network and file steps.
 *
 * Why not Android's DownloadManager: Hugging Face hands out a signed CDN link that expires
 * after about an hour. DownloadManager can only retry that same link, so once a 4.4 GB
 * download hiccups after the link expires it sits on "retrying" forever. Here every retry
 * asks Hugging Face for a fresh link and resumes from the bytes already on disk.
 *
 * Security:
 * - The token is only sent to huggingface.co (exact host) to obtain the signed link; the CDN
 *   request carries no token. Every hop must be HTTPS and a Hugging Face host.
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

    /** Hugging Face itself refused (bad token, license not accepted, file gone): retrying won't help. */
    class Refused(val code: Int) : IOException("HTTP $code")

    /** The CDN rejected the signed link (usually expired): get a new one and carry on. */
    class LinkExpired(code: Int) : IOException("Download link rejected (HTTP $code)")

    enum class Finish { OK, INCOMPLETE, BAD_CHECKSUM }

    fun freeBytes(context: Context): Long = StatFs(ModelFiles.dir(context).path).availableBytes

    fun partFile(context: Context) = File(ModelFiles.dir(context), PART_NAME)

    /**
     * Follows Hugging Face's redirects by hand. The token goes only to huggingface.co;
     * the first hop to another host is the signed CDN link, returned without the token.
     * Blocking network call.
     */
    fun resolveSignedUrl(token: String): String {
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
                setRequestProperty("Authorization", "Bearer ${token.trim()}")
            }
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                        url = URL(url, location)
                    }
                    401, 403, 404 -> throw Refused(code)
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

    /**
     * Appends to [part] from the signed link, resuming with a Range request. Returns when the
     * server closes the stream; throws on any network error so the caller can retry.
     */
    fun fetch(signedUrl: String, part: File, keepGoing: () -> Boolean, onProgress: (Long) -> Unit) {
        var url = URL(signedUrl)
        repeat(4) {
            require(url.protocol == "https" && isHuggingFaceHost(url.host)) { "Unexpected download host ${url.host}" }
            val have = part.length()
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept-Encoding", "identity")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                val append = when (code) {
                    in 300..399 -> {
                        url = URL(url, conn.getHeaderField("Location") ?: throw IOException("Redirect without a location"))
                        return@repeat
                    }
                    206 -> {
                        val start = conn.getHeaderField("Content-Range")
                            ?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()
                        if (start != have) {
                            part.delete() // server resumed from somewhere else; start clean
                            throw IOException("Server resumed at the wrong place")
                        }
                        true
                    }
                    200 -> false // no resume support on this hop: start over
                    416 -> if (have >= SIZE_BYTES) return else { part.delete(); throw IOException("Bad resume point") }
                    400, 401, 403, 410 -> throw LinkExpired(code)
                    else -> throw IOException("HTTP $code from download server")
                }
                var done = if (append) have else 0L
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (!keepGoing()) throw IOException("Cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done > SIZE_BYTES) { part.delete(); throw IOException("Server sent more data than expected") }
                            onProgress(done)
                        }
                    }
                }
                return
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects from the download server")
    }

    fun deletePart(context: Context) {
        partFile(context).delete()
    }

    /** Versions up to build 14 used DownloadManager; drop any entry they left behind. */
    fun removeLegacyDownload(context: Context, id: Long) {
        if (id >= 0) runCatching { context.getSystemService(DownloadManager::class.java).remove(id) }
    }

    /** Verifies the finished download and moves it into place. Blocking (hashes 4.4 GB). */
    fun finish(context: Context): Finish {
        val dir = ModelFiles.dir(context)
        val part = partFile(context)
        val result = when {
            !part.exists() || part.length() != SIZE_BYTES -> Finish.INCOMPLETE
            !isOfficialModel(part) -> Finish.BAD_CHECKSUM
            else -> {
                dir.listFiles()?.filter { it.name.endsWith(".task") }?.forEach { it.delete() }
                val installed = File(dir, FILE_NAME)
                if (part.renameTo(installed)) {
                    ModelFiles.markVerified(context, installed)
                    Finish.OK
                } else Finish.INCOMPLETE
            }
        }
        part.delete()
        return result
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
}
