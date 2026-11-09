package com.listingprep.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Size
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Where in-app camera photos live: private to the app, never in the gallery. */
fun cameraPhotoDir(context: Context) = File(context.filesDir, "photos").apply { mkdirs() }

/**
 * Built-in camera. Shoot every angle of one item, tap "Next item", repeat. Each group
 * becomes one item, so there's no gallery picking and no guessing which photos belong together.
 * Pinch to zoom, tap to focus.
 */
@Composable
fun CameraScreen(onFinish: (List<List<File>>) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) onFinish(emptyList())
    }
    LaunchedEffect(Unit) { if (!granted) askCamera.launch(Manifest.permission.CAMERA) }

    // Photos per item; the last group is the one being shot.
    var groups by remember { mutableStateOf(listOf<List<File>>(emptyList())) }
    var saving by remember { mutableIntStateOf(0) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    val shutterFlash = remember { Animatable(0f) }

    fun finish() = onFinish(groups.filter { it.isNotEmpty() })
    BackHandler { finish() }

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            // ~12 MP: plenty for Marketplace and the AI, and fast to save. The 200 MP sensor mode would be ~40 MB a shot.
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                )
                .build()
        }
    }
    DisposableEffect(granted) {
        if (granted) controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    fun shoot() {
        val file = File(cameraPhotoDir(context), "${UUID.randomUUID()}.jpg")
        val group = groups.lastIndex // a shot still saving when you tap Next item stays with its item
        saving++
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        scope.launch {
            shutterFlash.snapTo(0.6f)
            shutterFlash.animateTo(0f, tween(180))
        }
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    saving--
                    groups = groups.mapIndexed { i, g -> if (i == group) g + file else g }
                }

                override fun onError(exception: ImageCaptureException) {
                    saving--
                    file.delete()
                }
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        // FIT so the preview shows exactly what the 4:3 photo will contain.
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        this.controller = controller
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().alpha(shutterFlash.value).background(Color.White))

        val current = groups.last()
        val itemNumber = groups.size
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    controller.imageCaptureFlashMode = flashMode
                }) {
                    Text(
                        when (flashMode) {
                            ImageCapture.FLASH_MODE_AUTO -> "Flash: auto"
                            ImageCapture.FLASH_MODE_ON -> "Flash: on"
                            else -> "Flash: off"
                        },
                        color = Color.White,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "Item $itemNumber · ${current.size} photo${if (current.size == 1) "" else "s"}",
                    color = Color.White,
                    modifier = Modifier
                        .background(Color(0x99000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        current.lastOrNull()?.let { last ->
                            last.delete()
                            groups = groups.dropLast(1) + listOf(current.dropLast(1))
                        }
                    },
                    enabled = current.isNotEmpty(),
                ) { Text("Undo", color = if (current.isNotEmpty()) Color.White else Color.Gray) }
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Last shot of this item (or nothing yet).
                Box(Modifier.size(64.dp)) {
                    current.lastOrNull()?.let { f ->
                        AsyncImage(
                            model = Uri.fromFile(f),
                            contentDescription = "Last photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, Color.White, RoundedCornerShape(10.dp)),
                        )
                    }
                }
                Box(
                    Modifier
                        .size(78.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(enabled = granted) { shoot() },
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(
                        onClick = { groups = groups + listOf(emptyList()) },
                        enabled = current.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) { Text("Next item") }
                    TextButton(onClick = { finish() }, enabled = saving == 0) {
                        Text("Done", color = Color.White)
                    }
                }
            }
        }
    }
}
