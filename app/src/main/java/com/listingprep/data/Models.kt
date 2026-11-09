package com.listingprep.data

import java.util.UUID

enum class Status { DRAFT, READY, POSTED }

val CONDITIONS = listOf("New", "Like new", "Good", "Fair", "For parts")

data class Photo(
    val uri: String,
    val takenAt: Long,
    val sharpness: Double,
)

data class Item(
    val id: String = UUID.randomUUID().toString(),
    val photos: List<Photo>,
    val title: String = "",
    val description: String = "",
    val price: String = "",
    val condition: String = "Good",
    val notes: String = "",
    val status: Status = Status.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
)

data class Settings(
    val footer: String = "Pickup only. Cash or bank transfer. No holds.",
    val defaultCondition: String = "Good",
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
