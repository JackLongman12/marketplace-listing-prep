package com.listingprep.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import com.listingprep.R

/**
 * Result of an update install. If Android wants you to confirm (the first update, or after
 * you installed a build by hand), it asks right away when the app is open, or via a
 * notification when it isn't. Not exported: only the system installer, through our own
 * PendingIntent, can reach it.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (UpdateState.appVisible) {
                    runCatching { context.startActivity(confirm) }.onFailure { notifyConfirm(context, confirm) }
                } else {
                    notifyConfirm(context, confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.apkFile(context).delete()
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val apk = Updater.apkFile(context)
                if (intent.getBooleanExtra(EXTRA_WITH_PROFILE, false) && apk.exists() && message.contains("metadata", ignoreCase = true)) {
                    runCatching { Updater.install(context, apk, withProfile = false) }
                    return
                }
                apk.delete()
                UpdateState.failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android didn't install the update.")
            }
        }
    }

    companion object {
        const val EXTRA_WITH_PROFILE = "withProfile"
    }

    private fun notifyConfirm(context: Context, confirm: Intent) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(context, 5, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            5,
            Notification.Builder(context, "updates")
                .setSmallIcon(R.drawable.ic_stat_listing)
                .setContentTitle("Snaplist update ready")
                .setContentText("Tap to finish installing the new build.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }
}
