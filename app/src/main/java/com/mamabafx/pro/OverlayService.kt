package com.mamabafx.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/** Floating bot controls, available while the user is in another app such as MT5. */
class OverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var panel: LinearLayout? = null
    private var statusText: TextView? = null
    private var toggleButton: TextView? = null
    private var closeAllButton: TextView? = null
    private var latestStatus: JSONObject? = null
    private var closeAllArmedUntil = 0L
    private var polling = false

    override fun onCreate() {
        super.onCreate()
        Api.prefs = getSharedPreferences("mamba", MODE_PRIVATE)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps to show the floating controls", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        polling = true
        pollStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun showOverlay() {
        if (panel != null) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = shape(Color.argb(238, 20, 0, 0), 14, Color.parseColor("#B71C1C"), 1)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = label("MAMBA FX", 14f, Color.WHITE, true)
        header.addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
        val hide = label("×", 24f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { stopSelf() }
        }
        header.addView(hide, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(header)

        statusText = label("Connecting to bot…", 12f, Color.WHITE)
        root.addView(statusText)

        toggleButton = actionButton("STOP BOT", Color.parseColor("#E53935")) {
            val running = isRunning()
            sendCommand(if (running) "stop" else "start")
        }
        root.addView(toggleButton, LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(8) })

        closeAllButton = actionButton("CLOSE ALL TRADES", Color.parseColor("#616161")) {
            val now = System.currentTimeMillis()
            if (now > closeAllArmedUntil) {
                closeAllArmedUntil = now + 5000
                closeAllButton?.text = "TAP AGAIN TO CONFIRM"
                handler.postDelayed({
                    if (System.currentTimeMillis() > closeAllArmedUntil) closeAllButton?.text = "CLOSE ALL TRADES"
                }, 5100)
            } else {
                closeAllArmedUntil = 0L
                closeAllButton?.text = "CLOSE ALL TRADES"
                sendCommand("closeAll")
            }
        }
        root.addView(closeAllButton, LinearLayout.LayoutParams(MATCH, dp(42)).apply { topMargin = dp(6) })

        val params = WindowManager.LayoutParams(
            dp(220),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(120)
        }
        try {
            windowManager?.addView(root, params)
            panel = root
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun actionButton(text: String, color: Int, action: () -> Unit): TextView =
        label(text, 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = shape(color, 9)
            setOnClickListener { action() }
        }

    private fun isRunning(): Boolean {
        val s = latestStatus ?: return true
        return s.optBoolean("running", s.optJSONObject("settings")?.optBoolean("run", true) ?: true)
    }

    private fun pollStatus() {
        if (!polling) return
        Api.call("GET", "/api/status?key=" + android.net.Uri.encode(Api.key()), null) { result, code ->
            if (!polling) return@call
            if (code == 403) {
                statusText?.text = "Access revoked — open Mamba FX Pro"
                toggleButton?.isEnabled = false
                closeAllButton?.isEnabled = false
            } else if (result != null) {
                latestStatus = result
                val online = result.optBoolean("online", false)
                val running = isRunning()
                val state = when {
                    !online -> "Bot offline"
                    result.optBoolean("newsPause", false) -> "Paused for news"
                    running -> "Bot active"
                    else -> "Bot stopped"
                }
                statusText?.text = "$state  •  Trades: ${result.optInt("openTrades", 0)}"
                toggleButton?.text = if (running) "STOP BOT" else "START BOT"
                toggleButton?.setBackgroundColor(if (running) Color.parseColor("#E53935") else Color.parseColor("#2E7D32"))
            } else {
                statusText?.text = "Relay unavailable ($code)"
            }
            handler.postDelayed({ pollStatus() }, 3000)
        }
    }

    private fun sendCommand(action: String) {
        val body = JSONObject().put("key", Api.key()).put("action", action)
        Api.call("POST", "/api/command", body) { result, code ->
            if (result == null || code !in 200..299) {
                Toast.makeText(this, "Command failed ($code)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, if (action == "closeAll") "Close-all sent to the bot" else "Bot command sent", Toast.LENGTH_SHORT).show()
                handler.postDelayed({ pollStatus() }, 500)
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Mamba FX Pro is running")
            .setContentText("Floating bot controls are available over other apps")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Mamba FX floating controls", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        try {
            panel?.let { windowManager?.removeView(it) }
        } catch (_: Exception) { }
        panel = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "mamba_floating_controls"
        private const val NOTIFICATION_ID = 7102
        private const val MATCH = WindowManager.LayoutParams.MATCH_PARENT
        private const val WRAP = WindowManager.LayoutParams.WRAP_CONTENT
    }
}
