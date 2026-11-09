package com.listingprep.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.listingprep.data.Item
import com.listingprep.media.MediaInfo
import com.listingprep.ml.BackgroundRemover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private data class Shot(val itemId: String, val index: Int, val uri: String)
private data class Cut(val file: File, val width: Int, val height: Int)

/**
 * Shown after taking photos: a preview of each photo with a white background, so you can
 * clean this photo, every photo, or none. The item is found from the centre of the photo;
 * tap the preview to point at it if it picked the wrong thing.
 */
@Composable
fun BackgroundReviewScreen(items: List<Item>, vm: AppViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shots = remember(items) { items.flatMap { item -> item.photos.mapIndexed { i, p -> Shot(item.id, i, p.uri) } } }
    var current by remember { mutableIntStateOf(0) }
    val cuts = remember { mutableStateMapOf<Int, Cut?>() } // null = failed
    val chosen = remember { mutableStateMapOf<Int, Boolean>() }
    var showOriginal by remember { mutableStateOf(false) }
    val remover = remember { runCatching { BackgroundRemover(context) }.getOrNull() }
    val lock = remember { Mutex() }
    val scratch = remember { File(context.cacheDir, "cutouts").apply { mkdirs() } }

    DisposableEffect(Unit) {
        onDispose {
            remover?.close()
            scratch.listFiles()?.forEach { it.delete() } // previews you didn't keep
        }
    }

    suspend fun compute(i: Int, x: Float = 0.5f, y: Float = 0.5f) = lock.withLock {
        val r = remover ?: return@withLock
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val src: Bitmap = MediaInfo.decode(context.contentResolver, Uri.parse(shots[i].uri), 1600)
                val out = r.whiteBackground(src, x, y)
                src.recycle()
                val f = File(scratch, "${UUID.randomUUID()}.jpg")
                f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                Cut(f, out.width, out.height).also { out.recycle() }
            }.getOrNull()
        }
        cuts[i]?.file?.delete()
        cuts[i] = result
    }

    // Previews for every photo, in order, in the background.
    LaunchedEffect(shots) { shots.indices.forEach { if (!cuts.containsKey(it)) compute(it) } }

    fun finish(apply: Set<Int>) {
        apply.forEach { i ->
            val cut = cuts[i] ?: return@forEach
            val kept = File(cameraPhotoDir(context), "cutout-${UUID.randomUUID()}.jpg")
            if (cut.file.renameTo(kept) || runCatching { cut.file.copyTo(kept); true }.getOrDefault(false)) {
                vm.setCutout(shots[i].itemId, shots[i].index, kept)
            }
        }
        onDone()
    }
    BackHandler { finish(emptySet()) }

    if (remover == null || shots.isEmpty()) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(12.dp)) {
        Text("Clean up backgrounds?", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(
            "Preview with a white background. Wrong part picked? Tap the item in the photo.",
            color = Color(0xFFBDC1C6),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val cut = cuts[current]
            val computed = cuts.containsKey(current)
            if (showOriginal || (computed && cut == null)) {
                AsyncImage(model = Uri.parse(shots[current].uri), contentDescription = "Original", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else if (cut != null) {
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val scale = minOf(boxW / cut.width, boxH / cut.height)
                val ox = (boxW - cut.width * scale) / 2
                val oy = (boxH - cut.height * scale) / 2
                AsyncImage(
                    model = Uri.fromFile(cut.file),
                    contentDescription = "Preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(cut) {
                        detectTapGestures { pos ->
                            val x = (pos.x - ox) / (cut.width * scale)
                            val y = (pos.y - oy) / (cut.height * scale)
                            if (x in 0f..1f && y in 0f..1f) scope.launch { cuts.remove(current); compute(current, x, y) }
                        }
                    },
                )
            }
            if (!computed && !showOriginal) CircularProgressIndicator()
            if (computed && cut == null) Text("Couldn't find the item in this photo", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Photo ${current + 1} of ${shots.size}", color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { showOriginal = !showOriginal }) {
                Text(if (showOriginal) "Show preview" else "Show original", color = Color.White)
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            itemsIndexed(shots) { i, shot ->
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(if (i == current) 3.dp else 0.dp, Color.White, RoundedCornerShape(8.dp))
                        .clickable { current = i },
                ) {
                    AsyncImage(model = Uri.parse(shot.uri), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (chosen[i] == true) Text(
                        "✓",
                        color = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { finish(chosen.filterValues { it }.keys) }, modifier = Modifier.weight(1f)) {
                val n = chosen.count { it.value }
                Text(if (n == 0) "Don't use" else "Done ($n)", color = Color.White)
            }
            OutlinedButton(
                onClick = {
                    chosen[current] = chosen[current] != true
                    if (chosen[current] == true && current < shots.lastIndex) current++
                },
                enabled = cuts[current] != null,
                modifier = Modifier.weight(1f),
            ) { Text(if (chosen[current] == true) "Undo this one" else "This photo", color = Color.White) }
            Button(
                onClick = {
                    scope.launch {
                        shots.indices.forEach { if (!cuts.containsKey(it)) compute(it) }
                        finish(shots.indices.filter { cuts[it] != null }.toSet())
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("All photos") }
        }
    }
}
