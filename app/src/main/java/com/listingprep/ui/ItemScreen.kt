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

// ---- item -----------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ItemScreen(item: Item, state: UiState, vm: AppViewModel, onBack: () -> Unit) {
    val idle = state.busy == null
    val index = state.items.indexOfFirst { it.id == item.id }
    val share = rememberMarketplaceSharer(vm, state.settings)
    val deleter = rememberItemDeleter(vm)
    var editBackground by remember { mutableStateOf<Int?>(null) }
    var confirmRefresh by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Int?>(null) }
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
                    IconButton(onClick = { confirmRefresh = true }, enabled = idle && state.canWrite) {
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

            Text("Photos: tap to enlarge, long-press (or ⋮) for options. The first is the cover.", style = MaterialTheme.typography.labelLarge)
            viewing?.let { start -> PhotoViewer(item.photos, start, onDismiss = { viewing = null }) }
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
                        onView = { viewing = i },
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
                        onClick = { vm.setCondition(item.id, c) },
                        label = { Text(c) },
                    )
                }
            }

            // "What is it?" Shown prominently when the AI couldn't tell; always editable.
            var identity by remember(item.id, item.identity) { mutableStateOf(item.identity) }
            if (item.unsure) Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Text(
                    "The AI couldn't tell exactly what this is, so the description is generic. Type what it is " +
                        "(brand and model if you know them) and it'll write it again from that" +
                        if (state.hasPriceKey && state.settings.onlineLookup) ", after looking the product up." else ".",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(
                value = identity,
                onValueChange = { identity = it.take(200) },
                label = { Text("What is it? (brand, model)") },
                placeholder = { Text("e.g. Sony WH-1000XM4 headphones") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (identity.isNotBlank() && (identity.trim() != item.identity || item.unsure)) {
                Button(
                    onClick = { vm.identifyAndWrite(item.id, identity) },
                    enabled = idle && state.canWrite,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (item.description.isBlank()) "Write description from this" else "Rewrite description from this") }
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
                enabled = idle && state.canWrite,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Write description") }
            if (!state.canWrite && item.description.isBlank()) {
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
                supportingText = if (item.price.isNotBlank() && item.description.isNotBlank()) ({
                    Text("Starts at the AI's original retail price (MSRP) when it knows the product; set your own.")
                }) else null,
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
                        TextButton(onClick = { toneDialog = true }, enabled = idle && state.canWrite) { Text("Rewrite in another tone") }
                    }
                    TextButton(onClick = { vm.clearDescription(item.id) }, enabled = state.generatingId != item.id) {
                        Text("Delete text")
                    }
                }
                if (toneDialog) ToneDialog(onPick = { tone -> toneDialog = false; vm.rewriteTone(item.id, tone) }, onDismiss = { toneDialog = false })
            }
            Text(
                "Your listing defaults (condition, pickup, payment) are added at the end of the description.",
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotoThumb(
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
    onView: () -> Unit,
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
                .combinedClickable(onClick = onView, onLongClick = { if (enabled) menu = true }),
        )
        Text(
            "⋮",
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .background(Color(0x88000000), RoundedCornerShape(10.dp))
                .clickable(enabled = enabled) { menu = true }
                .padding(horizontal = 8.dp, vertical = 2.dp),
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


/** Pick a tone (or describe your own) for "Rewrite in another tone". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToneDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var tone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rewrite in another tone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick a tone or describe one, then tap Rewrite. Same facts, new wording; Undo brings the old text back.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    STYLE_PRESETS.filter { it.second.isNotBlank() }.forEach { (label, text) ->
                        FilterChip(selected = tone == text, onClick = { tone = if (tone == text) "" else text }, label = { Text(label) })
                    }
                }
                OutlinedTextField(
                    value = tone,
                    onValueChange = { tone = it.take(400) },
                    label = { Text("Tone") },
                    placeholder = { Text("e.g. enthusiastic, mention it's barely used") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(tone) }, enabled = tone.isNotBlank()) { Text("Rewrite") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
