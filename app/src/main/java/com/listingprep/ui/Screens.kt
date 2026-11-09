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
fun App(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = screen != null) { screen = null }

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

private fun statusLabel(s: Status) = when (s) {
    Status.DRAFT -> "Draft"
    Status.READY -> "Ready to post"
    Status.POSTED -> "Posted"
}

// ---- list -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemListScreen(state: UiState, vm: AppViewModel, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MediaStore.getPickImagesMaxLimit())
    ) { uris -> if (uris.isNotEmpty()) vm.importPhotos(uris) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.importToday()
        else vm.showError("Allow access to all photos to use Today's, or use Pick photos instead.")
    }
    val idle = state.busy == null
    val pending = state.items.count { it.description.isBlank() && it.status != Status.POSTED }

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
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) vm.importToday() else permission.launch(Manifest.permission.READ_MEDIA_IMAGES)
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
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("No AI model yet", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Photo grouping works now. To write descriptions, add the Gemma model in Settings.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = onSettings) { Text("Set up model") }
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
                    ItemCard(item, generating = state.generatingId == item.id, onClick = { onOpen(item.id) })
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
private fun ItemCard(item: Item, generating: Boolean, onClick: () -> Unit) {
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
                    "$count photo${if (count == 1) "" else "s"} · ${statusLabel(item.status)}$price",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (generating) {
                    Text("Writing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                } else if (item.description.isNotBlank()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                            val n = withContext(Dispatchers.IO) { Exporter.saveToAlbum(context, item) }
                            Toast.makeText(context, "Saved $n photos to Pictures/ListingPrep", Toast.LENGTH_SHORT).show()
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
                    vm.updateItem(item.id) {
                        it.copy(status = if (it.status == Status.POSTED) Status.READY else Status.POSTED)
                    }
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

            Text("AI model", style = MaterialTheme.typography.titleMedium)
            val path = state.modelPath
            if (path != null) {
                val f = File(path)
                Text("Ready: ${f.name} (${"%.1f".format(f.length() / 1e9)} GB)")
            } else {
                Text("No model yet.")
            }
            Text(
                "Descriptions are written by Gemma 3n running on your phone. To add it:\n" +
                    "1. On your phone, open huggingface.co/google/gemma-3n-E4B-it-litert-preview, " +
                    "sign in and accept Google's Gemma license.\n" +
                    "2. Download gemma-3n-E4B-it-int4.task (about 4.4 GB).\n" +
                    "3. Tap Import model file and pick it from Downloads. You can delete the download afterwards.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { modelPicker.launch(arrayOf("*/*")) }, enabled = idle) { Text("Import model file") }
                OutlinedButton(onClick = vm::rescanModel, enabled = idle) { Text("Rescan") }
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
