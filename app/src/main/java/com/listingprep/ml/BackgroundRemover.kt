package com.listingprep.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedKeypoint
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.interactivesegmenter.InteractiveSegmenter
import java.io.Closeable
import java.nio.ByteOrder

/**
 * Tap-to-select background removal, fully on the phone (MediaPipe "magic touch" model,
 * bundled, ~6 MB). You tap the item; everything that isn't it turns white.
 */
class BackgroundRemover(context: Context) : Closeable {
    private val segmenter = InteractiveSegmenter.createFromOptions(
        context,
        InteractiveSegmenter.InteractiveSegmenterOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("magic_touch.tflite").build())
            .setOutputCategoryMask(false)
            .setOutputConfidenceMasks(true)
            .build(),
    )

    /** [x], [y] are where you tapped, 0..1 across the photo. Returns a new bitmap on white. */
    fun whiteBackground(photo: Bitmap, x: Float, y: Float): Bitmap {
        val roi = InteractiveSegmenter.RegionOfInterest.create(NormalizedKeypoint.create(x, y))
        val result = segmenter.segment(BitmapImageBuilder(photo).build(), roi)
        val mask = result.confidenceMasks().get().last()
        val mw = mask.width
        val mh = mask.height
        val conf = ByteBufferExtractor.extract(mask).order(ByteOrder.nativeOrder()).asFloatBuffer()

        val w = photo.width
        val h = photo.height
        val px = IntArray(w * h)
        photo.getPixels(px, 0, w, 0, 0, w, h)
        for (j in 0 until h) {
            val my = (j * mh / h).coerceIn(0, mh - 1)
            for (i in 0 until w) {
                val mx = (i * mw / w).coerceIn(0, mw - 1)
                // Soft edge: fully item above 0.65, fully white below 0.35.
                val a = ((conf.get(my * mw + mx) - 0.35f) / 0.3f).coerceIn(0f, 1f)
                if (a >= 1f) continue
                val c = px[j * w + i]
                val r = (((c shr 16) and 0xff) * a + 255 * (1 - a)).toInt()
                val g = (((c shr 8) and 0xff) * a + 255 * (1 - a)).toInt()
                val b = ((c and 0xff) * a + 255 * (1 - a)).toInt()
                px[j * w + i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun close() = segmenter.close()
}
