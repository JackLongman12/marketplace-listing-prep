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
        onlineLookup = prefs.getBoolean("onlineLookup", true),
        autoWrite = prefs.getBoolean("autoWrite", true),
        autoUpdate = prefs.getBoolean("autoUpdate", true),
        listingInBrowser = prefs.getBoolean("listingInBrowser", true),
        cloudWriting = prefs.getBoolean("cloudWriting", false),
        useNano = prefs.getBoolean("useNano", true),
        writingProvider = prefs.getString("writingProvider", null) ?: "gemini",
        writingModel = prefs.getString("writingModel", null) ?: "",
        region = prefs.getString("region", null) ?: "",
        city = prefs.getString("city", null) ?: "",
        area = prefs.getString("area", null) ?: "",
        areaName = prefs.getString("areaName", null) ?: "",
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("footer", s.footer)
            .putString("defaultCondition", s.defaultCondition)
            .putString("writingStyle", s.writingStyle)
            .putBoolean("onlineLookup", s.onlineLookup)
            .putBoolean("autoWrite", s.autoWrite)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putBoolean("listingInBrowser", s.listingInBrowser)
            .putBoolean("cloudWriting", s.cloudWriting)
            .putBoolean("useNano", s.useNano)
            .putString("writingProvider", s.writingProvider)
            .putString("writingModel", s.writingModel)
            .putString("region", s.region)
            .putString("city", s.city)
            .putString("area", s.area)
            .putString("areaName", s.areaName)
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

    /** Gemini API key for the price search, encrypted with the Keystore key. */
    var geminiKey: String?
        get() = prefs.getString("geminiKeyEnc", null)?.let(TokenVault::decrypt)
        set(v) {
            prefs.edit().apply {
                remove("ebayIdEnc"); remove("ebaySecretEnc") // from build 22
                if (v.isNullOrBlank()) remove("geminiKeyEnc") else putString("geminiKeyEnc", TokenVault.encrypt(v.trim()))
            }.apply()
        }

    /** Build the user last opened, and the notes of the update being installed, for the "Updated" pop-up. */
    /** API key for a cloud provider, encrypted with the Keystore key. Gemini uses [geminiKey]. */
    fun providerKey(id: String): String? =
        if (id == "gemini") geminiKey else prefs.getString("key_$id", null)?.let(TokenVault::decrypt)

    fun setProviderKey(id: String, key: String?) {
        if (id == "gemini") { geminiKey = key; return }
        prefs.edit().apply {
            if (key.isNullOrBlank()) remove("key_$id") else putString("key_$id", TokenVault.encrypt(key.trim()))
        }.apply()
    }

    /** Providers that have a key saved. */
    fun providersWithKeys(): Set<String> =
        listOf("gemini", "github", "openrouter", "groq", "mistral").filter { !providerKey(it).isNullOrBlank() }.toSet()

    fun clearProviderKeys() = listOf("github", "openrouter", "groq", "mistral").forEach { setProviderKey(it, null) }

    var lastSeenBuild: Long
        get() = prefs.getLong("lastSeenBuild", 0L)
        set(v) { prefs.edit().putLong("lastSeenBuild", v).apply() }

    var pendingNotes: Pair<Int, String>?
        get() = prefs.getInt("pendingNotesBuild", 0).takeIf { it > 0 }?.let { it to prefs.getString("pendingNotes", "").orEmpty() }
        set(v) { prefs.edit().putInt("pendingNotesBuild", v?.first ?: 0).putString("pendingNotes", v?.second.orEmpty()).apply() }

    /** Newest build seen on GitHub, and the newest one we've already sent a notification about. */
    var availableBuild: Int
        get() = prefs.getInt("availableBuild", 0)
        set(v) { prefs.edit().putInt("availableBuild", v).apply() }


    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(v) { prefs.edit().putLong("lastUpdateCheck", v).apply() }

    var modelDownloadId: Long
        get() = prefs.getLong("modelDownloadId", -1L)
        set(v) { prefs.edit().putLong("modelDownloadId", v).apply() }

    private fun Item.toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("description", description)
        put("price", price)
        put("condition", condition)
        put("notes", notes)
        put("status", status.name)
        put("createdAt", createdAt)
        put("checked", checked)
        put("exportedUris", JSONArray().apply { exportedUris.forEach { put(it) } })
        put("codes", JSONArray().apply { codes.forEach { put(it) } })
        put("product", product)
        put("identity", identity)
        put("unsure", unsure)
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
            price = optString("price"),
            condition = optString("condition", "Good"),
            notes = optString("notes"),
            status = runCatching { Status.valueOf(optString("status")) }.getOrDefault(Status.DRAFT),
            createdAt = optLong("createdAt"),
            checked = optBoolean("checked", false),
            exportedUris = optJSONArray("exportedUris")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            product = optString("product"),
            identity = optString("identity"),
            unsure = optBoolean("unsure", false),
            codes = optJSONArray("codes")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    }
}
