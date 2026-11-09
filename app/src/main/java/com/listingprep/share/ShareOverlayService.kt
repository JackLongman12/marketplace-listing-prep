package com.listingprep.share

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.IBinder
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.listingprep.MainActivity

/**
 * Random per-process secret. MainActivity is exported (it's the launcher), so it only
 * accepts a "posted" result that carries this value, which other apps can't know.
 */
object PanelSession {
    val nonce: String = java.util.UUID.randomUUID().toString()
}

/**
 * A small draggable panel that floats over Facebook while you fill in a listing.
 * Each button copies one field to the clipboard; "Done" returns to Listing Prep and
 * marks the item as posted.
 */
class ShareOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var panel: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_CLOSE) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        showPanel(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            price = intent.getStringExtra(EXTRA_PRICE).orEmpty(),
            description = intent.getStringExtra(EXTRA_DESCRIPTION).orEmpty(),
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removePanel()
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Posting helper", NotificationManager.IMPORTANCE_LOW)
        )
        val close = PendingIntent.getService(
            this, 0,
            Intent(this, ShareOverlayService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Copy panel is on")
            .setContentText("Use the floating panel to copy each field into Facebook.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null as Icon?, "Close panel", close).build())
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun showPanel(id: String, title: String, price: String, description: String) {
        removePanel()
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)

        val status = TextView(ctx).apply {
            text = "Tap a button, then paste into the matching Facebook field."
            setTextColor(Color.rgb(0xBD, 0xC1, 0xC6))
            textSize = 12f
        }

        fun copy(label: String, value: String) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(label, value))
            status.text = "$label copied. Long-press the $label field in Facebook → Paste."
        }

        fun button(label: String, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { onClick() }
        }

        fun row(vararg views: View) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it) }
        }

        val header = TextView(ctx).apply {
            text = "⠿  " + title.ifBlank { "Listing" }
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(280)
            setPadding(0, dp(2), 0, dp(4))
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(0xF0, 0x20, 0x21, 0x24))
            }
            elevation = dp(8).toFloat()
            addView(header)
            addView(status)
            addView(row(
                button("Title") { copy("Title", title) },
                button("Price") { copy("Price", price) },
                button("Description") { copy("Description", description) },
            ))
            addView(row(
                button("Open Facebook") { MarketplaceShare.openMarketplace(this@ShareOverlayService) },
                button("Done ✓") { finishPosted(id) },
                button("✕") { stopSelf() },
            ))
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(140)
        }

        // Drag the panel by its title line so it never covers the field you're typing in.
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; downX = e.rawX; downY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt()
                    params.y = startY + (e.rawY - downY).toInt()
                    windowManager.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }

        windowManager.addView(root, params)
        panel = root
    }

    private fun finishPosted(id: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_POSTED_ID, id)
                .putExtra(MainActivity.EXTRA_POSTED_NONCE, PanelSession.nonce)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        stopSelf()
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PRICE = "price"
        const val EXTRA_DESCRIPTION = "description"
        private const val ACTION_CLOSE = "com.listingprep.CLOSE_PANEL"
        private const val CHANNEL = "posting_helper"
        private const val NOTIFICATION_ID = 1
    }
}
