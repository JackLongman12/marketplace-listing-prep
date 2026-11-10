package com.listingprep

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.listingprep.media.ThumbnailFetcher

class ListingPrepApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    /** Gallery photos load from Android's cached thumbnails instead of decoding full-size files. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(ThumbnailFetcher.Factory()) }
        .crossfade(false)
        .build()
}
