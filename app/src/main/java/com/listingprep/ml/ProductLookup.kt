package com.listingprep.ml

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Online barcode lookup, used only when the AI couldn't identify the item from the photos.
 *
 * What leaves the phone: the barcode number only (plus your IP address, as with any
 * connection). No photos, text, token or device details. Safeguards:
 * - Only retail barcodes with a valid check digit (EAN-13, EAN-8, UPC-A, ISBN) are sent.
 * - HTTPS only, to three fixed hosts. The network security config blocks every other host,
 *   and redirects are followed only within the same host.
 * - Short timeouts, responses capped at 256 KB, no cookies.
 * - Replies are treated as untrusted text: control characters stripped, fields shortened,
 *   and the AI is told the info may be wrong.
 */
object ProductLookup {
    private const val UPCITEMDB = "api.upcitemdb.com"
    private const val OPEN_FOOD_FACTS = "world.openfoodfacts.org"
    private const val OPEN_LIBRARY = "openlibrary.org"
    val HOSTS = setOf(UPCITEMDB, OPEN_FOOD_FACTS, OPEN_LIBRARY)
    private const val MAX_BYTES = 256 * 1024

    /** The retail barcode number among scanned codes ("EAN-13: 4006…"), if any is valid. */
    fun retailCode(codes: List<String>): String? = codes.firstNotNullOfOrNull { line ->
        val kind = line.substringBefore(':')
        val digits = line.substringAfter(':').trim()
        if (kind in setOf("EAN-13", "EAN-8", "UPC-A") && isValidGtin(digits)) digits else null
    }

    private fun isValidGtin(code: String): Boolean {
        if (code.length !in setOf(8, 12, 13) || !code.all { it.isDigit() }) return false
        val digits = code.map { it - '0' }
        val body = digits.dropLast(1).reversed()
        val sum = body.mapIndexed { i, d -> if (i % 2 == 0) d * 3 else d }.sum()
        return (10 - sum % 10) % 10 == digits.last()
    }

    /** Blocking. Returns a short "Name: …; Brand: …" summary, or null if nothing was found. */
    fun lookup(code: String): String? {
        require(isValidGtin(code))
        val isbn = code.length == 13 && (code.startsWith("978") || code.startsWith("979"))
        val sources: List<() -> String?> = if (isbn) {
            listOf({ openLibrary(code) }, { upcItemDb(code) })
        } else {
            listOf({ upcItemDb(code) }, { openFoodFacts(code) })
        }
        for (source in sources) {
            val found = runCatching { source() }.getOrNull()
            if (!found.isNullOrBlank()) return found.take(600)
        }
        return null
    }

    private fun upcItemDb(code: String): String? {
        val json = get(URL("https://$UPCITEMDB/prod/trial/lookup?upc=$code")) ?: return null
        val item = json.optJSONArray("items")?.optJSONObject(0) ?: return null
        return summary(
            "Name" to item.optString("title"),
            "Brand" to item.optString("brand"),
            "Model" to item.optString("model"),
            "Category" to item.optString("category"),
            "Colour" to item.optString("color"),
            "Size" to item.optString("size"),
            "Dimensions" to item.optString("dimension"),
            "Weight" to item.optString("weight"),
        )
    }

    private fun openFoodFacts(code: String): String? {
        val json = get(URL("https://$OPEN_FOOD_FACTS/api/v2/product/$code.json?fields=product_name,brands,quantity,categories"))
            ?: return null
        if (json.optInt("status") != 1) return null
        val p = json.optJSONObject("product") ?: return null
        return summary(
            "Name" to p.optString("product_name"),
            "Brand" to p.optString("brands"),
            "Quantity" to p.optString("quantity"),
            "Category" to p.optString("categories").split(',').take(3).joinToString(", "),
        )
    }

    private fun openLibrary(isbn: String): String? {
        val json = get(URL("https://$OPEN_LIBRARY/isbn/$isbn.json")) ?: return null
        return summary(
            "Book title" to json.optString("title"),
            "Publisher" to (json.optJSONArray("publishers")?.optString(0) ?: ""),
            "Published" to json.optString("publish_date"),
            "Pages" to json.optInt("number_of_pages").takeIf { it > 0 }?.toString().orEmpty(),
        )
    }

    private fun summary(vararg fields: Pair<String, String>): String? {
        val parts = fields.mapNotNull { (k, v) -> clean(v)?.let { "$k: $it" } }
        return if (parts.isEmpty() || parts.none { it.startsWith("Name") || it.startsWith("Book title") }) null
        else parts.joinToString("; ")
    }

    /** Untrusted text from the internet: printable characters only, one line, short. */
    private fun clean(v: String): String? =
        v.filter { !it.isISOControl() }.replace(Regex("\\s+"), " ").trim().take(120)
            .takeIf { it.isNotEmpty() && it != "null" }

    private fun get(start: URL): JSONObject? {
        var url = start
        repeat(3) {
            require(url.protocol == "https" && url.host in HOSTS) { "Blocked host ${url.host}" }
            val conn = (url.openConnection() as HttpsURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "ListingPrep/0.1 (personal Android app)")
            }
            try {
                when (val code = conn.responseCode) {
                    200 -> return JSONObject(readCapped(conn))
                    301, 302, 303, 307, 308 -> {
                        val next = URL(url, conn.getHeaderField("Location") ?: return null)
                        if (next.host != url.host) return null // never follow to another site
                        url = next
                    }
                    404 -> return null
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun readCapped(conn: HttpsURLConnection): String {
        val out = ByteArrayOutputStream()
        conn.inputStream.use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw IOException("Response too large")
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
