package com.listingprep.ml

/**
 * Cleans text that came from the internet (barcode databases, web search) or from the AI
 * before it's used in a listing. A web page or product database could be written to trick
 * the AI into adding a scam link or contact details to your listing; this removes web
 * addresses, email addresses and invisible control characters, so they can't slip into
 * what you post. You still review every listing before sharing it.
 */
object TextSafety {
    private val URL = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val BARE_DOMAIN = Regex("""(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|io|co|me|ly|link|xyz|info|biz|shop|site|online|top|app|gl|to|cc|ru|cn|hk|uk)(?:/\S*)?\b""")
    private val EMAIL = Regex("""(?i)\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b""")
    private val CONTROL = Regex("""[\p{Cc}\p{Cf}&&[^\n\t]]""")

    fun clean(text: String): String = text
        .replace(CONTROL, "")
        .replace(EMAIL, "")
        .replace(URL, "")
        .replace(BARE_DOMAIN, "")
        .replace(Regex("""[ \t]{2,}"""), " ")
        .lines().joinToString("\n") { it.trimEnd() }
        .trim()
}
