package com.listingprep.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.platform.LocalContext
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Switch
import com.listingprep.data.readyToShare
import com.listingprep.data.isActive
import com.listingprep.data.Regions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.listingprep.data.CONDITIONS
import com.listingprep.data.Item
import com.listingprep.data.Photo
import com.listingprep.data.Status

// ---- list -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ItemListScreen(
    state: UiState,
    vm: AppViewModel,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onCamera: () -> Unit,
) {
    // Secondary: Android's photo picker (no photo permission needed; the app only sees what you pick).
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MediaStore.getPickImagesMaxLimit())
    ) { uris -> if (uris.isNotEmpty()) vm.importPhotos(uris) }
    val idle = state.busy == null
    val pending = state.items.count { it.description.isBlank() && it.isActive }
    var tab by rememberSaveable { mutableStateOf(Tab.ACTIVE) }
    val shown = remember(state.items, tab) { state.items.filter { tab.matches(it) } }
    val deleter = rememberItemDeleter(vm)
    val share = rememberMarketplaceSharer(vm, state.settings)
    // Bulk select: long-press an item to start, tap to add or remove.
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    LaunchedEffect(tab, state.items) { selected = selected.filter { id -> shown.any { it.id == id } }.toSet() }
    BackHandler(enabled = selecting) { selected = emptySet() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    val chosen = shown.map { it.id }.filter { it in selected } // in list order

    Scaffold(
        topBar = {
            if (selecting) TopAppBar(
                title = { Text("${selected.size} selected") },
                navigationIcon = {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Default.Close, contentDescription = "Cancel selection") }
                },
                actions = {
                    TextButton(onClick = { selected = shown.map { it.id }.toSet() }) { Text("Select all") }
                },
            ) else TopAppBar(
                title = { Text("Listing Prep") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = {
            if (selecting) BottomAppBar {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (tab) {
                        Tab.ACTIVE -> {
                            OutlinedButton(onClick = { vm.mergeAll(chosen); selected = emptySet() }, enabled = idle && chosen.size >= 2) {
                                Text("Merge")
                            }
                            OutlinedButton(onClick = { vm.markPostedAll(chosen); selected = emptySet() }, enabled = idle) {
                                Text("Posted")
                            }
                        }
                        Tab.POSTED -> {
                            OutlinedButton(onClick = { vm.markSoldAll(chosen); selected = emptySet() }, enabled = idle) {
                                Text("Sold")
                            }
                            OutlinedButton(onClick = { vm.markActiveAll(chosen); selected = emptySet() }, enabled = idle) {
                                Text("Not posted")
                            }
                        }
                        Tab.SOLD -> OutlinedButton(onClick = { vm.markPostedAll(chosen); selected = emptySet() }, enabled = idle) {
                            Text("Not sold")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { deleter(chosen); selected = emptySet() }) { Text("Delete") }
                }
            } else BottomAppBar {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onCamera,
                        modifier = Modifier.weight(1f),
                    ) { Text("Take photos", maxLines = 1) }
                    TextButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    ) { Text("From gallery", maxLines = 1) }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "status", contentType = "status") { StatusCards(state, vm) }
            state.update?.let { u -> item(key = "update", contentType = "update") { UpdateCard(u, vm) } }
            if (state.items.isNotEmpty()) item(key = "tabs", contentType = "tabs") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tab.entries.forEach { t ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text("${t.label} ${state.items.count(t::matches)}") },
                        )
                    }
                }
            }
            if (tab == Tab.SOLD && shown.isNotEmpty()) item(key = "clear-sold", contentType = "write") {
                OutlinedButton(
                    onClick = { deleter(shown.map { it.id }) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete all sold items and their photos") }
            }
            if (tab == Tab.ACTIVE && state.canWrite && pending > 0) {
                item(key = "write", contentType = "write") {
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(start = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$pending item${if (pending == 1) "" else "s"} without a description",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Button(onClick = { vm.generateAll() }, enabled = idle) { Text("Write all") }
                        }
                    }
                }
            }
            if (state.modelPath == null && !state.canWrite) {
                item {
                    val download = state.download
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (download != null) {
                                Text(download.label, style = MaterialTheme.typography.bodyMedium)
                                val p = download.progress
                                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            } else {
                                Text("No AI model yet", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Photo grouping works now. Descriptions need the free 4.4 GB Gemma model.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (state.hasHfToken) {
                                    Button(onClick = { vm.downloadModel() }, enabled = idle) { Text("Download AI model") }
                                } else {
                                    TextButton(onClick = onSettings) { Text("Set up model download") }
                                }
                            }
                        }
                    }
                }
            }
            if (state.items.isEmpty() && idle) {
                item {
                    Text(
                        "Tap Take photos and shoot each thing you're selling: all its angles, then Next item. " +
                            "Already have photos? From gallery sorts them into items.",
                        Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.items.isNotEmpty() && shown.isEmpty()) {
                item(key = "empty-tab") {
                    Text(
                        tab.empty,
                        Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(shown, key = { _, it -> it.id }, contentType = { _, _ -> "item" }) { index, item ->
                Column {
                    ItemCard(
                        item,
                        generating = state.generatingId == item.id,
                        onClick = { if (selecting) toggle(item.id) else onOpen(item.id) },
                        onLongClick = { toggle(item.id) },
                        selected = if (selecting) item.id in selected else null,
                        actionLabel = if (selecting) null else when {
                            item.isActive && item.readyToShare() -> "Share"
                            item.status == Status.POSTED -> "Sold"
                            item.status == Status.SOLD -> "Delete"
                            else -> null
                        },
                        onAction = {
                            when {
                                item.isActive -> share(item)
                                item.status == Status.POSTED -> vm.markSold(item.id)
                                else -> deleter(listOf(item.id))
                            }
                        },
                    )
                    val next = shown.getOrNull(index + 1)
                    if (tab == Tab.ACTIVE && next != null && !selecting) {
                        TextButton(
                            onClick = { vm.mergeWith(item.id, next.id) },
                            enabled = idle,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Merge with item below") }
                    }
                }
            }
        }
    }
}

internal enum class Tab(val label: String, val empty: String) {
    ACTIVE("To post", "Nothing waiting to be posted."),
    POSTED("Posted", "Items you've posted show here. Mark them Sold when they go."),
    SOLD("Sold", "Sold items show here, ready to clean up.");

    fun matches(item: Item) = when (this) {
        ACTIVE -> item.isActive
        POSTED -> item.status == Status.POSTED
        SOLD -> item.status == Status.SOLD
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ItemCard(
    item: Item,
    generating: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean?,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(CardDefaults.shape).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected == true) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                Checkbox(checked = selected, onCheckedChange = { onClick() })
            }
            val context = LocalContext.current
            val cover = item.photos.firstOrNull()?.shown
            val request = remember(cover) {
                coil.request.ImageRequest.Builder(context).data(cover?.let(Uri::parse)).size(256).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title.ifBlank { "Untitled item" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val count = item.photos.size
                Text(
                    "$count photo${if (count == 1) "" else "s"} · ${statusLabel(item)}" +
                        (if (item.price.isNotBlank()) " · ${item.price}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (generating) {
                    Text("Writing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else if (item.unsure) {
                    Text("AI wasn't sure what this is: tap to tell it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                } else if (item.description.isNotBlank()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (actionLabel != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onAction) {
                    if (actionLabel == "Share") {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(actionLabel)
                }
            }
        }
    }
}


@Composable
internal fun UpdateCard(update: UpdateUi, vm: AppViewModel) {
    val context = LocalContext.current
    var canInstall by remember { mutableStateOf(vm.canInstallUpdates()) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { canInstall = vm.canInstallUpdates(); onPauseOrDispose { } }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Update available: build ${update.build}", style = MaterialTheme.typography.titleSmall)
            Text(update.label, style = MaterialTheme.typography.bodyMedium)
            if (update.notes.isNotBlank()) {
                Text(update.notes.lineSequence().first(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            update.progress?.let { p -> if (!update.ready) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
            when {
                update.offerOnly -> Button(onClick = { vm.checkForUpdate() }) { Text("Download & see what's new") }
                update.ready && canInstall -> Button(onClick = vm::openUpdatePopup) { Text("See what's new & install") }
                update.ready -> {
                    Text("One-time step: allow Listing Prep to install its own updates.", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }) { Text("Allow updates") }
                }
            }
        }
    }
}
