package com.listingprep.ml

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt

/**
 * Second-hand price search through eBay's official Browse API (used-condition listings in
 * your country's eBay marketplace). Runs only when you tap "Suggest a price".
 *
 * What leaves the phone: the item's title (as a search phrase) and which eBay country site to
 * search. No photos, description, notes, exact location or account details.
 * Safeguards:
 * - Your eBay developer keys are stored encrypted with a hardware-backed Android Keystore key;
 *   the short-lived access token is kept in memory only.
 * - HTTPS to api.ebay.com only (the network security config blocks every other host for this
 *   traffic), redirects refused, 15 s timeouts, replies capped at 1 MB and parsed as data.
 * - Prices are cleaned: wrong currency dropped, outliers trimmed, median reported.
 */
object PriceLookup {
    private const val HOST = "api.ebay.com"

    data class Estimate(
        val median: Double,
        val low: Double,
        val high: Double,
        val count: Int,
        val currency: String,
        val site: String,
    )

    class BadKeys : IOException("eBay rejected the keys")

    /** eBay country sites the Browse API serves, by ISO region; others fall back to eBay US. */
    private val SITES = mapOf(
        "US" to "EBAY_US", "GB" to "EBAY_GB", "AU" to "EBAY_AU", "CA" to "EBAY_CA", "DE" to "EBAY_DE",
        "FR" to "EBAY_FR", "IT" to "EBAY_IT", "ES" to "EBAY_ES", "NL" to "EBAY_NL", "BE" to "EBAY_BE",
        "AT" to "EBAY_AT", "CH" to "EBAY_CH", "IE" to "EBAY_IE", "PL" to "EBAY_PL", "HK" to "EBAY_HK",
        "MO" to "EBAY_HK", "SG" to "EBAY_SG", "MY" to "EBAY_MY", "PH" to "EBAY_PH", "IN" to "EBAY_IN",
        "TW" to "EBAY_TW",
    )

    fun siteFor(region: String) = SITES[region] ?: "EBAY_US"

    @Volatile private var token: Pair<String, Long>? = null // value, expiry (ms)

    /** Blocking. Median used price for [query] on the eBay site for [region], or null if too few results. */
    fun estimate(clientId: String, clientSecret: String, query: String, region: String): Estimate? {
        val site = siteFor(region)
        val q = cleanQuery(query)
        require(q.length >= 3) { "Add a title first; it's what the price search looks for" }
        val access = accessToken(clientId, clientSecret)
        val url = URL(
            "https://$HOST/buy/browse/v1/item_summary/search?q=${URLEncoder.encode(q, "UTF-8")}" +
                "&filter=${URLEncoder.encode("conditions:{USED}", "UTF-8")}&limit=60"
        )
        val json = request(url, "GET", mapOf("Authorization" to "Bearer $access", "X-EBAY-C-MARKETPLACE-ID" to site), null)
        val items = json.optJSONArray("itemSummaries") ?: return null
        val byCurrency = (0 until items.length()).mapNotNull { i ->
            val price = items.optJSONObject(i)?.optJSONObject("price") ?: return@mapNotNull null
            val v = price.optString("value").toDoubleOrNull() ?: return@mapNotNull null
            val c = price.optString("currency").filter { it.isLetter() }.take(3)
            if (v > 0 && v < 10_000_000 && c.length == 3) c to v else null
        }.groupBy({ it.first }, { it.second })
        val (currency, values) = byCurrency.maxByOrNull { it.value.size } ?: return null
        val trimmed = trimOutliers(values.sorted())
        if (trimmed.size < 3) return null
        return Estimate(
            median = median(trimmed),
            low = percentile(trimmed, 0.25),
            high = percentile(trimmed, 0.75),
            count = trimmed.size,
            currency = currency,
            site = site.removePrefix("EBAY_"),
        )
    }

    /** Search phrase from the title: letters, digits and spaces only, short. */
    private fun cleanQuery(title: String) =
        title.replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim().take(80)

    private fun trimOutliers(sorted: List<Double>): List<Double> {
        if (sorted.size < 5) return sorted
        val q1 = percentile(sorted, 0.25)
        val q3 = percentile(sorted, 0.75)
        val iqr = q3 - q1
        return sorted.filter { it >= q1 - 1.5 * iqr && it <= q3 + 1.5 * iqr }
    }

    private fun median(s: List<Double>) = percentile(s, 0.5)

    private fun percentile(s: List<Double>, p: Double): Double {
        val idx = (s.size - 1) * p
        val lo = idx.toInt()
        val hi = minOf(lo + 1, s.lastIndex)
        return s[lo] + (s[hi] - s[lo]) * (idx - lo)
    }

    fun round(v: Double): String = if (v >= 20) v.roundToInt().toString() else "%.2f".format(v)

    private fun accessToken(clientId: String, clientSecret: String): String {
        token?.let { (t, exp) -> if (System.currentTimeMillis() < exp - 60_000) return t }
        val basic = Base64.encodeToString("${clientId.trim()}:${clientSecret.trim()}".toByteArray(), Base64.NO_WRAP)
        val body = "grant_type=client_credentials&scope=" + URLEncoder.encode("https://api.ebay.com/oauth/api_scope", "UTF-8")
        val json = try {
            request(
                URL("https://$HOST/identity/v1/oauth2/token"), "POST",
                mapOf("Authorization" to "Basic $basic", "Content-Type" to "application/x-www-form-urlencoded"),
                body,
            )
        } catch (e: HttpError) {
            if (e.code == 400 || e.code == 401) throw BadKeys() else throw e
        }
        val t = json.optString("access_token").takeIf { it.isNotBlank() } ?: throw IOException("No access token from eBay")
        token = t to System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        return t
    }

    fun forgetToken() { token = null }

    private class HttpError(val code: Int) : IOException("eBay answered HTTP $code")

    private fun request(url: URL, method: String, headers: Map<String, String>, body: String?): JSONObject {
        require(url.protocol == "https" && url.host == HOST) { "Blocked host ${url.host}" }
        val conn = (url.openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            useCaches = false
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw HttpError(code)
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
