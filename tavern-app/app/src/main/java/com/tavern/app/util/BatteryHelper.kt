package com.tavern.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

object BatteryHelper {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        if (intent.resolveActivity(context.packageManager) != null) {
            context.startActivity(intent)
        }
    }

    /** Read current thermal level. Returns 0-3 (normal, warm, hot, critical). */
    fun thermalLevel(context: Context): Int {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return 0
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                when (pm.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_MODERATE -> 1
                    PowerManager.THERMAL_STATUS_SEVERE -> 2
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY -> 3
                    else -> 0
                }
            } else 0
        } catch (_: Exception) { 0 }
    }
}
