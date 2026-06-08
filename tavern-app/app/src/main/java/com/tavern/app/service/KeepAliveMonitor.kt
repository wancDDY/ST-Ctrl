package com.tavern.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.tavern.app.node.NodeState
import kotlinx.coroutines.*
import java.net.InetSocketAddress
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
            val triggerAt = SystemClock.elapsedRealtime() + intervalMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent
                )
            } else {
                @Suppress("DEPRECATION")
                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, intervalMs, pendingIntent
                )
            }
        }

        suspend fun checkAndHeal(context: Context) {
            val port = NodeState.port.value
            val alive = try {
                withTimeout(PORT_CHECK_TIMEOUT_MS) {
                    Socket().apply {
                        connect(InetSocketAddress("127.0.0.1", port), PORT_CHECK_TIMEOUT_MS.toInt())
                    }.use { true }
                }
            } catch (e: Exception) {
                false
            }
            if (!alive) {
                Log.w("KeepAlive", "端口 $port 无响应，尝试重启服务")
                NodeState.setIdle()
                // 只重启 Service（Node.js 低功耗启动），不拉起 Activity 避免双路启动
                val serviceIntent = Intent(context, TavernForegroundService::class.java).apply {
                    action = TavernForegroundService.ACTION_BOOT_START
                }
                context.startForegroundService(serviceIntent)
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

    fun cancel() {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, CheckReceiver::class.java).apply { action = ACTION_CHECK }
        val pending = PendingIntent.getBroadcast(context, 3001, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        alarm.cancel(pending)
    }

}
