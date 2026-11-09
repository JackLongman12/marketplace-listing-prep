package com.listingprep.data

import java.util.UUID

enum class Status { DRAFT, READY, POSTED, SOLD }

/** Still being prepared: not posted or sold yet. */
val Item.isActive get() = status == Status.DRAFT || status == Status.READY

val CONDITIONS = listOf("New", "Like new", "Good", "Fair", "For parts")

data class Photo(
    val uri: String,
    val takenAt: Long,
    val sharpness: Double,
    /** Same photo with the background replaced by white (in-app file), or null. The original is kept. */
    val cutout: String? = null,
) {
    /** What's shown and shared: the white-background version when there is one. */
    val shown: String get() = cutout ?: uri
}

data class Item(
    val id: String = UUID.randomUUID().toString(),
    val photos: List<Photo>,
    val title: String = "",
    val description: String = "",
    /** Asking price as typed, without currency (the region in Settings sets the currency). */
    val price: String = "",
    val condition: String = "Good",
    val notes: String = "",
    val status: Status = Status.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    /** Set by the user after reviewing photos and text. Sharing is locked until this is true. */
    val checked: Boolean = false,
    /** Copies saved to Pictures/ListingPrep for the last share, replaced on the next one. */
    val exportedUris: List<String> = emptyList(),
    /** Barcodes / QR codes found in the photos, e.g. "EAN-13: 4006381333931" or "QR: https://…". */
    val codes: List<String> = emptyList(),
    /** Product info from the online barcode lookup, cached so it's fetched once. */
    val product: String = "",
    /** What the seller says the item is (brand, model…), when the AI couldn't tell. */
    val identity: String = "",
    /** The AI wrote a generic description because it couldn't tell what the item is. */
    val unsure: Boolean = false,
)

/** Everything Facebook needs is filled in and a human has signed off. */
fun Item.readyToShare(): Boolean =
    checked && photos.isNotEmpty() && title.isNotBlank() && price.isNotBlank() && description.isNotBlank()

data class Settings(
    val footer: String = "Pickup only. Cash or bank transfer. No holds.",
    val defaultCondition: String = "Good",
    /** Your own instructions for how the AI writes every listing. */
    val writingStyle: String = "",
    /** Look up retail barcodes online, only when the AI can't tell what the item is. */
    val onlineLookup: Boolean = true,
    /** Write descriptions automatically after taking photos. */
    val autoWrite: Boolean = true,
    /** Opt-in: write descriptions with Google Gemini (photos are sent to Google) instead of on the phone. */
    val cloudWriting: Boolean = false,
    /** Which cloud AI writes ("gemini", "github", "openrouter", "groq", "mistral"). */
    val writingProvider: String = "gemini",
    /** Model name at that provider; blank = the provider's default. */
    val writingModel: String = "",
    /** Download new builds from GitHub and install them when you leave the app. */
    val autoUpdate: Boolean = true,
    /** ISO region code (e.g. "HK" for Hong Kong SAR); blank = the phone's region. */
    val region: String = "",
    /** State / province code (ISO 3166-2) and its readable name path, e.g. "Scotland, Aberdeen City". */
    val area: String = "",
    val areaName: String = "",
    /** Optional city or district, used for wording and price context. */
    val city: String = "",
)

/** The description with the listing defaults removed (what the AI should see when rewriting). */
fun stripDefaults(description: String, settings: Settings): String {
    var text = description
    val footer = settings.footer.trim()
    if (footer.isNotEmpty()) text = text.replace(footer, "")
    return text.lines().filterNot { it.trim().startsWith("Condition:", ignoreCase = true) }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
}

/** AI text followed by the listing defaults: "Condition: …" and your footer from Settings. */
fun withDefaults(description: String, condition: String, settings: Settings): String = buildString {
    append(stripDefaults(description, settings))
    if (condition.isNotBlank()) {
        if (isNotEmpty()) append("\n\n")
        append("Condition: ").append(condition)
    }
    if (settings.footer.isNotBlank()) {
        if (isNotEmpty()) append("\n\n")
        append(settings.footer.trim())
    }
}.trim()

/** Full description text as it should be pasted into Facebook (defaults added only if missing). */
fun Item.listingDescription(settings: Settings): String = buildString {
    append(description.trim())
    if (condition.isNotBlank() && description.lines().none { it.trim().startsWith("Condition:", ignoreCase = true) }) {
        if (isNotEmpty()) append("\n\n")
        append("Condition: ").append(condition)
    }
    val footer = settings.footer.trim()
    if (footer.isNotEmpty() && !description.contains(footer)) {
        append("\n\n").append(footer)
    }
}.trim()
