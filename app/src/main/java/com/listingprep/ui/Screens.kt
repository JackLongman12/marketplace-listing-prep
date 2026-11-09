package com.listingprep.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Switch
import com.listingprep.data.readyToShare
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.listingprep.data.CONDITIONS
import com.listingprep.data.Item
import com.listingprep.data.Photo
import com.listingprep.data.Status
import com.listingprep.data.listingDescription
import com.listingprep.media.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val SETTINGS = "__settings__"

@Composable
fun App(
    postedId: String? = null,
    onPostedHandled: () -> Unit = {},
    vm: AppViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = screen != null) { screen = null }

    // "Done ✓" on the floating panel: mark posted and go back to the list for the next item.
    LaunchedEffect(postedId) {
        val id = postedId ?: return@LaunchedEffect
        vm.markPosted(id)
        screen = null
        onPostedHandled()
    }

    when (val current = screen) {
        null -> ItemListScreen(state, vm, onOpen = { screen = it }, onSettings = { screen = SETTINGS })
        SETTINGS -> SettingsScreen(state, vm, onBack = { screen = null })
        else -> {
            val item = state.items.find { it.id == current }
            if (item == null) LaunchedEffect(current) { screen = null }
            else ItemScreen(item, state, vm, onBack = { screen = null })
        }
    }
}

// ---- shared bits ----------------------------------------------------------

@Composable
private fun StatusCards(state: UiState, vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.busy?.let { label ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
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
    item.status == Status.POSTED -> "Posted"
    item.checked -> "Checked ✓"
    item.status == Status.READY -> "Needs your check"
    else -> "Draft"
}

// ---- list -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemListScreen(state: UiState, vm: AppViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MediaStore.getPickImagesMaxLimit())
    ) { uris -> if (uris.isNotEmpty()) vm.importPhotos(uris) }
    // Full or "selected photos only" access both work; with partial access the app only
    // ever sees the photos you picked in Android's dialog.
    val photoPermissions = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) vm.importToday()
        else vm.showError("Allow photo access to use Today's, or use Pick photos instead.")
    }
    val idle = state.busy == null
    val pending = state.items.count { it.description.isBlank() && it.status != Status.POSTED }
    val share = rememberMarketplaceSharer(vm, state.settings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listing Prep") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        enabled = idle,
                        modifier = Modifier.weight(1f),
                    ) { Text("Pick photos", maxLines = 1) }
                    OutlinedButton(
                        onClick = {
                            val full = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                                PackageManager.PERMISSION_GRANTED
                            // With partial access, ask again so you can add today's new photos to the selection.
                            if (full) vm.importToday() else permission.launch(photoPermissions)
                        },
                        enabled = idle,
                        modifier = Modifier.weight(1f),
                    ) { Text("Today's", maxLines = 1) }
                    Button(
                        onClick = { vm.generateAll() },
                        enabled = idle && state.modelPath != null && pending > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (pending > 0) "Write $pending" else "Write all", maxLines = 1) }
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
            item { StatusCards(state, vm) }
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
                        "Take photos of everything you're selling, one item after another, " +
                            "then tap Today's (or Pick photos). The app sorts them into items.",
                        Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(state.items, key = { _, it -> it.id }) { index, item ->
                Column {
                    ItemCard(
                        item,
                        generating = state.generatingId == item.id,
                        onClick = { onOpen(item.id) },
                        onShare = if (item.readyToShare() && item.status != Status.POSTED) {
                            { share(item) }
                        } else null,
                    )
                    if (index < state.items.lastIndex) {
                        TextButton(
                            onClick = { vm.mergeWithNext(item.id) },
                            enabled = idle,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Merge with item below") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemCard(item: Item, generating: Boolean, onClick: () -> Unit, onShare: (() -> Unit)?) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = item.photos.firstOrNull()?.uri?.let(Uri::parse),
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
                val price = if (item.price.isNotBlank()) " · ${item.price}" else ""
                Text(
                    "$count photo${if (count == 1) "" else "s"} · ${statusLabel(item)}$price",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (generating) {
                    Text("Writing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else if (item.description.isNotBlank()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onShare != null) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}

// ---- item -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ItemScreen(item: Item, state: UiState, vm: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val idle = state.busy == null
    val index = state.items.indexOfFirst { it.id == item.id }
    var confirmDelete by remember { mutableStateOf(false) }
    val share = rememberMarketplaceSharer(vm, state.settings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.title.ifBlank { "Item" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete item") }
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
                    )
                }
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

            Button(
                onClick = { vm.generate(item.id) },
                enabled = idle && state.modelPath != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (item.description.isBlank()) "Write description" else "Rewrite description") }
            if (state.modelPath == null) {
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
            OutlinedTextField(
                value = item.price,
                onValueChange = { v -> vm.updateItem(item.id) { it.copy(price = v) } },
                label = { Text("Price") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = item.description,
                onValueChange = { v -> vm.updateItem(item.id) { it.copy(description = v) } },
                label = { Text("Description") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Copy description adds the condition and your footer from Settings.",
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

            Text("Or do it by hand", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(item.title)) },
                    enabled = item.title.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Copy title") }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(item.listingDescription(state.settings))) },
                    modifier = Modifier.weight(1f),
                ) { Text("Copy description") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) {
                                Exporter.deleteExported(context, item.exportedUris)
                                Exporter.saveToAlbum(context, item)
                            }
                            vm.updateItem(item.id) { it.copy(exportedUris = saved.map { u -> u.toString() }) }
                            Toast.makeText(context, "Saved ${saved.size} photos to Pictures/ListingPrep", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save to album") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val intent = withContext(Dispatchers.IO) { Exporter.shareIntent(context, item) }
                            if (intent != null) context.startActivity(intent)
                            else Toast.makeText(context, "Couldn't read the photos", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Share photos") }
            }
            Button(
                onClick = {
                    if (item.status == Status.POSTED) vm.updateItem(item.id) { it.copy(status = Status.READY) }
                    else vm.markPosted(item.id)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (item.status == Status.POSTED) "Mark as not posted" else "Mark as posted") }
            if (index in 0 until state.items.lastIndex) {
                TextButton(onClick = { vm.mergeWithNext(item.id) }, enabled = idle) { Text("Merge with next item") }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this item?") },
            text = { Text("Your original photos stay in your gallery.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteItem(item.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
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
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        AsyncImage(
            model = Uri.parse(photo.uri),
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
