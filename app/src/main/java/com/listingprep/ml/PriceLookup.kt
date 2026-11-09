package com.listingprep.ml

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Second-hand price search with Google's AI search: Gemini answering with live Google
 * Search results ("grounding"), asked for the used price of the item where you live.
 * Runs only when you tap "Find typical second-hand price".
 *
 * What leaves the phone: the item's title, its condition, and your location as set in
 * Settings (city / area / country). No photos, description, notes or account details.
 * Safeguards:
 * - Your Gemini API key is stored encrypted with a hardware-backed Android Keystore key and
 *   sent in a request header (never in a URL, so it can't end up in logs or history).
 * - HTTPS to generativelanguage.googleapis.com only (the network security config blocks other
 *   hosts), redirects refused, timeouts, replies capped at 1 MB and parsed as plain data.
 * - The answer is treated as untrusted text: numbers are validated, text is shortened, and
 *   nothing is filled in until you tap "Use".
 */
object PriceLookup {
    private const val HOST = "generativelanguage.googleapis.com"
    /** Google retires model versions; this alias always points at the current Flash model. */
    private const val PREFERRED = "gemini-flash-latest"
    @Volatile private var model: String? = null

    data class Estimate(
        val summary: String,
        val price: Double?,
        val low: Double?,
        val high: Double?,
        val currency: String?,
        val sources: List<String>,
    )

    class BadKey : IOException("Google rejected the API key")

    /** Blocking. */
    fun estimate(apiKey: String, title: String, condition: String, place: String, currency: String): Estimate {
        val item = clean(title, 100)
        require(item.length >= 3) { "Add a title first; it's what the price search looks for" }
        val prompt = """
            Search the web for what this item typically sells for second-hand (used) in ${clean(place, 120)}.
            Item: $item
            Condition: ${clean(condition, 30)}
            Prefer recent sold or asking prices on second-hand marketplaces used in that area (for example Facebook Marketplace, Carousell, eBay, Gumtree, Craigslist, Kijiji, Vinted or local equivalents). Ignore new/retail prices unless nothing used is found.
            Answer in at most two short sentences, then end with exactly one line in this format:
            PRICE: <typical used price as a plain number> | LOW: <number> | HIGH: <number> | CURRENCY: <${clean(currency, 3).ifBlank { "ISO code" }} or the ISO code of the prices you found>
            If you can't find reliable prices, end with: PRICE: unknown
        """.trimIndent()

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", JSONObject().put("temperature", 0.2))
        val json = try {
            post(URL("https://$HOST/v1beta/models/${model ?: PREFERRED}:generateContent"), apiKey.trim(), body.toString())
        } catch (e: HttpError) {
            if (e.code != 404) throw e
            // That model name is gone (Google renamed or retired it): ask which Flash models this key can use.
            model = pickModel(apiKey.trim()) ?: throw IOException("No suitable Gemini model is available for this key")
            post(URL("https://$HOST/v1beta/models/$model:generateContent"), apiKey.trim(), body.toString())
        }

        val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: throw IOException("No answer from Google")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        val text = (0 until (parts?.length() ?: 0)).joinToString("") { parts!!.optJSONObject(it)?.optString("text").orEmpty() }
        val sources = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
            (0 until chunks.length()).mapNotNull { chunks.optJSONObject(it)?.optJSONObject("web")?.optString("title") }
                .map { clean(it, 60) }.filter { it.isNotBlank() }.distinct().take(5)
        }.orEmpty()

        val line = Regex("""PRICE:\s*([^\n]+)""", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.groupValues?.get(1).orEmpty()
        fun field(name: String) = Regex("""$name:\s*([0-9][0-9.,]*)""", RegexOption.IGNORE_CASE).find("PRICE: $line")
            ?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 && it < 100_000_000 }
        val cur = Regex("""CURRENCY:\s*([A-Za-z]{3})""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.uppercase()
        val summary = clean(text.substringBefore("PRICE:").ifBlank { text }, 400)
        return Estimate(summary, field("PRICE"), field("LOW"), field("HIGH"), cur, sources)
    }

    /** Newest general "flash" model that can answer with Google Search. */
    private fun pickModel(key: String): String? {
        val json = get(URL("https://$HOST/v1beta/models?pageSize=200"), key)
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

    fun round(v: Double): String = if (v >= 20) Math.round(v).toString() else "%.2f".format(v)

    /** Untrusted text: printable characters only, single spaces, short. */
    private fun clean(v: String, max: Int) =
        v.filter { !it.isISOControl() || it == '\n' }.replace(Regex("[\\t ]+"), " ").replace("**", "").trim().take(max)

    private class HttpError(val code: Int) : IOException("Google answered HTTP $code")

    private fun get(url: URL, key: String): JSONObject {
        require(url.protocol == "https" && url.host == HOST) { "Blocked host ${url.host}" }
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            when (val code = conn.responseCode) {
                200 -> Unit
                400, 401, 403 -> throw BadKey()
                else -> throw HttpError(code)
            }
            return JSONObject(readCapped(conn))
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(conn: HttpsURLConnection): String {
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
        return out.toString("UTF-8")
    }

    private fun post(url: URL, key: String, body: String): JSONObject {
        require(url.protocol == "https" && url.host == HOST) { "Blocked host ${url.host}" }
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000 // searching + answering can take a while
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            when (val code = conn.responseCode) {
                200 -> Unit
                400, 401, 403 -> throw BadKey()
                429 -> throw IOException("Google's free limit was reached; try again later")
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
