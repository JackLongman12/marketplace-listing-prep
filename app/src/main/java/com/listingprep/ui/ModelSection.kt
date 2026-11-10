package com.listingprep.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.remember
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.listingprep.ml.ModelDownloader
import java.io.File

/** Shown in Settings: model status, one-tap download, delete. */
@Composable
fun ModelSection(state: UiState, vm: AppViewModel, onImportFile: () -> Unit) {
    val context = LocalContext.current
    val idle = state.busy == null
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    Text("AI model", style = MaterialTheme.typography.titleMedium)

    val path = state.modelPath
    val download = state.download
    when {
        path != null -> {
            val f = File(path)
            var confirmDelete by remember { mutableStateOf(false) }
            Text("Ready: ${f.name} (${"%.1f".format(f.length() / 1e9)} GB)")
            OutlinedButton(onClick = { confirmDelete = true }, enabled = idle) { Text("Delete AI model") }
            if (confirmDelete) AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Delete the AI model?") },
                text = { Text("Frees 4.4 GB. Your items stay. Writing uses Gemini Nano if your phone has it; otherwise you can download the model again any time.") },
                confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteModel() }) { Text("Delete") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            )
        }

        download != null -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(download.label)
                val p = download.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Keeps going if you leave the app. It installs itself when done.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = vm::cancelDownload) { Text("Cancel download") }
            }
        }

        else -> {
            var wifiOnly by rememberSaveable { mutableStateOf(true) }
            Text("Gemma 3n writes your descriptions on the phone. It's a free one-time 4.4 GB download: no account needed.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wi-Fi only", Modifier.weight(1f))
                Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
            }
            Button(
                onClick = { vm.downloadModel(wifiOnly) },
                enabled = idle,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download AI model (4.4 GB)") }
            Text(
                "The file is checked against Google's official fingerprint before it's used. " +
                    "Gemma is provided under Google's Gemma Terms of Use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { open(ModelDownloader.LICENSE_PAGE) }) { Text("Gemma Terms of Use") }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onImportFile, enabled = idle && download == null) { Text("Import model file") }
        OutlinedButton(onClick = { vm.rescanModel() }, enabled = idle) { Text("Rescan") }
    }

    if (state.offerDeleteImportSource != null) {
        AlertDialog(
            onDismissRequest = vm::keepImportSource,
            title = { Text("Model installed") },
            text = { Text("Delete the 4.4 GB original you imported from? The app has its own verified copy now.") },
            confirmButton = { TextButton(onClick = vm::deleteImportSource) { Text("Delete original") } },
            dismissButton = { TextButton(onClick = vm::keepImportSource) { Text("Keep it") } },
        )
    }
}

/** Shown in Settings: what leaves the phone, and buttons to remove everything. */
@Composable
fun PrivacySection(state: UiState, vm: AppViewModel) {
    var confirmErase by remember { mutableStateOf(false) }

    Text("Privacy", style = MaterialTheme.typography.titleMedium)
    Text(
        "Photos, items and AI writing stay on this phone. The app only connects to Hugging Face " +
            "(model download), GitHub (app updates) and, when you use them, the barcode and price " +
            "lookups; everything else, including library telemetry, is blocked. Shared photos are " +
            "re-saved without location or camera data.\n\n" +
            "Installing a new build over this one keeps everything, including the AI model. When you " +
            "uninstall, Android asks whether to keep app data: tick it to keep the model for the next " +
            "install, leave it unticked to remove the model, items and keys. Android keeps photo copies " +
            "in Pictures/ListingPrep after uninstall, so delete them first if you're removing the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = { vm.deleteAllSharedCopies() }, modifier = Modifier.fillMaxWidth()) {
        Text("Delete shared photo copies")
    }
    OutlinedButton(onClick = { confirmErase = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Erase everything (before uninstalling)")
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase everything?") },
            text = {
                Text(
                    "Deletes all items, photo copies in Pictures/ListingPrep, the AI model (4.4 GB), " +
                        "your price-search key and settings. Your original photos are not touched. " +
                        "This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmErase = false; vm.eraseEverything() }) { Text("Erase") }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } },
        )
    }
}
