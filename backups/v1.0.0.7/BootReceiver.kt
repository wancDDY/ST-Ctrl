package com.tavern.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tavern.app.console.SettingsState

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(SettingsState.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SettingsState.KEY_FAST_START, false)) return
        Log.i("BootReceiver", "快速启动：开机自启 Node.js（低功耗）")
        val svc = Intent(context, TavernForegroundService::class.java).apply {
            action = TavernForegroundService.ACTION_BOOT_START
        }
        context.startForegroundService(svc)
    }
}
