package com.listingprep.ml

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Cloud AI services that can write listings from photos. All optional, all with your own key. */
enum class Provider(
    val id: String,
    val label: String,
    val endpoint: String,
    val defaultModel: String,
    val keyPage: String,
    val keyHelp: String,
) {
    GEMINI(
        "gemini", "Google Gemini", "https://generativelanguage.googleapis.com", "gemini-flash-latest",
        "https://aistudio.google.com/apikey", "Free key from Google AI Studio (also used for the price search).",
    ),
    GITHUB(
        "github", "GitHub Models", "https://models.github.ai/inference/chat/completions", "openai/gpt-4.1-mini",
        "https://github.com/settings/personal-access-tokens/new",
        "Free with your GitHub account: create a fine-grained token with the \"Models: read\" permission.",
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/auto",
        "https://openrouter.ai/settings/keys", "One key for many models; pay per use, some free models.",
    ),
    GROQ(
        "groq", "Groq", "https://api.groq.com/openai/v1/chat/completions", "meta-llama/llama-4-scout-17b-16e-instruct",
        "https://console.groq.com/keys", "Generous free tier, very fast.",
    ),
    MISTRAL(
        "mistral", "Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest",
        "https://console.mistral.ai/api-keys", "Free experiment tier available.",
    );

    val host: String get() = URL(endpoint).host

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

/**
 * Talks to OpenAI-style chat APIs (GitHub Models, OpenRouter, Groq, Mistral).
 * Same safeguards as the Gemini client: HTTPS to the provider's fixed host only (also enforced
 * by the network security config), key in a header, no redirects, timeouts, 1 MB reply cap,
 * photos sent as fresh JPEGs without EXIF or location.
 */
object OpenAiCompatible {
    class BadKey : IOException("The provider rejected the API key")
    class RateLimited : IOException("The provider's limit was reached for now")
    class BadModel(model: String) : IOException("The provider doesn't offer the model \"$model\" (check the model name)")

    fun generate(provider: Provider, key: String, model: String, prompt: String, photos: List<Bitmap>, temperature: Double): String {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
        photos.forEach { bmp ->
            content.put(
                JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/jpeg;base64," + jpegBase64(bmp)),
                )
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("temperature", temperature)
            .put("max_tokens", 900)

        val url = URL(provider.endpoint)
        require(url.protocol == "https" && url.host == provider.host) { "Blocked host ${url.host}" }
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${key.trim()}")
            if (provider == Provider.OPENROUTER) setRequestProperty("X-Title", "Listing Prep")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            when (val code = conn.responseCode) {
                200 -> Unit
                401, 403 -> throw BadKey()
                404 -> throw BadModel(model)
                429 -> throw RateLimited()
                400 -> {
                    val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText().take(2000) } }.getOrNull().orEmpty()
                    if ("model" in err.lowercase()) throw BadModel(model) else throw IOException("The provider refused the request (HTTP 400)")
                }
                else -> throw IOException("${provider.label} answered HTTP $code")
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
            val json = JSONObject(out.toString("UTF-8"))
            val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw IOException("No answer")
            return message.optString("content").take(20_000)
        } finally {
            conn.disconnect()
        }
    }

    private fun jpegBase64(bmp: Bitmap, maxDim: Int = 1024): String {
        val s = maxDim.toFloat() / maxOf(bmp.width, bmp.height)
        val scaled = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
