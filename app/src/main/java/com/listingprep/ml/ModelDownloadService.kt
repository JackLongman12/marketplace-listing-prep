package com.listingprep.ml

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import com.listingprep.MainActivity
import com.listingprep.R
import com.listingprep.data.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.coroutineContext

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val label: String, val progress: Float?) : DownloadState
    data class Done(val result: ModelDownloader.Finish) : DownloadState
    data class Failed(val message: String) : DownloadState
}

/**
 * Foreground service that downloads the model with resume. Survives leaving the app, waits
 * out dropped connections and Wi-Fi changes, and fetches a fresh signed link on each retry.
 */
class ModelDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground("Starting download…", null)
        if (intent?.action == ACTION_CANCEL) {
            val running = job
            scope.launch {
                running?.cancelAndJoin()
                ModelDownloader.deletePart(this@ModelDownloadService)
                _state.value = DownloadState.Idle
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val wifiOnly = intent?.getBooleanExtra(EXTRA_WIFI_ONLY, true) ?: true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ListingPrep:model")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        job = scope.launch {
            val end = try {
                download(wifiOnly)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelDownloader.Refused) {
                DownloadState.Failed(ModelDownloader.refusedMessage(e.code))
            } catch (e: Exception) {
                DownloadState.Failed("Model download failed: ${e.message}. Tap Download to resume.")
            }
            _state.value = end
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps data-sync services at 6 hours a day. Keep what we have so Download resumes. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.cancel()
        _state.value = DownloadState.Failed("Android paused the download after a long time. Tap Download to resume where it left off.")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        if (_state.value is DownloadState.Running) {
            _state.value = DownloadState.Failed("The download stopped. Tap Download to resume where it left off.")
        }
        super.onDestroy()
    }

    private suspend fun download(wifiOnly: Boolean): DownloadState {
        val token = Store(this).hfToken
        if (token.isNullOrBlank()) return DownloadState.Failed("Add your Hugging Face token first.")
        val part = ModelDownloader.partFile(this)
        var link: String? = null
        var failures = 0

        while (part.length() < ModelDownloader.SIZE_BYTES) {
            if (!networkOk(wifiOnly)) {
                publish(if (wifiOnly) "Waiting for Wi-Fi…" else "Waiting for a connection…", part.length())
                delay(3_000)
                continue
            }
            val before = part.length()
            try {
                publish(if (before > 0) "Resuming download…" else "Contacting Hugging Face…", before)
                val url = link ?: ModelDownloader.resolveSignedUrl(token).also { link = it }
                val ctx = coroutineContext
                ModelDownloader.fetch(url, part, keepGoing = { ctx.isActive }) { done ->
                    publish("Downloading AI model…", done)
                }
                if (part.length() < ModelDownloader.SIZE_BYTES) throw IOException("Connection closed early")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelDownloader.Refused) {
                throw e
            } catch (e: IOException) {
                if (!coroutineContext.isActive) throw CancellationException()
                link = null // assume the signed link went stale; ask for a new one next time
                failures = if (part.length() > before) 0 else failures + 1
                if (failures >= 30) {
                    return DownloadState.Failed("The connection keeps failing. Check your internet, then tap Download to resume.")
                }
                val wait = (5_000L * (1 shl minOf(failures, 4))).coerceAtMost(60_000L)
                publish("Connection dropped, retrying in ${wait / 1000}s…", part.length(), force = true)
                delay(wait)
            }
        }

        publish("Verifying download (checksum)…", null, force = true)
        return DownloadState.Done(ModelDownloader.finish(this))
    }

    private fun networkOk(wifiOnly: Boolean): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !wifiOnly || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun publish(label: String, downloaded: Long?, force: Boolean = false) {
        val fraction = downloaded?.let { it.toFloat() / ModelDownloader.SIZE_BYTES }
        val text = if (downloaded != null) {
            "$label ${"%.1f / %.1f GB".format(downloaded / 1e9, ModelDownloader.SIZE_BYTES / 1e9)}"
        } else label
        _state.value = DownloadState.Running(text, fraction)
        val now = System.currentTimeMillis()
        if (force || now - lastNotify > 1_000) {
            lastNotify = now
            goForeground(text, fraction)
        }
    }

    private fun goForeground(text: String, fraction: Float?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI model download", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ModelDownloadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_listing)
            .setContentTitle("AI model for Listing Prep")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
            .addAction(Notification.Action.Builder(null as Icon?, "Cancel", cancel).build())
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val CHANNEL = "model_download"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_CANCEL = "com.listingprep.CANCEL_MODEL_DOWNLOAD"
        private const val EXTRA_WIFI_ONLY = "wifiOnly"

        private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val state = _state.asStateFlow()

        fun start(context: Context, wifiOnly: Boolean) {
            _state.value = DownloadState.Running("Starting download…", null)
            context.startForegroundService(
                Intent(context, ModelDownloadService::class.java).putExtra(EXTRA_WIFI_ONLY, wifiOnly)
            )
        }

        fun cancel(context: Context) {
            if (_state.value is DownloadState.Running) {
                context.startService(Intent(context, ModelDownloadService::class.java).setAction(ACTION_CANCEL))
            } else {
                ModelDownloader.deletePart(context)
            }
            _state.value = DownloadState.Idle
        }

        /** Called once the UI has shown a Done/Failed result. */
        fun acknowledge() {
            if (_state.value !is DownloadState.Running) _state.value = DownloadState.Idle
        }
    }
}
