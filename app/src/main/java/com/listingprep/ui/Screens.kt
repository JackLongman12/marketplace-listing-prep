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

private const val SETTINGS = "__settings__"
private const val CAMERA = "__camera__"
private const val REVIEW = "__review__"

@Composable
fun App(
    postedId: String? = null,
    onPostedHandled: () -> Unit = {},
    vm: AppViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    BackHandler(enabled = screen != null) { screen = null }
    val context = LocalContext.current
    // Background writing posts a "descriptions ready" notification; ask once.
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.undo?.stamp) {
        val undo = state.undo ?: return@LaunchedEffect
        val r = snackbar.showSnackbar(undo.message, actionLabel = "Undo", duration = SnackbarDuration.Short)
        if (r == SnackbarResult.ActionPerformed) vm.undo() else vm.hideUndo()
    }

    // "Done ✓" on the floating panel: mark posted and go back to the list for the next item.
    LaunchedEffect(postedId) {
        val id = postedId ?: return@LaunchedEffect
        vm.markPosted(id)
        screen = null
        onPostedHandled()
    }

    Box(Modifier.fillMaxSize()) {
    when (val current = screen) {
        null -> ItemListScreen(state, vm, onOpen = { screen = it }, onSettings = { screen = SETTINGS }, onCamera = { vm.warmUp(); screen = CAMERA })
        CAMERA -> CameraScreen(onFinish = { groups ->
            val ids = vm.addCameraItems(groups)
            if (state.settings.autoWrite &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            reviewIds = ids
            screen = if (ids.isEmpty()) null else REVIEW
        })
        REVIEW -> BackgroundReviewScreen(
            items = state.items.filter { it.id in reviewIds },
            vm = vm,
            onDone = { screen = null; reviewIds = emptyList(); vm.autoWrite() },
        )
        SETTINGS -> SettingsScreen(state, vm, onBack = { screen = null })
        else -> {
            val item = state.items.find { it.id == current }
            if (item == null) LaunchedEffect(current) { screen = null }
            else ItemScreen(item, state, vm, onBack = { screen = null })
        }
    }
    if (state.updatePopup) UpdatePopup(state, vm)
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }
}

// ---- shared bits ----------------------------------------------------------

@Composable
private fun StatusCards(state: UiState, vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.busy?.let { label ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (state.generatingId != null || label.startsWith("Writing")) {
                            TextButton(onClick = vm::stopWriting) { Text("Stop") }
                        }
                    }
                    val p = state.progress
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        state.error?.let { message ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = vm::clearError) { Text("OK") }
                }
            }
        }
    }
}

private fun statusLabel(item: Item) = when {
    item.status == Status.SOLD -> "Sold"
    item.status == Status.POSTED -> "Posted"
    item.checked -> "Checked ✓"
    item.status == Status.READY -> "Needs your check"
    else -> "Draft"
}

// ---- list -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemListScreen(
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
                    Button(onClick = { deleter(chosen); selected = emptySet() }, enabled = idle) { Text("Delete") }
                }
            } else BottomAppBar {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onCamera,
                        enabled = idle,
                        modifier = Modifier.weight(1f),
                    ) { Text("Take photos", maxLines = 1) }
                    TextButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = idle,
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
                    enabled = idle,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete all sold items and their photos") }
            }
            if (tab == Tab.ACTIVE && state.modelPath != null && pending > 0) {
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
            if (state.modelPath == null) {
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
                        action = if (selecting) null else when {
                            item.isActive && item.readyToShare() -> "Share" to { share(item) }
                            item.status == Status.POSTED -> "Sold" to { vm.markSold(item.id) }
                            item.status == Status.SOLD -> "Delete" to { deleter(listOf(item.id)) }
                            else -> null
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

private enum class Tab(val label: String, val empty: String) {
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
private fun ItemCard(
    item: Item,
    generating: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean?,
    action: Pair<String, () -> Unit>?,
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
            AsyncImage(
                model = item.photos.firstOrNull()?.shown?.let(Uri::parse),
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
                } else if (item.description.isNotBlank()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = action.second) {
                    if (action.first == "Share") {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(action.first)
                }
            }
        }
    }
}

// ---- item -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemScreen(item: Item, state: UiState, vm: AppViewModel, onBack: () -> Unit) {
    val idle = state.busy == null
    val index = state.items.indexOfFirst { it.id == item.id }
    val share = rememberMarketplaceSharer(vm, state.settings)
    val deleter = rememberItemDeleter(vm)
    var editBackground by remember { mutableStateOf<Int?>(null) }
    var confirmRefresh by remember { mutableStateOf(false) }
    if (confirmRefresh) AlertDialog(
        onDismissRequest = { confirmRefresh = false },
        title = { Text("Refresh this item?") },
        text = {
            Text(
                "Reads the photos again (text, barcodes), writes a new title and description" +
                    (if (state.hasPriceKey) " and searches the price again" else "") +
                    ". Your current title, description" + (if (state.hasPriceKey) " and price" else "") + " will be replaced."
            )
        },
        confirmButton = { TextButton(onClick = { confirmRefresh = false; vm.refreshItem(item.id) }) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = { confirmRefresh = false }) { Text("Cancel") } },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.title.ifBlank { "Item" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { confirmRefresh = true }, enabled = idle && state.modelPath != null) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh everything for this item")
                    }
                    IconButton(onClick = { deleter(listOf(item.id)) }) { Icon(Icons.Default.Delete, contentDescription = "Delete item") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCards(state, vm)

            Text("Photos — tap one for options. The first is the cover.", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(item.photos, key = { _, p -> p.uri }) { i, photo ->
                    PhotoThumb(
                        photo = photo,
                        index = i,
                        enabled = idle,
                        hasPrevious = index > 0,
                        hasNext = index in 0 until state.items.lastIndex,
                        onCover = { vm.setCover(item.id, i) },
                        onMove = { target -> vm.movePhoto(item.id, i, target) },
                        onRemove = { vm.removePhoto(item.id, i) },
                        onRemoveBackground = { editBackground = i },
                        onRestore = { vm.setCutout(item.id, i, null) },
                    )
                }
            }
            editBackground?.let { i ->
                item.photos.getOrNull(i)?.let { photo ->
                    BackgroundDialog(
                        photo = photo,
                        onSave = { file -> vm.setCutout(item.id, i, file); editBackground = null },
                        onDismiss = { editBackground = null },
                    )
                }
            }

            Text("Barcode / QR code", style = MaterialTheme.typography.labelLarge)
            if (item.codes.isEmpty()) {
                Text(
                    "Photograph the barcode or QR code as one of the angles, then scan. It's read on the phone; " +
                        "a QR code often names the product, a barcode number alone doesn't.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                item.codes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (item.product.isNotBlank()) {
                Text(
                    "Found online: ${item.product}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { vm.scanCodes(item.id) }, enabled = idle) {
                Text(if (item.codes.isEmpty()) "Scan photos for codes" else "Scan again")
            }

            Text("Condition", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CONDITIONS.forEach { c ->
                    FilterChip(
                        selected = item.condition == c,
                        onClick = { vm.updateItem(item.id) { it.copy(condition = c) } },
                        label = { Text(c) },
                    )
                }
            }

            OutlinedTextField(
                value = item.notes,
                onValueChange = { v -> vm.updateItem(item.id) { it.copy(notes = v) } },
                label = { Text("Notes for the AI (optional)") },
                placeholder = { Text("e.g. 120×60 cm, small dent on back, comes with charger") },
                modifier = Modifier.fillMaxWidth(),
            )

            // Only offered while there's no description yet: once written, edit it by hand.
            if (item.description.isBlank()) Button(
                onClick = { vm.generate(item.id) },
                enabled = idle && state.modelPath != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Write description") }
            if (state.modelPath == null && item.description.isBlank()) {
                Text(
                    "Add the AI model in Settings to write descriptions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = item.title,
                onValueChange = { v -> vm.updateItem(item.id) { it.copy(title = v) } },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            val region = remember(state.settings.region) { Regions.current(state.settings) }
            OutlinedTextField(
                value = item.price,
                onValueChange = { v -> vm.updateItem(item.id) { it.copy(price = v.filter { c -> c.isDigit() || c == '.' }.take(10)) } },
                label = { Text("Price (${region.currency.ifBlank { "local currency" }})") },
                prefix = { Text(region.symbol) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            var priceSetup by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { if (state.hasPriceKey) vm.findPrice(item.id) else priceSetup = true },
                    enabled = idle && item.title.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(if (item.price.isBlank()) "Find typical second-hand price" else "Check the price again") }
                if (state.hasPriceKey && item.price.isNotBlank()) {
                    IconButton(onClick = { vm.findPrice(item.id) }, enabled = idle && item.title.isNotBlank()) {
                        Icon(Icons.Default.Refresh, contentDescription = "Search the price again")
                    }
                }
            }
            state.priceHint?.takeIf { it.itemId == item.id }?.let { hint ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(hint.text, style = MaterialTheme.typography.bodyMedium)
                        if (hint.sources.isNotEmpty()) {
                            Text(
                                "Sources: " + hint.sources.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            hint.value?.let { v ->
                                Button(onClick = { vm.updateItem(item.id) { it.copy(price = v) }; vm.dismissPriceHint() }) {
                                    Text("Use ${region.symbol}$v")
                                }
                            }
                            TextButton(onClick = vm::dismissPriceHint) { Text("Close") }
                        }
                    }
                }
            }
            if (priceSetup) PriceKeyDialog(vm, onDone = { ok -> priceSetup = false; if (ok) vm.findPrice(item.id) })
            OutlinedTextField(
                value = item.description,
                onValueChange = { v -> vm.updateItem(item.id) { it.copy(description = v) } },
                label = { Text("Description") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            if (item.description.isNotBlank() || item.title.isNotBlank()) {
                var toneDialog by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.description.isNotBlank()) {
                        TextButton(onClick = { toneDialog = true }, enabled = idle && state.modelPath != null) { Text("Rewrite in another tone") }
                    }
                    TextButton(onClick = { vm.clearDescription(item.id) }, enabled = state.generatingId != item.id) {
                        Text("Delete text")
                    }
                }
                if (toneDialog) ToneDialog(onPick = { tone -> toneDialog = false; vm.rewriteTone(item.id, tone) }, onDismiss = { toneDialog = false })
            }
            Text(
                "Sharing adds the condition and your footer from Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            Text("Post it", style = MaterialTheme.typography.titleSmall)

            val missing = listOfNotNull(
                "title".takeIf { item.title.isBlank() },
                "price".takeIf { item.price.isBlank() },
                "description".takeIf { item.description.isBlank() },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Checked", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (missing.isEmpty()) "I've reviewed the photos and text."
                        else "Add ${missing.joinToString(", ")} first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = item.checked,
                    onCheckedChange = { on -> vm.updateItem(item.id) { it.copy(checked = on) } },
                    enabled = item.checked || missing.isEmpty(),
                )
            }
            Button(
                onClick = { share(item) },
                enabled = item.readyToShare(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share to Marketplace")
            }
            Text(
                "Saves the photos cover-first, copies the description, opens Facebook, and shows a " +
                    "floating panel with copy buttons for each field.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (item.status) {
                Status.POSTED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.markSold(item.id) }, modifier = Modifier.weight(1f)) { Text("Mark as sold") }
                    OutlinedButton(onClick = { vm.markActive(item.id) }, modifier = Modifier.weight(1f)) { Text("Not posted") }
                }
                Status.SOLD -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { deleter(listOf(item.id)) }, modifier = Modifier.weight(1f)) { Text("Delete item & photos") }
                    OutlinedButton(onClick = { vm.markPosted(item.id) }, modifier = Modifier.weight(1f)) { Text("Not sold") }
                }
                else -> OutlinedButton(onClick = { vm.markPosted(item.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Mark as posted")
                }
            }
            if (index in 0 until state.items.lastIndex) {
                TextButton(onClick = { vm.mergeWithNext(item.id) }, enabled = idle) { Text("Merge with next item") }
            }
        }
    }
}

@Composable
private fun PhotoThumb(
    photo: Photo,
    index: Int,
    enabled: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onCover: () -> Unit,
    onMove: (MoveTarget) -> Unit,
    onRemove: () -> Unit,
    onRemoveBackground: () -> Unit,
    onRestore: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AsyncImage(
            model = Uri.parse(photo.shown),
            contentDescription = "Photo ${index + 1}",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { menu = true },
        )
        if (index == 0) {
            Text(
                "Cover",
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (index != 0) DropdownMenuItem(text = { Text("Make cover") }, onClick = { menu = false; onCover() })
            DropdownMenuItem(text = { Text("Move to a new item") }, onClick = { menu = false; onMove(MoveTarget.NEW) })
            if (hasPrevious) DropdownMenuItem(text = { Text("Move to previous item") }, onClick = { menu = false; onMove(MoveTarget.PREVIOUS) })
            if (hasNext) DropdownMenuItem(text = { Text("Move to next item") }, onClick = { menu = false; onMove(MoveTarget.NEXT) })
            DropdownMenuItem(
                text = { Text(if (photo.cutout == null) "Remove background" else "Redo background") },
                onClick = { menu = false; onRemoveBackground() },
            )
            if (photo.cutout != null) DropdownMenuItem(text = { Text("Restore original background") }, onClick = { menu = false; onRestore() })
            DropdownMenuItem(text = { Text("Remove from item") }, onClick = { menu = false; onRemove() })
        }
    }
}

// ---- settings -------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(state: UiState, vm: AppViewModel, onBack: () -> Unit) {
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    val context = LocalContext.current
    val idle = state.busy == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCards(state, vm)

            ModelSection(state, vm, onImportFile = { modelPicker.launch(arrayOf("*/*")) })

            HorizontalDivider()
            PrivacySection(state, vm)

            HorizontalDivider()
            Text("AI writing style", style = MaterialTheme.typography.titleMedium)
            Text(
                "Applies to every description the AI writes. Facts still come only from the photos, codes and your notes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                STYLE_PRESETS.forEach { (label, text) ->
                    FilterChip(
                        selected = state.settings.writingStyle == text,
                        onClick = { vm.updateSettings { it.copy(writingStyle = text) } },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = state.settings.writingStyle,
                onValueChange = { v -> vm.updateSettings { it.copy(writingStyle = v.take(1000)) } },
                label = { Text("Style instructions") },
                placeholder = { Text("e.g. Short and casual. Mention it's from a smoke-free home. Use Australian spelling.") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            SettingSwitch(
                title = "Write in the background",
                detail = "After you take photos, the AI writes the new descriptions on its own. Leave the app if you like; " +
                    "a notification says when they're ready.",
                checked = state.settings.autoWrite,
                onChange = { on -> vm.updateSettings { it.copy(autoWrite = on) } },
            )
            SettingSwitch(
                title = "Look up barcodes online",
                detail = "Only when the AI can't tell what an item is. Sends just the barcode number (not photos) over HTTPS " +
                    "to upcitemdb.com, Open Food Facts or Open Library (books).",
                checked = state.settings.onlineLookup,
                onChange = { on -> vm.updateSettings { it.copy(onlineLookup = on) } },
            )

            HorizontalDivider()
            Text("Location", style = MaterialTheme.typography.titleMedium)
            var picking by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Text(Regions.describe(state.settings), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "Sets the currency for prices and the spelling and units in descriptions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (picking) LocationPicker(
                onPicked = { loc ->
                    picking = false
                    vm.updateSettings { it.copy(region = loc.country, area = loc.area, areaName = loc.areaName, city = loc.city) }
                },
                onDismiss = { picking = false },
            )

            HorizontalDivider()
            Text("Price search", style = MaterialTheme.typography.titleMedium)
            var keysDialog by remember { mutableStateOf(false) }
            Text(
                if (state.hasPriceKey) "Google AI search is set up (key stored encrypted). Sends an item's title, condition and your location when you tap Find price."
                else "Optional: Google's AI search looks up what an item sells for second-hand where you live.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { keysDialog = true }) { Text(if (state.hasPriceKey) "Change API key" else "Set up price search") }
                if (state.hasPriceKey) TextButton(onClick = { vm.savePriceKey("") }) { Text("Remove key") }
            }
            if (keysDialog) PriceKeyDialog(vm, onDone = { keysDialog = false })

            HorizontalDivider()
            Text("Updates", style = MaterialTheme.typography.titleMedium)
            SettingSwitch(
                title = "Update automatically",
                detail = "Checks this app's GitHub releases, downloads a new build and installs it when you leave the app. " +
                    "Only builds signed with the same key as this one are accepted.",
                checked = state.settings.autoUpdate,
                onChange = { on -> vm.updateSettings { it.copy(autoUpdate = on) } },
            )
            OutlinedButton(onClick = { vm.checkForUpdate() }, enabled = !state.updatePopup) {
                Text("Check for updates (now on build ${remember { com.listingprep.update.Updater.installedBuild(context) }})")
            }

            HorizontalDivider()
            Text("Listing defaults", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.settings.footer,
                onValueChange = { v -> vm.updateSettings { it.copy(footer = v) } },
                label = { Text("Footer added to every description") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Default condition for new items", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CONDITIONS.forEach { c ->
                    FilterChip(
                        selected = state.settings.defaultCondition == c,
                        onClick = { vm.updateSettings { it.copy(defaultCondition = c) } },
                        label = { Text(c) },
                    )
                }
            }
        }
    }
}

private val STYLE_PRESETS = listOf(
    "Default" to "",
    "Short" to "Keep it short: one sentence, then at most 3 bullet points.",
    "Detailed" to "Be thorough: describe features, size and condition in detail, with up to 6 bullet points.",
    "Casual" to "Write casually and warmly, like a friendly neighbour selling something.",
    "Professional" to "Write in a clear, professional tone like a small shop listing.",
)

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun UpdateCard(update: UpdateUi, vm: AppViewModel) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(update.label, style = MaterialTheme.typography.bodyMedium)
            if (update.notes.isNotBlank()) {
                Text(update.notes.lineSequence().first(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            update.progress?.let { p -> if (!update.ready) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) }
            if (update.ready) {
                if (vm.canInstallUpdates()) {
                    Button(onClick = vm::openUpdatePopup) { Text("See what's new & install") }
                } else {
                    Text(
                        "One-time step: allow Listing Prep to install its own updates.",
                        style = MaterialTheme.typography.bodySmall,
                    )
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

@Composable
private fun UpdatePopup(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val u = state.update
    AlertDialog(
        onDismissRequest = vm::closeUpdatePopup,
        title = { Text("App update") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(u?.label ?: state.updateNote.orEmpty())
                if (u != null && u.notes.isNotBlank()) {
                    Text(if (u.ready) "What's changing in this update" else "What's new", style = MaterialTheme.typography.titleSmall)
                    Text(
                        u.notes,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    )
                }
                if (u != null && !u.ready) {
                    val p = u.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (u == null && state.updateNote == "Checking for updates…") {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (u?.ready == true && !vm.canInstallUpdates()) {
                    Text("One-time step: allow Listing Prep to install its own updates.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            when {
                u?.ready == true && vm.canInstallUpdates() -> Row {
                    TextButton(onClick = vm::installWhenLeaving) { Text("When I leave") }
                    TextButton(onClick = { vm.closeUpdatePopup(); vm.installUpdate() }) { Text("Install now") }
                }
                u?.ready == true -> TextButton(onClick = {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }) { Text("Allow updates") }
                else -> TextButton(onClick = vm::closeUpdatePopup) { Text(if (u != null) "Hide" else "OK") }
            }
        },
        dismissButton = if (u?.ready == true) ({ TextButton(onClick = vm::closeUpdatePopup) { Text("Not now") } }) else null,
    )
}

/** One-time setup for the price search: the user's own free Google AI Studio (Gemini) API key. */
@Composable
private fun PriceKeyDialog(vm: AppViewModel, onDone: (saved: Boolean) -> Unit) {
    val context = LocalContext.current
    // Plain remember: rememberSaveable would copy the key into saved instance state.
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Price search setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Prices are looked up with Google's AI search (Gemini with Google Search). It needs a free API key:\n" +
                        "1. Open Google AI Studio and sign in.\n" +
                        "2. Tap Create API key.\n" +
                        "3. Paste it here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                }) { Text("Open Google AI Studio") }
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, singleLine = true,
                    label = { Text("API key") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Stored encrypted on this phone and sent only to Google's Gemini API. A search sends the item's title, " +
                        "condition and your location (no photos). On Google's free tier, Google may use requests to improve its products.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.savePriceKey(key); onDone(true) }, enabled = key.length > 20) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } },
    )
}

/** Pick a tone (or describe your own) for "Rewrite in another tone". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToneDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rewrite in another tone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Same facts, new wording. Undo brings the old text back.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STYLE_PRESETS.filter { it.second.isNotBlank() }.forEach { (label, text) ->
                        FilterChip(selected = false, onClick = { onPick(text) }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.take(300) },
                    label = { Text("Or describe the tone") },
                    placeholder = { Text("e.g. enthusiastic, mention it's barely used") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(custom) }, enabled = custom.isNotBlank()) { Text("Rewrite") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
