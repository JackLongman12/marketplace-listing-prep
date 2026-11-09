package com.listingprep.media

import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import kotlin.math.max

/**
 * Loads list/grid thumbnails cheaply. Gallery photos use MediaStore's cached thumbnail;
 * photos taken with the in-app camera are decoded straight at the size on screen.
 * Full-size 50+ MP decodes while scrolling were the main cause of jank.
 */
class ThumbnailFetcher(private val uri: Uri, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val target = targetSize()
        val bmp = if (uri.scheme == "content") {
            runCatching { options.context.contentResolver.loadThumbnail(uri, target, null) }.getOrNull()
        } else null
        val bitmap = bmp ?: ImageDecoder.decodeBitmap(ImageDecoder.createSource(options.context.contentResolver, uri)) { d, info, _ ->
            val scale = max(target.width.toFloat() / info.size.width, target.height.toFloat() / info.size.height)
            if (scale < 1f) d.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
        val drawable = if (bitmap.config != android.graphics.Bitmap.Config.HARDWARE) {
            bitmap.copy(android.graphics.Bitmap.Config.HARDWARE, false)?.also { bitmap.recycle() } ?: bitmap
        } else bitmap
        return DrawableResult(BitmapDrawable(options.context.resources, drawable), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun targetSize(): Size {
        fun px(d: Dimension) = (d as? Dimension.Pixels)?.px ?: 512
        return Size(px(options.size.width).coerceIn(64, 1024), px(options.size.height).coerceIn(64, 1024))
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? = when {
            data.scheme == "content" && data.authority == "media" -> ThumbnailFetcher(data, options)
            data.scheme == "file" -> ThumbnailFetcher(data, options)
            else -> null
        }
    }
}
