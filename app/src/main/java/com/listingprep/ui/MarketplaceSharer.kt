package com.listingprep.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.listingprep.data.Item
import com.listingprep.data.Settings
import com.listingprep.data.listingDescription
import com.listingprep.data.readyToShare
import com.listingprep.media.Exporter
import com.listingprep.share.MarketplaceShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Returns a function that shares one item to Facebook Marketplace. Shows a one-time
 * prompt for the "Display over other apps" permission the copy panel needs.
 */
@Composable
fun rememberMarketplaceSharer(vm: AppViewModel, settings: Settings): (Item) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentSettings by rememberUpdatedState(settings)
    var askPermission by remember { mutableStateOf(false) }

    if (askPermission) {
        AlertDialog(
            onDismissRequest = { askPermission = false },
            title = { Text("Allow the copy panel") },
            text = {
                Text(
                    "While you post, Listing Prep shows a small floating panel over Facebook with " +
                        "Copy title / price / description buttons. Turn on \"Display over other apps\" " +
                        "for Listing Prep, come back, and tap Share again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askPermission = false
                    context.startActivity(MarketplaceShare.panelPermissionIntent(context))
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { askPermission = false }) { Text("Cancel") } },
        )
    }

    return remember(vm) {
        { item: Item ->
            if (!item.readyToShare()) {
                Toast.makeText(context, "Check the item and fill in title, price and description first.", Toast.LENGTH_SHORT).show()
            } else if (!MarketplaceShare.canShowPanel(context)) {
                askPermission = true
            } else {
                scope.launch {
                    val saved = withContext(Dispatchers.IO) {
                        Exporter.deleteExported(context, item.exportedUris)
                        Exporter.saveToAlbum(context, item)
                    }
                    vm.updateItem(item.id) { it.copy(exportedUris = saved.map { u -> u.toString() }) }
                    val description = item.listingDescription(currentSettings)
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Description", description))
                    MarketplaceShare.startPanel(context, item, description)
                    MarketplaceShare.openMarketplace(context)
                    Toast.makeText(
                        context,
                        "${saved.size} photos saved, cover first. Pick the newest ones in Facebook.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            Unit
        }
    }
}
