package com.listingprep.media

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

object MediaInfo {

    /** When the photo was taken, in epoch millis, or 0 if unknown. */
    fun takenAt(cr: ContentResolver, uri: Uri): Long {
        runCatching {
            cr.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    if (idx >= 0 && !c.isNull(idx)) {
                        val v = c.getLong(idx)
                        if (v > 0) return v
                    }
                }
            }
        }
        runCatching {
            cr.openInputStream(uri)?.use { s ->
                val exif = ExifInterface(s)
                val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                if (dt != null) {
                    return SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dt)?.time ?: 0L
                }
            }
        }
        return 0L
    }

    /** Camera photos (DCIM) taken since midnight today, oldest first. */
    fun todayCameraPhotos(cr: ContentResolver): List<Uri> {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val out = mutableListOf<Uri>()
        cr.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
            arrayOf(start.toString(), "DCIM/%"),
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) out += ContentUris.withAppendedId(collection, c.getLong(id))
        }
        return out
    }

    /** Decodes a photo (rotation applied) scaled so its longest side is at most [maxDim]. */
    fun decode(cr: ContentResolver, uri: Uri, maxDim: Int): Bitmap {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = maxDim.toFloat() / maxOf(w, h)
            if (scale < 1f) {
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        }
        return if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Variance of the Laplacian: higher means sharper. Used to pick the cover photo. */
    fun sharpness(bmp: Bitmap): Double {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return 0.0
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val g = FloatArray(w * h) {
            val c = px[it]
            0.299f * ((c shr 16) and 0xff) + 0.587f * ((c shr 8) and 0xff) + 0.114f * (c and 0xff)
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = (g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]).toDouble()
            sum += l
            sumSq += l * l
            n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }
}
