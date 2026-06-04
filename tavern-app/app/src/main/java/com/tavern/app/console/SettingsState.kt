package com.tavern.app.console

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PerfMode(val label: String, val key: String) {
    FULL("性能优先", "full"),
    LIGHT("轻度优化", "light"),
    BALANCED("均衡模式", "balanced"),
    SAVE("深度优化", "save")
}

object SettingsState {
    private const val PREFS_NAME = "tavern_console_prefs"
    private const val KEY_PERF_MODE = "perf_mode"

    private val _perfMode = MutableStateFlow(PerfMode.FULL)
    val perfMode: StateFlow<PerfMode> = _perfMode.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_PERF_MODE, PerfMode.FULL.key) ?: PerfMode.FULL.key
        _perfMode.value = PerfMode.entries.firstOrNull { it.key == key } ?: PerfMode.FULL
    }

    fun setPerfMode(context: Context, mode: PerfMode) {
        _perfMode.value = mode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PERF_MODE, mode.key).apply()
        // Reschedule keep-alive alarm with the new interval
        try {
            com.tavern.app.service.KeepAliveMonitor.reschedule(context)
        } catch (_: Exception) {}
    }


    fun keepAliveIntervalMinutes(): Long = when (_perfMode.value) {
        PerfMode.FULL -> 5L
        PerfMode.LIGHT -> 10L
        PerfMode.BALANCED -> 15L
        PerfMode.SAVE -> 30L
    }

    // Descriptions for each mode
    fun description(mode: PerfMode): List<String> = when (mode) {
        PerfMode.FULL -> listOf(
            "• WebView 渲染优先级：高",
            "• WebView 缓存：默认策略",
            "• 后台保活检查：每 5 分钟",
            "",
            "性能最佳，耗电较高。"
        )
        PerfMode.LIGHT -> listOf(
            "• WebView 渲染优先级：普通",
            "• WebView 缓存：优先本地",
            "• 后台保活检查：每 10 分钟",
            "",
            "轻微优化，几乎不影响使用体验。"
        )
        PerfMode.BALANCED -> listOf(
            "• WebView 渲染优先级：普通",
            "• WebView 缓存：优先本地",
            "• 后台保活检查：每 15 分钟",
            "",
            "日常使用无感知，推荐。"
        )
        PerfMode.SAVE -> listOf(
            "• WebView 渲染优先级：普通",
            "• WebView 缓存：优先本地",
            "• 后台保活检查：每 30 分钟",
            "",
            "最大程度省电，适合长时间后台。"
        )
    }
}
