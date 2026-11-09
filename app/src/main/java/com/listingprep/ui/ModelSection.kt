package com.listingprep.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.runtime.remember
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.listingprep.ml.ModelDownloader
import java.io.File

/** Shown in Settings: model status, one-tap download, and the one-time Hugging Face setup. */
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
            Text("Ready: ${f.name} (${"%.1f".format(f.length() / 1e9)} GB)")
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

        state.hasHfToken -> {
            var wifiOnly by rememberSaveable { mutableStateOf(true) }
            Text("Gemma 3n writes your descriptions on the phone. It's a one-time 4.4 GB download.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wi-Fi only", Modifier.weight(1f))
                Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
            }
            Button(
                onClick = { vm.downloadModel(wifiOnly) },
                enabled = idle,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download AI model (4.4 GB)") }
            TextButton(onClick = { vm.saveHfToken("") }) { Text("Change Hugging Face token") }
        }

        else -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("One-time setup", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The model is free, but Google requires you to accept its Gemma license on " +
                        "Hugging Face before downloading. After this, downloading is one tap.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("1. Sign in and accept the license (\"Acknowledge license\").", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { open(ModelDownloader.MODEL_PAGE) }) { Text("Open model page") }
                Text("2. Create a Read token and copy it.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { open(ModelDownloader.TOKEN_PAGE) }) { Text("Create token") }
                Text("3. Paste it here.", style = MaterialTheme.typography.bodyMedium)
                // Plain remember: rememberSaveable would copy the token into saved instance state.
                var token by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim() },
                    label = { Text("Hugging Face token (hf_…)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.saveHfToken(token); vm.downloadModel() },
                    enabled = idle && token.startsWith("hf_") && token.length > 10,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save token & download (Wi-Fi)") }
                Text(
                    "The token is stored only on this phone and is only sent to huggingface.co.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
    val idle = state.busy == null
    var confirmErase by remember { mutableStateOf(false) }

    Text("Privacy", style = MaterialTheme.typography.titleMedium)
    Text(
        "Photos, items and AI processing stay on this phone. The app may only connect to " +
            "huggingface.co (to start the model download); every other connection, including " +
            "library telemetry, is blocked. Shared photos are re-saved without location or camera data.\n\n" +
            "Uninstalling removes the model, your items and the token. Android keeps photo copies in " +
            "Pictures/ListingPrep after uninstall, so delete them first if you're removing the app.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = { vm.deleteAllSharedCopies() }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
        Text("Delete shared photo copies")
    }
    OutlinedButton(onClick = { confirmErase = true }, enabled = idle, modifier = Modifier.fillMaxWidth()) {
        Text("Erase everything (before uninstalling)")
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase everything?") },
            text = {
                Text(
                    "Deletes all items, photo copies in Pictures/ListingPrep, the AI model (4.4 GB), " +
                        "your Hugging Face token and settings. Your original photos are not touched. " +
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
