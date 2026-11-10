package com.listingprep.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    /**
     * Facebook's website only takes one photo at a time, so a listing with more photos than
     * this opens the Facebook app instead (its picker takes many at once).
     */
    const val MAX_WEB_PHOTOS = 5

    fun usesBrowser(photos: Int) = photos <= MAX_WEB_PHOTOS

    /**
     * Opens Facebook for a new listing.
     * - Up to [MAX_WEB_PHOTOS] photos: the browser, where facebook.com/marketplace/create/item
     *   opens the Create listing form itself.
     * - More photos: the Facebook app on Marketplace (it ignores the link's "create" part);
     *   one of its screens is picked directly, so there's no "which Facebook?" question.
     */
    fun openMarketplace(context: Context, photos: Int) {
        val inBrowser = usesBrowser(photos)
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, CREATE_URL).addCategory(Intent.CATEGORY_BROWSABLE)
        val browser = run {
            val def = pm.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.takeIf { it.packageName != FACEBOOK && it.packageName != "android" }
            def ?: pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE), 0)
                .map { it.activityInfo }.firstOrNull { it.packageName != FACEBOOK }
        }?.let { Intent(view).setClassName(it.packageName, it.name) }
        val fbApp = pm.queryIntentActivities(Intent(view).setPackage(FACEBOOK), 0).firstOrNull()?.activityInfo
            ?.let { Intent(view).setClassName(it.packageName, it.name) }
        val attempts = listOfNotNull(
            if (inBrowser) browser else fbApp,
            if (inBrowser) fbApp else browser,
            pm.getLaunchIntentForPackage(FACEBOOK),
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
                .putExtra(ShareOverlayService.EXTRA_PHOTOS, item.photos.size)
        )
    }
}
