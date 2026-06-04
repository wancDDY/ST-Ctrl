package com.tavern.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tavern.app.MainActivity
import com.tavern.app.R
import com.tavern.app.TavernApplication
import com.tavern.app.node.NodeState

class TavernForegroundService : Service() {

    companion object {
        const val ACTION_HIDE = "com.tavern.app.HIDE_SERVICE"
        const val ACTION_OPEN = "com.tavern.app.OPEN_APP"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                // Hide notification only; keep service alive so Node.js keeps running.
                // Notification restored when user re-enters the tavern.
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            ACTION_OPEN -> {
                val openIntent = Intent(this, MainActivity::class.java).apply {
                    action = "com.tavern.app.ENTER_TAVERN"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(openIntent)
            }
            else -> startForegroundCompat(buildNotification())
        }
        return START_STICKY
    }

    private fun buildNotification() = NotificationCompat.Builder(this, TavernApplication.CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.notification_running))
        .setContentText("127.0.0.1:${NodeState.port.value}")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setShowWhen(false)
        .addAction(0, "隐藏", servicePending(ACTION_HIDE, 0))
        .addAction(0, "打开", servicePending(ACTION_OPEN, 1))
        .build()

    private fun servicePending(action: String, code: Int): PendingIntent {
        val intent = Intent(this, TavernForegroundService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(TavernApplication.NOTIFICATION_ID, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TavernApplication.NOTIFICATION_ID, n)
        }
    }
}
