package com.listingprep.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.listingprep.MainActivity
import com.listingprep.R
import com.listingprep.data.Store
import kotlin.concurrent.thread

/**
 * Checks GitHub for a new build a few times a day, even when the app is closed (Android runs
 * it when there's a network, battery-friendly). A new build gets one notification; tapping it
 * opens the app, where the home screen shows the update. Only reads the public release list.
 */
class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "update-check") {
            runCatching {
                val store = Store(this)
                store.lastUpdateCheck = System.currentTimeMillis()
                val release = Updater.check(this) ?: return@runCatching
                store.availableBuild = release.build
                if (release.build > store.lastNotifiedBuild) {
                    store.lastNotifiedBuild = release.build
                    notifyNewBuild(this, release.build, release.notes)
                }
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 7

        /** Registers the periodic check (once; Android keeps it across restarts). */
        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                    .setPeriodic(6 * 60 * 60 * 1000L)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .build()
            )
        }

        fun cancel(context: Context) = context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)

        fun notifyNewBuild(context: Context, build: Int, notes: String) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                context, 8, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val first = notes.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            nm.notify(
                9,
                Notification.Builder(context, "updates")
                    .setSmallIcon(R.drawable.ic_stat_listing)
                    .setContentTitle("Listing Prep build $build is out")
                    .setContentText(first.ifBlank { "Tap to see what's new and update." })
                    .setStyle(Notification.BigTextStyle().bigText((first.ifBlank { "" } + "\nTap to see what's new and update.").trim()))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}
