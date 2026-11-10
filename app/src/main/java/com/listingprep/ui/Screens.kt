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

private const val SETTINGS = "__settings__"
private const val CAMERA = "__camera__"
private const val REVIEW = "__review__"
private const val RESHOOT = "__reshoot__"

@Composable
fun App(
    postedId: String? = null,
    onPostedHandled: () -> Unit = {},
    vm: AppViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Photo being re-shot: item id and photo position; the camera returns to that item.
    var reshootId by rememberSaveable { mutableStateOf<String?>(null) }
    var reshootIndex by rememberSaveable { mutableStateOf(0) }
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
        null -> ItemListScreen(state, vm, onOpen = { screen = it }, onSettings = { screen = SETTINGS }, onCamera = { vm.cameraOpened(); screen = CAMERA })
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
        RESHOOT -> CameraScreen(single = true, onFinish = { groups ->
            val id = reshootId
            groups.firstOrNull()?.firstOrNull()?.let { file -> if (id != null) vm.replacePhoto(id, reshootIndex, file) }
            screen = id
        })
        else -> {
            val item = state.items.find { it.id == current }
            if (item == null) LaunchedEffect(current) { screen = null }
            else ItemScreen(item, state, vm, onBack = { screen = null }, onReshoot = { i ->
                reshootId = item.id; reshootIndex = i; vm.cameraOpened(); screen = RESHOOT
            })
        }
    }
    if (state.updatePopup) UpdatePopup(state, vm)
    state.crashReport?.let { report ->
        AlertDialog(
            onDismissRequest = vm::dismissCrashReport,
            title = { Text("Snaplist closed unexpectedly") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sorry about that. Copy the crash report and send it to the developer so it can be fixed. It only has the app build, Android version, phone model, the day, and which lines of code failed: no photos, listing text, error messages or anything about you.")
                    Text(
                        report.lineSequence().take(4).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Crash report", report))
                    vm.dismissCrashReport()
                }) { Text("Copy report") }
            },
            dismissButton = { TextButton(onClick = vm::dismissCrashReport) { Text("Dismiss") } },
        )
    }
    state.justUpdated?.let { u ->
        AlertDialog(
            onDismissRequest = vm::dismissJustUpdated,
            title = { Text("App updated ✓") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(u.label)
                    if (u.notes.isNotBlank()) {
                        Text("What changed", style = MaterialTheme.typography.titleSmall)
                        Text(
                            u.notes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismissJustUpdated) { Text("OK") } },
        )
    }
    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp))
    }
}


@Composable
internal fun UpdatePopup(state: UiState, vm: AppViewModel) {
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
                    Text("One-time step: allow Snaplist to install its own updates.", style = MaterialTheme.typography.bodySmall)
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
