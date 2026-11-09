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
)

/** Everything Facebook needs is filled in and a human has signed off. */
fun Item.readyToShare(): Boolean =
    checked && photos.isNotEmpty() && title.isNotBlank() && description.isNotBlank()

data class Settings(
    val footer: String = "Pickup only. Cash or bank transfer. No holds.",
    val defaultCondition: String = "Good",
    /** Your own instructions for how the AI writes every listing. */
    val writingStyle: String = "",
    /** Shows "Remove background" on photos. Off by default. */
    val backgroundRemoval: Boolean = false,
)

/** Full description text as it should be pasted into Facebook. */
fun Item.listingDescription(settings: Settings): String = buildString {
    append(description.trim())
    if (condition.isNotBlank()) {
        if (isNotEmpty()) append("\n\n")
        append("Condition: ").append(condition)
    }
    if (settings.footer.isNotBlank()) {
        append("\n\n").append(settings.footer.trim())
    }
}.trim()
