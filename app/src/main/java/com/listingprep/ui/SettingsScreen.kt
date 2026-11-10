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

// ---- settings -------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(state: UiState, vm: AppViewModel, onBack: () -> Unit) {
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
            Text("Google AI (optional)", style = MaterialTheme.typography.titleMedium)
            var keysDialog by remember { mutableStateOf(false) }
            Text(
                if (state.hasPriceKey) "Google AI key saved (encrypted). Used for the price search, and for writing if you turn that on below."
                else "Optional: a free Google AI Studio key enables the second-hand price search, and optional writing with Google.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { keysDialog = true }) { Text(if (state.hasPriceKey) "Change API key" else "Set up price search") }
                if (state.hasPriceKey) TextButton(onClick = { vm.savePriceKey("") }) { Text("Remove key") }
            }
            if (keysDialog) PriceKeyDialog(vm, onDone = { keysDialog = false })
            HorizontalDivider()
            Text("Phone's built-in AI", style = MaterialTheme.typography.titleMedium)
            SettingSwitch(
                title = "Use Gemini Nano when available",
                detail = when (state.nano) {
                    com.listingprep.ml.Nano.State.READY -> "Ready on this phone. Runs on the phone's AI chip, nothing is uploaded, no extra download."
                    com.listingprep.ml.Nano.State.DOWNLOADING -> "Android is downloading it (it manages this itself)…"
                    com.listingprep.ml.Nano.State.DOWNLOADABLE -> "This phone supports it; Android will download it when this is on."
                    com.listingprep.ml.Nano.State.UNAVAILABLE -> "Not available on this phone (or Android hasn't enabled it yet). The 4.4 GB model or cloud writing is used instead."
                    null -> "Checking…"
                },
                checked = state.settings.useNano,
                onChange = { on -> vm.updateSettings { it.copy(useNano = on) }; if (on) vm.refreshNano(download = true) },
            )
            TextButton(onClick = { vm.refreshNano(download = state.settings.useNano) }) { Text("Check again") }

            HorizontalDivider()
            Text("Cloud writing (optional)", style = MaterialTheme.typography.titleMedium)
            val provider = com.listingprep.ml.Provider.of(state.settings.writingProvider)
            val hasKey = provider.id in state.providerKeys
            var confirmCloud by remember { mutableStateOf(false) }
            var providerKeyDialog by remember { mutableStateOf(false) }
            SettingSwitch(
                title = "Write descriptions with a cloud AI",
                detail = "Faster and usually better than the phone, but up to 3 photos per item, the text read from them, your notes " +
                    "and location are sent to the provider you pick. If its limit is reached or you're offline, the phone writes instead.",
                checked = state.settings.cloudWriting,
                onChange = { on -> if (on) confirmCloud = true else vm.updateSettings { it.copy(cloudWriting = false) } },
            )
            Text("Provider", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                com.listingprep.ml.Provider.entries.forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = { vm.updateSettings { it.copy(writingProvider = p.id, writingModel = "") } },
                        label = { Text(p.label + if (p.id in state.providerKeys) " ✓" else "") },
                    )
                }
            }
            Text(provider.keyHelp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { providerKeyDialog = true }) { Text(if (hasKey) "Change ${provider.label} key" else "Add ${provider.label} key") }
                if (hasKey) TextButton(onClick = { vm.saveProviderKey(provider.id, "") }) { Text("Remove") }
            }
            if (provider != com.listingprep.ml.Provider.GEMINI) {
                OutlinedTextField(
                    value = state.settings.writingModel,
                    onValueChange = { v -> vm.updateSettings { it.copy(writingModel = v.trim().take(100)) } },
                    label = { Text("Model (blank = ${provider.defaultModel})") },
                    supportingText = { Text("Model names change; if you get \"model not found\", enter a current one from the provider's site.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (providerKeyDialog) ProviderKeyDialog(provider, vm, onDone = { providerKeyDialog = false })
            if (confirmCloud) AlertDialog(
                onDismissRequest = { confirmCloud = false },
                title = { Text("Send photos to ${provider.label}?") },
                text = {
                    Text(
                        "With this on, writing a description uploads up to 3 of the item's photos (re-saved without location data), " +
                            "the text read from them, your notes and your location to ${provider.label}. Free tiers may use what you " +
                            "send to improve their products. You can switch back to on-phone writing any time." +
                            if (!hasKey) "\n\nAdd a ${provider.label} key first." else ""
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { confirmCloud = false; vm.updateSettings { it.copy(cloudWriting = true) } },
                        enabled = hasKey,
                    ) { Text("Turn on") }
                },
                dismissButton = { TextButton(onClick = { confirmCloud = false }) { Text("Cancel") } },
            )

            HorizontalDivider()
            Text("Posting", style = MaterialTheme.typography.titleMedium)
            SettingSwitch(
                title = "Open the listing form in the browser",
                detail = if (state.settings.listingInBrowser) "Share opens Facebook's Create listing form in your browser (sign in there once)."
                else "Share opens the Facebook app on Marketplace; tap Sell there to start the listing.",
                checked = state.settings.listingInBrowser,
                onChange = { on -> vm.updateSettings { it.copy(listingInBrowser = on) } },
            )

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


/** One-time setup for the price search: the user's own free Google AI Studio (Gemini) API key. */
@Composable
internal fun PriceKeyDialog(vm: AppViewModel, onDone: (saved: Boolean) -> Unit) {
    val context = LocalContext.current
    // Plain remember: rememberSaveable would copy the key into saved instance state.
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text("Google AI key") },
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


/** Saves the API key for a cloud provider (stored encrypted on the phone). */
@Composable
internal fun ProviderKeyDialog(provider: com.listingprep.ml.Provider, vm: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    // Plain remember: rememberSaveable would copy the key into saved instance state.
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("${provider.label} key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(provider.keyHelp, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(provider.keyPage)))
                }) { Text("Get a key") }
                OutlinedTextField(
                    value = key, onValueChange = { key = it.trim() }, singleLine = true,
                    label = { Text("API key") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Stored encrypted on this phone and sent only to ${provider.host}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.saveProviderKey(provider.id, key); onDone() }, enabled = key.length > 15) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
