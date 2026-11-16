package com.tavern.app.backup

import android.content.Context
import android.util.Log
import com.tavern.app.util.AssetExtractor
import java.io.File
import kotlinx.coroutines.runBlocking

class AutoBackupWorker {
    // Triggered on app launch instead of WorkManager because OEM background
    // alarms are unreliable on many phones.

    companion object {
        private const val TAG = "AutoBackup"
        private const val PREFS_NAME = "tavern_console_prefs"
        private const val KEY_SCOPE_CHATS = "backup_scope_chats"
        private const val KEY_SCOPE_EXTENSIONS = "backup_scope_extensions"
        private const val KEY_SCOPE_CORE_CONFIG = "backup_scope_core_config"
        private const val KEY_SCOPE_LIBRARY = "backup_scope_library"
        private const val KEY_SCOPE_APP_SETTINGS = "backup_scope_app_settings"

        private val backupLock = java.util.concurrent.atomic.AtomicBoolean(false)
        // Internal: a backup archive carries these four as part of the app settings,
        // so the key names are shared with `SettingsState` rather than repeated.
        internal const val KEY_ENABLED = "auto_backup_enabled"
        internal const val KEY_INTERVAL = "auto_backup_interval"
        internal const val KEY_MAX_KEEP = "auto_backup_max_keep"
        private const val KEY_LAST_BACKUP_MS = "auto_backup_last_ms"
        internal const val KEY_INCLUDE_API_KEYS = "auto_backup_include_api_keys"
        // "Remind me" is a separate switch from the enable toggle: the reader keeps the
        // schedule but decides when the copy runs, because it stops the core.
        internal const val KEY_REMIND_ONLY = "auto_backup_remind_only"
        private const val KEY_LAST_REMINDED_MS = "auto_backup_last_reminded_ms"

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

        /** What a backup carries, as the reader last left the switches. */
        fun getScope(context: Context): BackupScope {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return BackupScope(
                chats = prefs.getBoolean(KEY_SCOPE_CHATS, true),
                extensions = prefs.getBoolean(KEY_SCOPE_EXTENSIONS, true),
                coreConfig = prefs.getBoolean(KEY_SCOPE_CORE_CONFIG, true),
                library = prefs.getBoolean(KEY_SCOPE_LIBRARY, true),
                appSettings = prefs.getBoolean(KEY_SCOPE_APP_SETTINGS, true),
                apiKeys = getIncludeApiKeys(context),
            )
        }

        fun setScope(context: Context, scope: BackupScope) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SCOPE_CHATS, scope.chats)
                .putBoolean(KEY_SCOPE_EXTENSIONS, scope.extensions)
                .putBoolean(KEY_SCOPE_CORE_CONFIG, scope.coreConfig)
                .putBoolean(KEY_SCOPE_LIBRARY, scope.library)
                .putBoolean(KEY_SCOPE_APP_SETTINGS, scope.appSettings)
                .putBoolean(KEY_INCLUDE_API_KEYS, scope.apiKeys)
                .apply()
        }

        fun isRemindOnly(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_REMIND_ONLY, false)

        fun setRemindOnly(context: Context, remindOnly: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_REMIND_ONLY, remindOnly).apply()
        }

        /** Call after ANY successful backup (auto or manual). */
        fun recordBackupDone(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_BACKUP_MS, System.currentTimeMillis())
                .remove(KEY_LAST_REMINDED_MS)
                .apply()
            BackupState.clearReminder()
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
        return
    }

    // The backup restarts Node when it finishes, and Android 12+ refuses that start
    // from the background. Running one while the app is not in front is how the
    // service ends up down until the next entry to the app. Wait for the foreground.
    if (!com.tavern.app.ApplicationState.appInForeground) {
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
                return
            }

            // The copy stops the core for as long as it runs. A reader who does not want
            // that to happen on its own gets one line in the island instead, once a day,
            // and starts the copy from the page the line opens.
            if (isRemindOnly(context)) {
                val now = System.currentTimeMillis()
                val lastReminded = prefs.getLong(KEY_LAST_REMINDED_MS, 0L)
                val remindedToday = lastReminded > 0 &&
                    now / 86_400_000L == lastReminded / 86_400_000L
                if (remindedToday) return
                prefs.edit().putLong(KEY_LAST_REMINDED_MS, now).apply()
                Log.i(TAG, "Auto backup is due; reminding instead of running (remind-only)")
                BackupState.postReminder("自动备份提醒")
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
            // The stored date is the only witness the launcher path had. An uninstall clears it
            // while the archives stay in the app's folder, so the first launch after each install
            // wrote another copy for the same day. The files on disk are the second witness.
            val storedLast = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_BACKUP_MS, 0L)
            val lastMs = maxOf(storedLast, manager.latestAutoBackupMs())
            val lastDay = if (lastMs > 0) lastMs / 86_400_000L else 0L
            val todayDay = System.currentTimeMillis() / 86_400_000L
            if (lastDay > 0L && (todayDay - lastDay) < getInterval(context)) {
                return
            }
            if (com.tavern.app.console.SettingsState.backupLocation() == "public" &&
                !manager.hasPublicTreePermission()
            ) {
                Log.w(TAG, "Public backup folder is not authorized; skipping auto backup")
                return
            }
            val result = manager.createBackup(
                coreDir = coreDir,
                coreVersion = "auto",
                onProgress = { current, total, name ->
                    BackupState.updateProgress(
                        current = current,
                        total = total,
                        context = if (current > 0) "压缩中…" else name,
                    )
                    BackupState.appendLog(
                        // Progress is rendered once in the monitor; the log lists items only.
                        if (current > 0) name else "\$ $name",
                    )
                },
                scope = getScope(context)
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
