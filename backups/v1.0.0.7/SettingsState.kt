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
    const val PREFS_NAME = "tavern_console_prefs"
    const val KEY_FAST_START = "fast_start"
    private const val KEY_PERF_MODE = "perf_mode"
    private const val KEY_KEEP_TAVERN = "keep_tavern_alive"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = prefs.getString(KEY_PERF_MODE, PerfMode.FULL.key) ?: PerfMode.FULL.key
        _perfMode.value = PerfMode.entries.firstOrNull { it.key == key } ?: PerfMode.FULL
        _fastStart = prefs.getBoolean(KEY_FAST_START, false)
        _keepTavernAlive = prefs.getBoolean(KEY_KEEP_TAVERN, true)
    }

    private val _perfMode = MutableStateFlow(PerfMode.FULL)
    val perfMode: StateFlow<PerfMode> = _perfMode.asStateFlow()

    // ── 快速启动 ──
    private var _fastStart = false
    fun fastStart(): Boolean = _fastStart
    fun setFastStart(ctx: Context, on: Boolean) {
        _fastStart = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_FAST_START, on).apply()
    }

    // ── 后台酒馆 ──
    private var _keepTavernAlive = true
    fun keepTavernAlive(): Boolean = _keepTavernAlive
    fun setKeepTavernAlive(ctx: Context, on: Boolean) {
        _keepTavernAlive = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_TAVERN, on).apply()
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

    fun niceValue(): Int = when (_perfMode.value) {
        PerfMode.FULL -> 0
        PerfMode.LIGHT -> 5
        PerfMode.BALANCED -> 10
        PerfMode.SAVE -> 15
    }

    /** V8 heap limit in MB — smaller heap = less GC work = less heat */
    fun maxOldSpaceMb(): Int = when (_perfMode.value) {
        PerfMode.FULL -> 256
        PerfMode.LIGHT -> 192
        PerfMode.BALANCED -> 128
        PerfMode.SAVE -> 96
    }

    fun uvPoolSize(): Int = when (_perfMode.value) {
        PerfMode.FULL -> 4
        PerfMode.LIGHT -> 3
        PerfMode.BALANCED -> 2
        PerfMode.SAVE -> 1
    }

    // Descriptions for each mode
    fun description(mode: PerfMode): List<String> = when (mode) {
        PerfMode.FULL -> listOf(
            "• CPU 优先级：正常",
            "• 堆内存上限：256 MB",
            "• IO 线程池：4 线程",
            "• 轮询：正常",
            "• 保活：每 5 分钟",
            "",
            "性能最佳，发热较高。"
        )
        PerfMode.LIGHT -> listOf(
            "• CPU 优先级：轻度降低",
            "• 堆内存上限：192 MB",
            "• IO 线程池：3 线程",
            "• 轮询：正常",
            "• 保活：每 10 分钟",
            "",
            "轻微降低 CPU 占用。"
        )
        PerfMode.BALANCED -> listOf(
            "• CPU 优先级：中度降低",
            "• 堆内存上限：128 MB",
            "• IO 线程池：2 线程",
            "• 轮询：正常",
            "• 保活：每 15 分钟",
            "",
            "CPU 占用明显下降，推荐日常使用。"
        )
        PerfMode.SAVE -> listOf(
            "• CPU 优先级：最低",
            "• 堆内存上限：96 MB",
            "• IO 线程池：1 线程",
            "• 轮询：2x 节流",
            "• 保活：每 30 分钟",
            "",
            "最大限度地降低 CPU 占用。切换后需重启 APP 生效。"
        )
    }
}
