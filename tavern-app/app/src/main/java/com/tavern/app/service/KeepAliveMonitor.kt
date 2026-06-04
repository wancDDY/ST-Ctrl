package com.tavern.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.tavern.app.node.NodeState
import kotlinx.coroutines.*
import java.net.Socket

class KeepAliveMonitor(private val context: Context) {

    companion object {
        private const val PORT_CHECK_TIMEOUT_MS = 2000L
        const val ACTION_CHECK = "com.tavern.app.CHECK_ALIVE"

        /** Get the check interval based on current performance mode. */
        private fun getIntervalMs(): Long =
            com.tavern.app.console.SettingsState.keepAliveIntervalMinutes() * 60 * 1000L

        /** Reschedule the keep-alive alarm. Call when performance mode changes. */
        fun reschedule(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val checkIntent = Intent(context, CheckReceiver::class.java).apply {
                action = ACTION_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 3001, checkIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val intervalMs = getIntervalMs()
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs,
                intervalMs,
                pendingIntent
            )
        }

        suspend fun checkAndHeal(context: Context) {
            val port = NodeState.port.value
            val alive = try {
                withTimeout(PORT_CHECK_TIMEOUT_MS) {
                    Socket("127.0.0.1", port).use { true }
                }
            } catch (e: Exception) {
                false
            }
            if (!alive) {
                Log.w("KeepAlive", "端口 $port 无响应，尝试重启服务")
                // Restart foreground service; MainActivity will restart Node.js on next launch
                val serviceIntent = Intent(context, TavernForegroundService::class.java)
                context.startForegroundService(serviceIntent)
                // Also try to launch MainActivity for automatic recovery
                val activityIntent = Intent(context, com.tavern.app.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                context.startActivity(activityIntent)
            }
        }
    }

    class CheckReceiver : BroadcastReceiver() {
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CHECK) {
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        checkAndHeal(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    fun schedule() {
        reschedule(context)
    }

}
