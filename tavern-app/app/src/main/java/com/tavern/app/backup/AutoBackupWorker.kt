package com.tavern.app.backup

import android.content.Context
import android.util.Log
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.*

class AutoBackupWorker {
    // No longer extends CoroutineWorker — triggered on app launch, not by WorkManager

    companion object {
        private const val TAG = "AutoBackup"
        private const val PREFS_NAME = "tavern_console_prefs"

        private val backupLock = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val KEY_ENABLED = "auto_backup_enabled"
        private const val KEY_INTERVAL = "auto_backup_interval"
        private const val KEY_MAX_KEEP = "auto_backup_max_keep"
        private const val KEY_LAST_BACKUP_MS = "auto_backup_last_ms"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun getInterval(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_INTERVAL, 3)  // default: every 3 days

        fun setInterval(context: Context, days: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_INTERVAL, days.coerceAtLeast(1)).apply()
        }

        fun getMaxKeep(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MAX_KEEP, 3)

        fun setMaxKeep(context: Context, max: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MAX_KEEP, max.coerceAtLeast(1)).apply()
        }

        /** Call after ANY backup (auto or manual) to record the timestamp. */
        fun recordBackupDone(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_BACKUP_MS, System.currentTimeMillis()).apply()
        }

        /**
         * Called when the user opens the app. Checks if enough days have passed
         * since the last backup (auto or manual). If so, runs one auto backup.
         * Skips if Node is running (user is actively chatting).
         */
        fun checkAndBackupIfNeeded(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLED, true)) return

            val intervalDays = prefs.getInt(KEY_INTERVAL, 3).coerceAtLeast(1)
            val lastMs = prefs.getLong(KEY_LAST_BACKUP_MS, 0L)

            // Use epoch-day to compare: only count calendar days, not hours
            val todayDay = System.currentTimeMillis() / 86_400_000L
            val lastDay = if (lastMs > 0) lastMs / 86_400_000L else 0L
            if (lastDay > 0 && (todayDay - lastDay) < intervalDays) {
                Log.d(TAG, "Last backup ${todayDay - lastDay}d ago, interval=${intervalDays}d — skip")
                return
            }

            // Prevent concurrent runs
            if (!backupLock.compareAndSet(false, true)) {
                Log.w(TAG, "Backup already running, skipping")
                return
            }

            Thread {
                try {
                    // Skip if Node is running — user is actively chatting
                    val nodeRunning = try {
                        java.net.Socket().use { sock ->
                            sock.connect(java.net.InetSocketAddress("127.0.0.1",
                                com.tavern.app.node.NodeState.port.value), 1000)
                            true
                        }
                    } catch (_: Exception) { false }
                    if (nodeRunning) {
                        Log.i(TAG, "Node running, user active — skipping launch backup")
                        return@Thread
                    }

                    Log.i(TAG, "Auto backup triggered (last: ${todayDay - lastDay}d ago)")
                    val coreDir = AssetExtractor.getCoreDir(context)
                    val dataDir = File(coreDir, "data")
                    if (!dataDir.exists()) {
                        Log.w(TAG, "data/ not found")
                        return@Thread
                    }

                    val manager = BackupManager(context)
                    if (manager.backupDir.freeSpace < 10L * 1024 * 1024) {
                        Log.w(TAG, "Not enough storage space")
                        return@Thread
                    }

                    val result = runBlocking { manager.createBackup(coreDir, "auto") { _, _, _ -> } }

                    result.fold(
                        onSuccess = { file ->
                            Log.i(TAG, "Auto backup done: ${file.name} (${file.length()} bytes)")
                            val minBytes = 20L * 1024 * 1024
                            val chatCount = try {
                                File(dataDir, "default-user/chats").walkTopDown().count { it.name.endsWith(".jsonl") }
                            } catch (_: Exception) { 0 }
                            if (file.length() < minBytes && chatCount > 5) {
                                Log.w(TAG, "Backup too small, retrying")
                                if (!file.delete()) Log.w(TAG, "Failed to delete undersized backup")
                                return@fold
                            }
                            recordBackupDone(context)
                            runBlocking { manager.cleanupOldAutoBackups(getMaxKeep(context)) }
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Auto backup failed", e)
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Auto backup error", e)
                } finally {
                    backupLock.set(false)
                }
            }.start()
        }
    }
}
