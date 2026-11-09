package com.listingprep.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.listingprep.data.Item
import java.io.OutputStream

/**
 * Gets an item's photos out of the app, cover first, so Facebook can pick them up.
 *
 * Privacy: every exported photo is a fresh JPEG re-encoded from pixels, so it carries
 * no EXIF at all: no GPS location (your home), no camera serial, no timestamps.
 */
object Exporter {
    private const val ALBUM = "Pictures/ListingPrep"
    private const val MAX_DIM = 2048 // Marketplace shows photos at up to 2048 px anyway.

    private fun writeCleanJpeg(cr: ContentResolver, src: Uri, out: OutputStream): Boolean = runCatching {
        val bmp = MediaInfo.decode(cr, src, MAX_DIM) // rotation is applied to the pixels
        try {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        } finally {
            bmp.recycle()
        }
    }.getOrDefault(false)

    /**
     * Saves clean copies into Pictures/ListingPrep. The cover gets the newest timestamp,
     * so it shows first in Facebook's photo picker (newest-first).
     */
    fun saveToAlbum(context: Context, item: Item): List<Uri> {
        val cr = context.contentResolver
        val now = System.currentTimeMillis()
        val n = item.photos.size
        val saved = mutableListOf<Uri>()
        item.photos.forEachIndexed { i, p ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "listing_${now}_${i + 1}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, ALBUM)
                put(MediaStore.Images.Media.DATE_TAKEN, now + (n - i) * 1000L)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val dest = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
            val ok = runCatching {
                cr.openOutputStream(dest)!!.use { writeCleanJpeg(cr, Uri.parse(p.uri), it) }
            }.getOrDefault(false)
            if (ok) {
                cr.update(dest, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                saved += dest
            } else {
                cr.delete(dest, null, null)
            }
        }
        return saved
    }

    /** Removes copies this app saved earlier (the app owns them, so no prompt is needed). */
    fun deleteExported(context: Context, uris: List<String>) {
        uris.forEach { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
    }

    /**
     * Deletes every image this app ever put in Pictures/ListingPrep, including ones from
     * items that were since deleted. Android keeps shared-gallery files after uninstall,
     * so this is how you leave nothing behind.
     */
    fun deleteAllOwned(context: Context): Int {
        val cr = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        var deleted = 0
        cr.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf("$ALBUM%", context.packageName),
            null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (c.moveToNext()) {
                val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(idCol))
                deleted += runCatching { cr.delete(uri, null, null) }.getOrDefault(0)
            }
        }
        return deleted
    }
}
