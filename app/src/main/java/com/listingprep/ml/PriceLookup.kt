package com.listingprep.ml

import java.io.IOException

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

    data class Estimate(
        val summary: String,
        val price: Double?,
        val low: Double?,
        val high: Double?,
        val currency: String?,
        val sources: List<String>,
    )


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

        val answer = Gemini.generate(apiKey, listOf(Gemini.textPart(prompt)), search = true, temperature = 0.2)
        return parse(answer.text, answer.sources)
    }

    /** Reads the "PRICE: … | LOW: … | HIGH: … | CURRENCY: …" line from the answer. */
    fun parse(text: String, sources: List<String>): Estimate {
        val line = Regex("""PRICE:\s*([^\n]+)""", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()?.groupValues?.get(1).orEmpty()
        fun field(name: String) = Regex("""$name:\s*([0-9][0-9.,]*)""", RegexOption.IGNORE_CASE).find("PRICE: $line")
            ?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 && it < 100_000_000 }
        val cur = Regex("""CURRENCY:\s*([A-Za-z]{3})""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.uppercase()
        val summary = clean(text.substringBefore("PRICE:").ifBlank { text }, 400)
        return Estimate(summary, field("PRICE"), field("LOW"), field("HIGH"), cur, sources)
    }

    fun round(v: Double): String = if (v >= 20) Math.round(v).toString() else "%.2f".format(v)

    /** Untrusted text: printable characters only, single spaces, short. */
    private fun clean(v: String, max: Int) =
        v.filter { !it.isISOControl() || it == '\n' }.replace(Regex("[\\t ]+"), " ").replace("**", "").trim().take(max)
}
