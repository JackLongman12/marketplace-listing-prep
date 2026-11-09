package com.listingprep.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.listingprep.data.Photo
import com.listingprep.media.MediaInfo
import com.listingprep.ml.BackgroundRemover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Tap the item in the photo; the background turns white. Save keeps both versions, so
 * "Restore original" in the photo menu brings the old one back.
 */
@Composable
fun BackgroundDialog(photo: Photo, onSave: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val remover = remember { runCatching { BackgroundRemover(context) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { remover?.close() } }

    LaunchedEffect(photo.uri) {
        original = withContext(Dispatchers.IO) {
            runCatching { MediaInfo.decode(context.contentResolver, Uri.parse(photo.uri), 1600) }.getOrNull()
        }
        if (original == null) failed = "Couldn't open this photo."
    }

    fun cutAt(x: Float, y: Float) {
        val src = original ?: return
        if (remover == null || working) return
        working = true
        scope.launch {
            val out = withContext(Dispatchers.Default) { runCatching { remover.whiteBackground(src, x, y) }.getOrNull() }
            working = false
            if (out == null) failed = "Background removal failed on this photo." else { failed = null; result = out }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(12.dp)) {
            Text(
                failed ?: if (result == null) "Tap the item you're selling" else "Tap again to pick a different spot",
                color = Color.White,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shown = result ?: original
                if (shown != null) {
                    val density = LocalDensity.current
                    val boxW = with(density) { maxWidth.toPx() }
                    val boxH = with(density) { maxHeight.toPx() }
                    val scale = minOf(boxW / shown.width, boxH / shown.height)
                    val ox = (boxW - shown.width * scale) / 2
                    val oy = (boxH - shown.height * scale) / 2
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "Photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().pointerInput(shown) {
                            detectTapGestures { pos ->
                                val x = (pos.x - ox) / (shown.width * scale)
                                val y = (pos.y - oy) / (shown.height * scale)
                                if (x in 0f..1f && y in 0f..1f) cutAt(x, y)
                            }
                        },
                    )
                }
                if (working || (original == null && failed == null)) CircularProgressIndicator()
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel", color = Color.White) }
                if (result != null) OutlinedButton(onClick = { result = null }, modifier = Modifier.weight(1f)) {
                    Text("Original", color = Color.White)
                }
                Button(
                    onClick = {
                        val out = result ?: return@Button
                        scope.launch {
                            val file = File(cameraPhotoDir(context), "cutout-${UUID.randomUUID()}.jpg")
                            val ok = withContext(Dispatchers.IO) {
                                runCatching { file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) } }.getOrDefault(false)
                            }
                            if (ok) onSave(file) else failed = "Couldn't save the photo."
                        }
                    },
                    enabled = result != null && !working,
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}
