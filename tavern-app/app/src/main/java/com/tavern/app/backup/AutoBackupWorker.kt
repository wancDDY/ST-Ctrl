package com.tavern.app.backup

import android.content.Context
import android.util.Log
import androidx.work.*
import com.tavern.app.util.AssetExtractor
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val UNIQUE_WORK_NAME = "tavern-auto-backup"
        private const val PREFS_NAME = "tavern_console_prefs"
        private const val KEY_ENABLED = "auto_backup_enabled"
        private const val KEY_INTERVAL = "auto_backup_interval"
        private const val KEY_MAX_KEEP = "auto_backup_max_keep"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun getInterval(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_INTERVAL, 1)

        fun setInterval(context: Context, days: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_INTERVAL, days).apply()
        }

        fun getMaxKeep(context: Context): Int =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MAX_KEEP, 7)

        fun setMaxKeep(context: Context, max: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_MAX_KEEP, max).apply()
        }

        fun schedule(context: Context) {
            if (!isEnabled(context)) {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
                return
            }
            val intervalDays = getInterval(context)
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                intervalDays.toLong(), TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setInitialDelay(calculateDelayToHour(3), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        private fun calculateDelayToHour(targetHour: Int): Long {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, targetHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (target.before(now)) target.add(Calendar.DAY_OF_MONTH, 1)
            return target.timeInMillis - now.timeInMillis
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Auto backup starting...")
        return try {
            val coreDir = AssetExtractor.getCoreDir(applicationContext)
            val dataDir = File(coreDir, "data")
            if (!dataDir.exists()) {
                Log.w(TAG, "data/ not found, skipping")
                return Result.success()
            }

            val manager = BackupManager(applicationContext)
            val result = manager.createBackup(coreDir, "auto") { _, _, _ -> }

            result.fold(
                onSuccess = {
                    Log.i(TAG, "Auto backup done: ${it.name}")
                    manager.cleanupOldAutoBackups(getMaxKeep(applicationContext))
                    Result.success()
                },
                onFailure = { e ->
                    Log.e(TAG, "Auto backup failed", e)
                    Result.retry()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup error", e)
            Result.retry()
        }
    }
}
