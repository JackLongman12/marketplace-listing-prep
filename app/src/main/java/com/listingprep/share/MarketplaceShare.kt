package com.listingprep.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.listingprep.data.Item

/**
 * Facebook has no public API for creating Marketplace listings, so "sharing" means:
 * photos saved cover-first to an album, Facebook opened on the create screen, and a
 * floating panel ([ShareOverlayService]) with one-tap copy buttons for each field.
 */
object MarketplaceShare {
    const val FACEBOOK = "com.facebook.katana"
    private val CREATE_URL: Uri = Uri.parse("https://www.facebook.com/marketplace/create/item")

    fun canShowPanel(context: Context) = Settings.canDrawOverlays(context)

    fun panelPermissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** Tries the Facebook app on the create-listing link, then the app's home, then the browser. */
    fun openMarketplace(context: Context) {
        val attempts = listOfNotNull(
            Intent(Intent.ACTION_VIEW, CREATE_URL).setPackage(FACEBOOK),
            context.packageManager.getLaunchIntentForPackage(FACEBOOK),
            Intent(Intent.ACTION_VIEW, CREATE_URL),
        )
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    /**
     * Hands the photos (cover first) straight to Facebook. Facebook decides what it offers for
     * shared photos; if one of its share options is Marketplace, that one is used directly,
     * otherwise Android shows Facebook's options to pick from. Returns false if Facebook can't
     * take photos (not installed), so the caller can fall back to opening Marketplace.
     */
    fun sendPhotos(context: Context, photos: List<Uri>): Boolean {
        if (photos.isEmpty()) return false
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(photos))
            clipData = android.content.ClipData.newRawUri("photos", photos[0]).also { cd ->
                photos.drop(1).forEach { cd.addItem(android.content.ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(FACEBOOK)
        }
        val pm = context.packageManager
        val targets = pm.queryIntentActivities(send, 0)
        if (targets.isEmpty()) return false
        val marketplace = targets.firstOrNull { it.loadLabel(pm).toString().contains("Marketplace", ignoreCase = true) }
        if (marketplace != null) send.setClassName(marketplace.activityInfo.packageName, marketplace.activityInfo.name)
        return try {
            context.startActivity(send)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Marketplace's price box takes a plain number. */
    fun priceForFacebook(price: String): String =
        price.filter { it.isDigit() || it == '.' }.ifBlank { price }

    fun startPanel(context: Context, item: Item, description: String) {
        context.startForegroundService(
            Intent(context, ShareOverlayService::class.java)
                .putExtra(ShareOverlayService.EXTRA_ID, item.id)
                .putExtra(ShareOverlayService.EXTRA_TITLE, item.title)
                .putExtra(ShareOverlayService.EXTRA_PRICE, priceForFacebook(item.price))
                .putExtra(ShareOverlayService.EXTRA_DESCRIPTION, description)
        )
    }
}
