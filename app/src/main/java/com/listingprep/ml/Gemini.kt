package com.listingprep.ml

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Minimal, locked-down client for Google's Gemini API, shared by the price search and the
 * optional cloud writing.
 * - HTTPS to generativelanguage.googleapis.com only (also enforced by the network security
 *   config); redirects refused; timeouts; replies capped at 1 MB and parsed as plain data.
 * - The API key goes in a request header, never in a URL.
 * - Model names change: the "latest Flash" alias is used, and if Google ever drops it the
 *   newest Flash model available to the key is picked.
 */
object Gemini {
    private const val HOST = "generativelanguage.googleapis.com"
    private const val PREFERRED = "gemini-flash-latest"
    @Volatile private var model: String? = null

    class BadKey : IOException("Google rejected the API key")
    class RateLimited : IOException("Google's free limit was reached for now")
    class HttpError(val code: Int) : IOException("Google answered HTTP $code")

    data class Answer(val text: String, val sources: List<String>)

    /** A photo as an inline JPEG part (resized so the upload stays small). */
    fun imagePart(bmp: Bitmap, maxDim: Int = 1024): JSONObject {
        val s = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out) // a fresh JPEG: no EXIF, no location
        if (scaled !== bmp) scaled.recycle()
        return JSONObject().put(
            "inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)),
        )
    }

    fun textPart(text: String): JSONObject = JSONObject().put("text", text)

    /** Blocking. [search] lets Gemini look things up on Google Search. */
    fun generate(key: String, parts: List<JSONObject>, search: Boolean, temperature: Double): Answer {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray(parts))))
            .put("generationConfig", JSONObject().put("temperature", temperature))
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val k = key.trim()
        val json = try {
            post(URL("https://$HOST/v1beta/models/${model ?: PREFERRED}:generateContent"), k, body.toString())
        } catch (e: HttpError) {
            if (e.code != 404) throw e
            model = pickModel(k) ?: throw IOException("No suitable Gemini model is available for this key")
            post(URL("https://$HOST/v1beta/models/$model:generateContent"), k, body.toString())
        }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: throw IOException("No answer from Google")
        val content = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = (0 until (content?.length() ?: 0)).joinToString("") { content!!.optJSONObject(it)?.optString("text").orEmpty() }
        val sources = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
            (0 until chunks.length()).mapNotNull { chunks.optJSONObject(it)?.optJSONObject("web")?.optString("title") }
                .map { t -> t.filter { !it.isISOControl() }.trim().take(60) }.filter { it.isNotBlank() }.distinct().take(5)
        }.orEmpty()
        return Answer(text.take(20_000), sources)
    }

    /** Newest general "flash" model that supports generateContent. */
    private fun pickModel(key: String): String? {
        val json = request(URL("https://$HOST/v1beta/models?pageSize=200"), key, null)
        val models = json.optJSONArray("models") ?: return null
        val names = (0 until models.length()).mapNotNull { i ->
            val m = models.optJSONObject(i) ?: return@mapNotNull null
            val methods = m.optJSONArray("supportedGenerationMethods")
            val canGenerate = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            m.optString("name").removePrefix("models/").takeIf { canGenerate }
        }.filter { n ->
            n.matches(Regex("[a-z0-9.-]+")) && "flash" in n &&
                listOf("lite", "image", "tts", "audio", "live", "thinking", "embedding", "exp").none { it in n }
        }
        return names.firstOrNull { it.endsWith("latest") }
            ?: names.maxByOrNull { n -> Regex("""[0-9]+(\.[0-9]+)?""").find(n)?.value?.toDoubleOrNull() ?: 0.0 }
    }

    private fun post(url: URL, key: String, body: String) = request(url, key, body)

    private fun request(url: URL, key: String, body: String?): JSONObject {
        require(url.protocol == "https" && url.host == HOST) { "Blocked host ${url.host}" }
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = 15_000
            readTimeout = 90_000 // searching / looking at photos can take a while
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", key)
            if (body != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            when (val code = conn.responseCode) {
                200 -> Unit
                400 -> {
                    // 400 is also "bad key"; anything else is a request problem worth reporting.
                    val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText().take(2000) } }.getOrNull().orEmpty()
                    if ("API_KEY" in err || "API key" in err) throw BadKey() else throw HttpError(400)
                }
                401, 403 -> throw BadKey()
                429 -> throw RateLimited()
                else -> throw HttpError(code)
            }
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 1 shl 20) throw IOException("Reply too large")
                }
            }
            return JSONObject(out.toString("UTF-8"))
        } finally {
            conn.disconnect()
        }
    }
}
