package com.listingprep.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment

/**
 * Returns a function that asks, then deletes items together with all their photos:
 * in-app camera shots, copies saved for Facebook and, if you tick the box, the original
 * gallery photos (Android shows its own confirmation for those).
 */
@Composable
fun rememberItemDeleter(vm: AppViewModel): (ids: List<String>) -> Unit {
    var pending by remember { mutableStateOf<List<String>?>(null) }
    var deleteOriginals by remember { mutableStateOf(true) }
    var afterSystemDialog by remember { mutableStateOf<List<String>?>(null) }

    val systemDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        // Whether you allowed it or not, the items themselves go.
        // Gallery originals can't come back, so this delete has no Undo.
        afterSystemDialog?.let { vm.deleteItems(it, allowUndo = false) }
        afterSystemDialog = null
    }

    pending?.let { ids ->
        val originals = remember(ids) { vm.galleryOriginals(ids) }
        val photos = remember(ids) { vm.photoCount(ids) }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (ids.size == 1) "Delete this item and its photos?" else "Delete ${ids.size} items and their photos?") },
            text = {
                Column {
                    Text(
                        "Removes the item${if (ids.size == 1) "" else "s"}, $photos photo${if (photos == 1) "" else "s"} " +
                            "taken in the app, and the copies saved for Facebook."
                    )
                    if (originals.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteOriginals, onCheckedChange = { deleteOriginals = it })
                            Text("Also delete the ${originals.size} original photo${if (originals.size == 1) "" else "s"} from the gallery")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    val request = if (deleteOriginals) vm.galleryDeleteRequest(originals) else null
                    if (request != null) {
                        afterSystemDialog = ids
                        systemDelete.launch(IntentSenderRequest.Builder(request).build())
                    } else {
                        vm.deleteItems(ids)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    // Nothing in the gallery to ask about: delete straight away, with Undo.
    return remember { { ids -> if (ids.isNotEmpty()) { if (vm.galleryOriginals(ids).isEmpty()) vm.deleteItems(ids) else pending = ids } } }
}
