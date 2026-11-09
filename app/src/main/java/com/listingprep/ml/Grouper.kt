package com.listingprep.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.listingprep.data.Photo
import java.io.Closeable

/** Turns a photo into a vector; similar-looking photos get similar vectors. Runs on-device. */
class Embedder(context: Context) : Closeable {
    private val embedder = ImageEmbedder.createFromOptions(
        context,
        ImageEmbedder.ImageEmbedderOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("mobilenet_v3_small.tflite").build())
            .setL2Normalize(true)
            .setRunningMode(RunningMode.IMAGE)
            .build(),
    )

    fun embed(bmp: Bitmap): FloatArray =
        embedder.embed(BitmapImageBuilder(bmp).build())
            .embeddingResult().embeddings()[0].floatEmbedding()

    override fun close() = embedder.close()
}

class Analyzed(val photo: Photo, val embedding: FloatArray)

object Grouper {

    /**
     * Walks photos in the order they were taken and starts a new item when a photo
     * looks different from the current item or there's a long pause between shots.
     */
    fun group(photos: List<Analyzed>): List<List<Analyzed>> {
        val sorted = photos.sortedBy { it.photo.takenAt }
        val groups = mutableListOf<MutableList<Analyzed>>()
        for (p in sorted) {
            val current = groups.lastOrNull()
            if (current == null || !belongsTo(p, current)) {
                groups += mutableListOf(p)
            } else {
                current += p
            }
        }
        return groups
    }

    private fun belongsTo(p: Analyzed, group: List<Analyzed>): Boolean {
        val last = group.last()
        val bothTimed = p.photo.takenAt > 0 && last.photo.takenAt > 0
        val gapSec = if (bothTimed) (p.photo.takenAt - last.photo.takenAt) / 1000 else Long.MAX_VALUE
        // Compare with the last few photos so a different angle still matches.
        val sim = group.takeLast(3).maxOf { cosine(it.embedding, p.embedding) }
        return when {
            gapSec in 0..20 -> sim >= 0.55f
            gapSec in 0..90 -> sim >= 0.68f
            gapSec in 0..600 -> sim >= 0.80f
            !bothTimed -> sim >= 0.75f
            else -> false
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot // vectors are L2-normalized
    }

    /** Sharpest photo first (cover), the rest in shooting order. */
    fun orderWithCover(photos: List<Photo>): List<Photo> {
        val cover = photos.maxByOrNull { it.sharpness } ?: return photos
        return listOf(cover) + photos.filter { it !== cover }.sortedBy { it.takenAt }
    }
}
