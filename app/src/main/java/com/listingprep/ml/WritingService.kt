package com.listingprep.ml

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.listingprep.MainActivity
import com.listingprep.R

/**
 * Keeps the app alive while the AI writes descriptions, so you can leave the app (or lock
 * the phone) and come back to finished listings. It does no work itself; the writing runs
 * in the app and this just holds a notification with progress.
 */
class WritingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            PROGRESS_ID,
            notification(this, intent?.getStringExtra(EXTRA_TEXT) ?: "Writing descriptions…", ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_NOT_STICKY
    }

    /** Android 15+ may stop long special-use work; the app keeps writing while it's open. */
    override fun onTimeout(startId: Int, fgsType: Int) = stopSelf()

    companion object {
        private const val CHANNEL = "writing"
        private const val PROGRESS_ID = 3
        private const val DONE_ID = 4
        private const val EXTRA_TEXT = "text"

        private fun notification(context: Context, text: String, ongoing: Boolean): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Writing descriptions", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(
                context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_listing)
                .setContentTitle("Listing Prep")
                .setContentText(text)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .apply { if (ongoing) setProgress(0, 0, true) }
                .build()
        }

        fun start(context: Context, text: String) = runCatching {
            context.startForegroundService(Intent(context, WritingService::class.java).putExtra(EXTRA_TEXT, text))
        }

        /** Updates the progress text without restarting the service (allowed from the background). */
        fun update(context: Context, text: String) {
            if (!canNotify(context)) return
            context.getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, notification(context, text, ongoing = true))
        }

        fun finish(context: Context, doneText: String?) {
            context.stopService(Intent(context, WritingService::class.java))
            if (doneText != null && canNotify(context)) {
                context.getSystemService(NotificationManager::class.java).notify(DONE_ID, notification(context, doneText, ongoing = false))
            }
        }

        private fun canNotify(context: Context) =
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
}
