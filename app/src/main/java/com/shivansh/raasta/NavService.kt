package com.shivansh.raasta

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps a ride alive with the screen off or another app in front: Android stops giving location (and lets the phone
 * sleep) otherwise. Shows the next instruction as a quiet notification with an End button.
 */
class NavService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == END) { onEnd?.invoke(); stopSelf(); return START_NOT_STICKY }
        title = intent?.getStringExtra("to") ?: title
        try {
            ServiceCompat.startForeground(this, ID, note(this, "Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (e: Exception) { // location permission was taken away in the meantime
            stopSelf()
            return START_NOT_STICKY
        }
        if (lock == null) lock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vaysmaps:ride").also { it.acquire(6 * 3600_000L) }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) { onEnd?.invoke(); stopSelf() }

    override fun onDestroy() {
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "ride"
        private const val END = "end"
        private var title = ""
        private var lastText = ""
        private var running = false
        var onEnd: (() -> Unit)? = null

        fun start(ctx: Context, to: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ride in progress", NotificationManager.IMPORTANCE_LOW))
            title = to
            lastText = ""
            running = true
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, NavService::class.java).putExtra("to", to)) }
        }

        @SuppressLint("MissingPermission", "NotificationPermission")
        fun update(ctx: Context, text: String) {
            if (!running || text == lastText) return
            lastText = text
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, note(ctx, text)) }
        }

        fun stop(ctx: Context) {
            if (!running) return
            running = false
            onEnd = null
            ctx.stopService(Intent(ctx, NavService::class.java))
            ctx.getSystemService(NotificationManager::class.java).cancel(ID)
        }

        private fun note(ctx: Context, text: String): Notification {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)
            val end = PendingIntent.getService(ctx, 1, Intent(ctx, NavService::class.java).setAction(END), flags)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFFB0E454.toInt())
                .setContentTitle(text)
                .setContentText("To $title")
                .setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, "End ride", end)
                .build()
        }
    }
}
