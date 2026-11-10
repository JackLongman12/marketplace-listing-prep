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

    /** Tries the Facebook app on the create-listing link, then the app's home, then the browser. */
    /**
     * Opens Facebook's "Create listing" form.
     * - In the browser (default): facebook.com/marketplace/create/item opens the form itself.
     *   The Facebook app ignores that link's "create" part and only shows Marketplace.
     * - In the Facebook app: one of its screens is picked directly, so there's no "which
     *   Facebook?" question; you then tap Sell yourself.
     */
    fun openMarketplace(context: Context) {
        val inBrowser = com.listingprep.data.Store(context).loadSettings().listingInBrowser
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
