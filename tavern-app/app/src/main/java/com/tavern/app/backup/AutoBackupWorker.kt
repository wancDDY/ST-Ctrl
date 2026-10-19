package com.tavern.app.backup

import android.content.Context
import android.util.Log
import com.tavern.app.util.AssetExtractor
import kotlinx.coroutines.runBlocking
import java.io.File

class AutoBackupWorker {
    // Triggered on app launch instead of WorkManager because OEM background
    // alarms are unreliable on many phones.

    companion object {
        private const val TAG = "AutoBackup"
        private const val PREFS_NAME = "tavern_console_prefs"

        private val backupLock = java.util.concurrent.atomic.AtomicBoolean(false)
        // Internal: a backup archive carries these four as part of the app settings,
        // so the key names are shared with `SettingsState` rather than repeated.
        internal const val KEY_ENABLED = "auto_backup_enabled"
        internal const val KEY_INTERVAL = "auto_backup_interval"
        internal const val KEY_MAX_KEEP = "auto_backup_max_keep"
        private const val KEY_LAST_BACKUP_MS = "auto_backup_last_ms"
        internal const val KEY_INCLUDE_API_KEYS = "auto_backup_include_api_keys"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun getInterval(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_INTERVAL, 3)

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

        fun getIncludeApiKeys(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_INCLUDE_API_KEYS, true)

        fun setIncludeApiKeys(context: Context, include: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_INCLUDE_API_KEYS, include).apply()
        }

        /** Call after ANY successful backup (auto or manual). */
        fun recordBackupDone(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_BACKUP_MS, System.currentTimeMillis()).apply()
        }

        /**
         * Called when the app launches. Starts one due backup in the
         * background. Callers must keep this off the critical Node startup path.
         */
        fun checkAndBackupIfNeeded(context: Context) {
    // A backup stops Node to copy a tree nothing is writing to; doing that while the
    // tavern page is in front kills the session the page is holding, and the page
    // then comes back reloaded for no reason the user can see. Next run gets it.
    if (com.tavern.app.ApplicationState.tavernPageOpen) {
        Log.d(TAG, "Tavern page is open - deferring auto backup")
        return
    }

    // The backup restarts Node when it finishes, and Android 12+ refuses that start
    // from the background. Running one while the app is not in front is how the
    // service ends up down until the next entry to the app. Wait for the foreground.
    if (!com.tavern.app.ApplicationState.appInForeground) {
        Log.d(TAG, "App is not in front - deferring auto backup")
        return
    }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_ENABLED, true)) return

            val intervalDays = prefs.getInt(KEY_INTERVAL, 3).coerceAtLeast(1)
            val lastMs = prefs.getLong(KEY_LAST_BACKUP_MS, 0L)

            // Compare epoch days only so time-of-day does not delay the check.
            val todayDay = System.currentTimeMillis() / 86_400_000L
            val lastDay = if (lastMs > 0) lastMs / 86_400_000L else 0L
            val due = lastDay == 0L || (todayDay - lastDay) >= intervalDays
            if (!due) {
                Log.d(TAG, "Last backup ${todayDay - lastDay}d ago, interval=${intervalDays}d — skip")
                return
            }

            if (!backupLock.compareAndSet(false, true)) {
                Log.w(TAG, "Backup already running, skipping")
                return
            }

            Thread {
                try {
                    runBlocking {
                        BackupCoordinator.withDataLock {
                            runDueBackup(context)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto backup error", e)
                } finally {
                    backupLock.set(false)
                }
            }.start()
        }

        private suspend fun runDueBackup(context: Context) {
            val coreDir = AssetExtractor.getCoreDir(context)
            val dataDir = File(coreDir, "data")
            if (!dataDir.exists()) {
                Log.w(TAG, "data/ not found")
                return
            }

            // A backup stops the core service to snapshot the database. An unattended one landing
            // in the middle of a memory pass would cut that pass off with no one watching, so it
            // waits for a quieter moment instead - the pass ends on its own within a batch or two.
            if (com.tavern.app.console.MemoryTaskState.running.value != null ||
                com.tavern.app.console.MemoryScanState.running.value != null
            ) {
                Log.i(TAG, "Memory work is running; skipping this auto backup round")
                return
            }

            val manager = BackupManager(context)
            if (com.tavern.app.console.SettingsState.backupLocation() == "public" &&
                !manager.hasPublicTreePermission()
            ) {
                Log.w(TAG, "Public backup folder is not authorized; skipping auto backup")
                return
            }
            val result = manager.createBackup(
                coreDir = coreDir,
                coreVersion = "auto",
                onProgress = { _, _, _ -> },
                includeApiKeys = getIncludeApiKeys(context)
            )

            result.fold(
                onSuccess = { entry ->
                    Log.i(TAG, "Auto backup done: ${entry.name} (${entry.sizeBytes} bytes)")
                    recordBackupDone(context)
                    try {
                        manager.cleanupOldAutoBackups(getMaxKeep(context))
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto cleanup failed", e)
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "Auto backup failed", e)
                }
            )
        }
    }
}
