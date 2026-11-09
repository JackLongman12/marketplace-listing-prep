package com.listingprep.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.ui.Alignment
import com.listingprep.data.Region
import com.listingprep.data.Regions
import org.json.JSONObject
import java.util.Locale

/** One ISO 3166-2 subdivision (state, province, region, district…). */
private data class Area(val code: String, val name: String, val parent: String, val type: String)

/** Bundled ISO 3166-2 list (Debian iso-codes), read once. Works offline. */
private object Areas {
    @Volatile private var cache: Map<String, List<Area>>? = null

    fun of(context: Context, country: String): List<Area> {
        val all = cache ?: runCatching {
            val json = JSONObject(context.assets.open("subdivisions.json").bufferedReader().use { it.readText() })
            json.keys().asSequence().associateWith { cc ->
                val arr = json.getJSONArray(cc)
                (0 until arr.length()).map { i ->
                    val r = arr.getJSONArray(i)
                    Area(r.getString(0), r.getString(1), r.getString(2), r.getString(3))
                }
            }
        }.getOrDefault(emptyMap()).also { cache = it }
        return all[country].orEmpty()
    }
}

data class PickedLocation(val country: String, val area: String, val areaName: String, val city: String)

/**
 * Narrows down step by step: country, then state / province (and a further level where the
 * country has one), then an optional city or district. Each step is a short searchable list.
 */
@Composable
fun LocationPicker(onPicked: (PickedLocation) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var country by remember { mutableStateOf<Region?>(null) }
    var path by remember { mutableStateOf(listOf<Area>()) } // chosen areas, biggest first
    var choosingCity by remember { mutableStateOf(false) }
    var city by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    val areas = remember(country) { country?.let { Areas.of(context, it.code) }.orEmpty() }
    val parent = path.lastOrNull()?.code.orEmpty()
    val level = areas.filter { it.parent == parent }

    fun finish() {
        val c = country ?: return
        onPicked(PickedLocation(c.code, path.lastOrNull()?.code.orEmpty(), path.joinToString(", ") { it.name }, city.trim()))
    }

    fun back() {
        query = ""
        when {
            choosingCity -> { choosingCity = false; if (level.isEmpty() && path.isNotEmpty()) path = path.dropLast(1) }
            path.isNotEmpty() -> path = path.dropLast(1)
            country != null -> country = null
            else -> onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                BackHandler { back() }
                val breadcrumb = listOfNotNull(country?.name) + path.map { it.name }
                Text(
                    when {
                        country == null -> "Country or region"
                        choosingCity || level.isEmpty() -> "City or district (optional)"
                        else -> level.first().type.ifBlank { "Area" }.replaceFirstChar { it.titlecase(Locale.ENGLISH) }
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                if (breadcrumb.isNotEmpty()) {
                    Text(breadcrumb.joinToString(" › "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.padding(4.dp))

                if (country != null && (choosingCity || level.isEmpty())) {
                    OutlinedTextField(
                        value = city,
                        onValueChange = { city = it.take(60) },
                        label = { Text("City, suburb or district") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { back() }) { Text("Back") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = { finish() }) { Text("Save") }
                    }
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val q = query.trim().lowercase()
                    // Country list: your phone's region first, then A–Z.
                    val options: List<Pair<String, () -> Unit>> = if (country == null) {
                        val home = Locale.getDefault().country
                        (Regions.all.filter { it.code == home } + Regions.all.filter { it.code != home })
                            .filter { q.isEmpty() || it.name.lowercase().contains(q) }
                            .map { r -> r.name to { country = r; path = emptyList(); query = "" } }
                    } else {
                        level.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                            .map { a -> a.name to { path = path + a; query = ""; if (areas.none { it.parent == a.code }) choosingCity = true } }
                    }
                    LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp).weight(1f, fill = false)) {
                        items(options) { (name, pick) ->
                            Text(name, Modifier.fillMaxWidth().clickable { pick() }.padding(vertical = 12.dp))
                            HorizontalDivider()
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        TextButton(onClick = { back() }) { Text(if (country == null) "Cancel" else "Back") }
                        Spacer(Modifier.weight(1f))
                        if (country != null) TextButton(onClick = { choosingCity = true; query = "" }) { Text("Skip") }
                    }
                }
            }
        }
    }
}
