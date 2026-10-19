package com.tavern.app.update

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.tavern.app.util.AssetExtractor
import com.tavern.app.util.JsonUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object PeriodicUpdateChecker {
    const val ACTION_CHECK = "com.stctrl.mobile.UPDATE_CHECK"

    private const val PREFS_NAME = "update_check_prefs"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_PENDING_APP_VERSION = "pending_app_version"
    private const val KEY_PENDING_CORE_VERSION = "pending_core_version"
    private const val KEY_PENDING_APP_CHANGELOG = "pending_app_changelog"
    private const val KEY_PENDING_CORE_CHANGELOG = "pending_core_changelog"
    private const val KEY_PERMISSION_PROMPTED = "permission_prompted"
    private const val REQUEST_CODE = 4001
    private const val TAG = "UpdateCheck"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val CHECK_INTERVAL_DAYS = 3

    private val checking = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // A check runs from an alarm with no screen attached, so the result is kept
    // both in prefs (survives a restart) and in this flow, which is what the
    // console observes to announce the update the next time the app is opened.
    private val _pendingUpdates = MutableStateFlow(PendingUpdates(null, null))
    val pendingUpdates: StateFlow<PendingUpdates> = _pendingUpdates.asStateFlow()

    data class PendingUpdates(
        val appVersion: String?,
        val coreVersion: String?,
        /**
         * Release notes that came with the versions above. The island notice is what
         * sends the reader to the update page, and that page must be able to show what
         * is new even when the check ran days ago or the network is gone, so the notes
         * travel with the announcement instead of being fetched again.
         */
        val appChangelog: String? = null,
        val coreChangelog: String? = null,
    ) {
        val hasUpdate: Boolean get() = !appVersion.isNullOrBlank() || !coreVersion.isNullOrBlank()

        fun validated(currentAppVersion: String, currentCoreVersion: String): PendingUpdates {
            val app = appVersion?.takeIf {
                VersionComparator.isNewer(it, currentAppVersion)
            }
            val core = coreVersion?.takeIf {
                VersionComparator.isNewer(it, currentCoreVersion)
            }
            return PendingUpdates(
                appVersion = app,
                coreVersion = core,
                // Notes for a version that is no longer pending are dropped with it.
                appChangelog = appChangelog.takeIf { app != null },
                coreChangelog = coreChangelog.takeIf { core != null },
            )
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pending(context: Context): PendingUpdates {
        val stored = PendingUpdates(
            appVersion = prefs(context).getString(KEY_PENDING_APP_VERSION, null),
            coreVersion = prefs(context).getString(KEY_PENDING_CORE_VERSION, null),
            appChangelog = prefs(context).getString(KEY_PENDING_APP_CHANGELOG, null),
            coreChangelog = prefs(context).getString(KEY_PENDING_CORE_CHANGELOG, null)
        )
        val validated = stored.validated(
            currentAppVersion(context),
            currentCoreVersion(context),
        )
        if (validated != stored) {
            persistPending(context, validated)
        } else {
            _pendingUpdates.value = validated
        }
        return validated
    }

    fun initialize(context: Context) {
        // Read before scheduling: the island notice on this launch comes from
        // what the previous alarm found, not from a check that has just started.
        pending(context)
        schedule(context)
    }

    fun checkWhenIdle(context: Context) {
        if (isDue(context)) {
            scope.launch {
                checkIfDue(context)
            }
        }
    }

    suspend fun checkIfDue(context: Context, force: Boolean = false): PendingUpdates? {
        if (!force && !isDue(context)) return pending(context)
        if (!checking.compareAndSet(false, true)) return pending(context)

        try {
            Log.i(TAG, "Checking app and core updates")
            val currentAppVersion = currentAppVersion(context)
            val currentCoreVersion = currentCoreVersion(context)
            val previous = pending(context)

            val releases = coroutineScope {
                val app = async { AppUpdateChecker.check().getOrNull() }
                val core = async { UpdateChecker.checkLatest().getOrNull() }
                app.await() to core.await()
            }

            // Only a check that actually reached GitHub counts as done: stamping the
            // time before the request meant one failure silenced the next three days.
            if (releases.first != null || releases.second != null) {
                prefs(context).edit()
                    .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
                    .apply()
            }

            val appRelease = releases.first
                ?.takeIf { VersionComparator.isNewer(it.version, currentAppVersion) }
            val coreRelease = releases.second
                ?.takeIf { VersionComparator.isNewer(it.version, currentCoreVersion) }
            val appVersion = if (releases.first != null) {
                appRelease?.version
            } else {
                previous.appVersion
            }
            val coreVersion = if (releases.second != null) {
                coreRelease?.version
            } else {
                previous.coreVersion
            }

            val result = PendingUpdates(
                appVersion = appVersion,
                coreVersion = coreVersion,
                // A check that reached GitHub replaces the announced notes; a failed
                // one keeps the previous announcement, notes included.
                appChangelog = if (releases.first != null) {
                    appRelease?.changelog
                } else {
                    previous.appChangelog
                },
                coreChangelog = if (releases.second != null) {
                    coreRelease?.changelog
                } else {
                    previous.coreChangelog
                },
            )
            persistPending(context, result)

            Log.i(TAG, "Update check complete: app=${result.appVersion ?: "-"} core=${result.coreVersion ?: "-"}")
            return result
        } finally {
            checking.set(false)
            schedule(context)
        }
    }

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = checkPendingIntent(context)
        val triggerAt = System.currentTimeMillis() + CHECK_INTERVAL_DAYS * DAY_MS
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent
        )
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(checkPendingIntent(context))
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun shouldRequestNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            !hasNotificationPermission(context) &&
            !prefs(context).getBoolean(KEY_PERMISSION_PROMPTED, false)

    fun markNotificationPermissionPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PERMISSION_PROMPTED, true).apply()
    }

    fun clearPendingCore(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_CORE_VERSION).apply()
        // Re-read instead of only dropping the key: the freshly installed core is
        // what the validation compares against, and the console must stop
        // announcing a version that is already on the device.
        pending(context)
    }

    private fun isDue(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK_MS, 0L)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_DAYS * DAY_MS
    }

    private fun currentCoreVersion(context: Context): String {
        val coreDir = AssetExtractor.getCoreDir(context)
        val packageJson = File(coreDir, "package.json")
        val fromPackage = JsonUtil.readVersion(packageJson, "")
        if (fromPackage.isNotBlank()) return fromPackage
        val versionFile = File(context.filesDir, "core_version.txt")
        if (versionFile.exists()) {
            val extracted = versionFile.readText().trim()
            if (extracted.isNotBlank()) return extracted
        }
        return try {
            context.assets.open("core/version.txt")
                .bufferedReader().use { it.readText().trim() }
        } catch (_: Exception) {
            ""
        }
    }

    private fun currentAppVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun persistPending(context: Context, updates: PendingUpdates) {
        prefs(context).edit()
            .putString(KEY_PENDING_APP_VERSION, updates.appVersion)
            .putString(KEY_PENDING_CORE_VERSION, updates.coreVersion)
            .putString(KEY_PENDING_APP_CHANGELOG, updates.appChangelog)
            .putString(KEY_PENDING_CORE_CHANGELOG, updates.coreChangelog)
            .apply()
        _pendingUpdates.value = updates
    }

    private fun checkPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, UpdateCheckReceiver::class.java).apply {
            action = ACTION_CHECK
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    class UpdateCheckReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CHECK) {
                schedule(context)
                val pendingResult = goAsync()
                scope.launch {
                    try {
                        checkIfDue(context, force = true)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    /**
     * Alarms do not survive a reboot, and the app update path cancels them too,
     * so without re-arming here the "every three days" interval would silently
     * become "until the user happens to open the app".
     */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> initialize(context)
            }
        }
    }
}
