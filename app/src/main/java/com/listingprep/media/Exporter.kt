package com.listingprep.media

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.listingprep.data.Item
import java.io.File

/** Gets an item's photos out of the app, cover first, so Facebook can pick them up. */
object Exporter {

    /**
     * Copies the photos into Pictures/ListingPrep. The cover gets the newest timestamp,
     * so it shows first in Facebook's photo picker (newest-first).
     */
    fun saveToAlbum(context: Context, item: Item): List<Uri> {
        val cr = context.contentResolver
        val now = System.currentTimeMillis()
        val n = item.photos.size
        val saved = mutableListOf<Uri>()
        item.photos.forEachIndexed { i, p ->
            val src = Uri.parse(p.uri)
            val mime = cr.getType(src) ?: "image/jpeg"
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "listing_${now}_${i + 1}.$ext")
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ListingPrep")
                put(MediaStore.Images.Media.DATE_TAKEN, now + (n - i) * 1000L)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val dest = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@forEachIndexed
            val ok = runCatching {
                cr.openInputStream(src)!!.use { input -> cr.openOutputStream(dest)!!.use { input.copyTo(it) } }
            }.isSuccess
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

    /** Builds a share-sheet intent with copies of the photos (cover first). */
    fun shareIntent(context: Context, item: Item): Intent? {
        val cr = context.contentResolver
        val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val uris = ArrayList<Uri>()
        item.photos.forEachIndexed { i, p ->
            val src = Uri.parse(p.uri)
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(src) ?: "image/jpeg") ?: "jpg"
            val f = File(dir, "photo_${i + 1}.$ext")
            val ok = runCatching {
                cr.openInputStream(src)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            }.isSuccess
            if (ok) uris += FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        }
        if (uris.isEmpty()) return null
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(cr, "photos", uris[0]).also { cd ->
                uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share photos")
    }
}
