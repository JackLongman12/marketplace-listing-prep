package com.listingprep.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves items as a small JSON file and settings in SharedPreferences. */
class Store(context: Context) {
    private val file = File(context.filesDir, "items.json")
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun loadItems(): List<Item> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { arr.getJSONObject(it).toItem() }
    }.getOrDefault(emptyList())

    fun saveItems(items: List<Item>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        val tmp = File(file.parentFile, "items.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun loadSettings() = Settings(
        footer = prefs.getString("footer", null) ?: Settings().footer,
        defaultCondition = prefs.getString("defaultCondition", null) ?: Settings().defaultCondition,
        writingStyle = prefs.getString("writingStyle", null) ?: "",
        backgroundRemoval = prefs.getBoolean("backgroundRemoval", false),
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("footer", s.footer)
            .putString("defaultCondition", s.defaultCondition)
            .putString("writingStyle", s.writingStyle)
            .putBoolean("backgroundRemoval", s.backgroundRemoval)
            .apply()
    }

    /** Stored encrypted with a Keystore key (see [TokenVault]). */
    var hfToken: String?
        get() = prefs.getString("hfTokenEnc", null)?.let(TokenVault::decrypt)
        set(v) {
            prefs.edit().apply {
                if (v.isNullOrBlank()) remove("hfTokenEnc") else putString("hfTokenEnc", TokenVault.encrypt(v))
            }.apply()
        }

    var modelDownloadId: Long
        get() = prefs.getLong("modelDownloadId", -1L)
        set(v) { prefs.edit().putLong("modelDownloadId", v).apply() }

    private fun Item.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("description", description)
        put("condition", condition)
        put("notes", notes)
        put("status", status.name)
        put("createdAt", createdAt)
        put("checked", checked)
        put("exportedUris", JSONArray().apply { exportedUris.forEach { put(it) } })
        put("codes", JSONArray().apply { codes.forEach { put(it) } })
        put("photos", JSONArray().apply {
            photos.forEach { p ->
                put(JSONObject().apply {
                    put("uri", p.uri)
                    put("takenAt", p.takenAt)
                    put("sharpness", p.sharpness)
                    p.cutout?.let { put("cutout", it) }
                })
            }
        })
    }

    private fun JSONObject.toItem(): Item {
        val ps = getJSONArray("photos")
        return Item(
            id = getString("id"),
            photos = (0 until ps.length()).map {
                val p = ps.getJSONObject(it)
                Photo(p.getString("uri"), p.optLong("takenAt"), p.optDouble("sharpness", 0.0), p.optString("cutout").ifBlank { null })
            },
            title = optString("title"),
            description = optString("description"),
            condition = optString("condition", "Good"),
            notes = optString("notes"),
            status = runCatching { Status.valueOf(optString("status")) }.getOrDefault(Status.DRAFT),
            createdAt = optLong("createdAt"),
            checked = optBoolean("checked", false),
            exportedUris = optJSONArray("exportedUris")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            codes = optJSONArray("codes")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    }
}
