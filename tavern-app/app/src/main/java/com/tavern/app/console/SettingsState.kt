package com.tavern.app.console

import android.content.Context
import com.tavern.app.ApplicationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OptMode { AUTO, MANUAL }
enum class OptTier(val label: String, val factor: Double) {
    HIGH("性能优先", 0.70),
    BALANCED("均衡", 0.40),
    SAVE("省电", 0.20)
}

object SettingsState {
    const val PREFS_NAME = "tavern_console_prefs"
    private const val KEY_KEEP_TAVERN = "keep_tavern_alive"
    private const val KEY_COMPAT_MODE = "compat_mode"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"
    private const val KEY_OPT_MODE = "opt_mode"
    private const val KEY_OPT_TIER = "opt_tier"
    private const val KEY_LAN_KEEP = "lan_keep"
    private const val KEY_LAN_CUSTOM_TOKEN = "lan_custom_token"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _keepTavernAlive = prefs.getBoolean(KEY_KEEP_TAVERN, true)
        _compatMode = prefs.getBoolean(KEY_COMPAT_MODE, false)
        _compatModeFlow.value = _compatMode
        _optMode = try { OptMode.valueOf(prefs.getString(KEY_OPT_MODE, "AUTO")!!) } catch (_: Exception) { OptMode.AUTO }
        _optTier = try { OptTier.valueOf(prefs.getString(KEY_OPT_TIER, "BALANCED")!!) } catch (_: Exception) { OptTier.BALANCED }
        _crashCount.set(prefs.getInt(KEY_CRASH_COUNT, 0))
        _lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        // LAN access always starts OFF unless user enabled "keep on"
        _lanKeepEnabled = prefs.getBoolean(KEY_LAN_KEEP, false)
        _lanAccessEnabled = _lanKeepEnabled  // auto-enable if keep-on is set
        _lanAccessFlow.value = _lanAccessEnabled
        _lanCustomToken = prefs.getString(KEY_LAN_CUSTOM_TOKEN, "") ?: ""
    }

    // ── 兼容模式 ──
    private var _compatMode = false
    private val _compatModeFlow = MutableStateFlow(false)
    val compatMode: StateFlow<Boolean> = _compatModeFlow.asStateFlow()
    fun compatModeEnabled(): Boolean = _compatMode
    fun setCompatMode(ctx: Context, on: Boolean) {
        _compatMode = on; _compatModeFlow.value = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMPAT_MODE, on).apply()
    }

    // ── 优化模式 ──
    private var _optMode = OptMode.AUTO
    fun optMode(): OptMode = _optMode
    fun setOptMode(ctx: Context, m: OptMode) {
        _optMode = m
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_OPT_MODE, m.name).apply()
        writePerfParams(ctx)  // next Node start uses the new params
    }

    private var _optTier = OptTier.BALANCED
    fun optTier(): OptTier = _optTier
    fun setOptTier(ctx: Context, t: OptTier) {
        _optTier = t
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_OPT_TIER, t.name).apply()
        writePerfParams(ctx)  // next Node start uses the new params
    }

    // ── 后台酒馆 ──
    private var _keepTavernAlive = true
    fun keepTavernAlive(): Boolean = _keepTavernAlive
    fun setKeepTavernAlive(ctx: Context, on: Boolean) {
        _keepTavernAlive = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_TAVERN, on).apply()
    }

    // ── 崩溃渐进恢复 ──
    private val _crashCount = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var _lastCrashTime: Long = 0
    val crashCount: Int get() = _crashCount.get()
    fun recordNodeCrash(ctx: Context) {
        _crashCount.incrementAndGet()
        _lastCrashTime = System.currentTimeMillis()
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CRASH_COUNT, _crashCount.get()).putLong(KEY_LAST_CRASH_TIME, _lastCrashTime).apply()
    }
    private fun crashRecoveryFactor(): Double {
        if (_crashCount.get() == 0) return 1.0
        val elapsed = System.currentTimeMillis() - _lastCrashTime
        return when {
            elapsed < 30 * 60_000 -> 0.5
            elapsed < 60 * 60_000 -> 0.75
            else -> { _crashCount.set(0); 1.0 }
        }
    }

    // ═══ 设备数据 ═══
    @Volatile var heuristicBackupKb: Long = 0
    @Volatile var heuristicTotalRamMb: Long = 0
    @Volatile var heuristicIsEmulator: Boolean = false
    @Volatile var thermalLevel: Int = 0

    private val cpuCores: Int get() = Runtime.getRuntime().availableProcessors()

    fun keepAliveIntervalMinutes(): Long =
        // Shorter ticks: a killed :node (OEM memory pressure) is healed within
        // 2-3 minutes even if the main process is dead too.
        if (heuristicIsEmulator || heuristicTotalRamMb in 1..3000) 3L else 2L

    // ═══ 根据设备硬件计算各参数的理论上限 ═══
    fun maxHeapMb(): Int {
        val ram = heuristicTotalRamMb
        if (ram <= 0) return 512
        return ((ram * 0.15).toInt()).coerceIn(256, 1024)
    }

    fun maxPoolSize(): Int = cpuCores.coerceIn(2, 8)

    // ═══ 当前生效的分配因子（0.0~1.0） ═══
    fun currentFactor(): Double = when (_optMode) {
        OptMode.MANUAL -> _optTier.factor
        OptMode.AUTO -> {
            val ram = heuristicTotalRamMb
            // 1000MB→0.15, 12000MB→1.0
            var f = ((ram - 1000) / 11000.0).coerceIn(0.15, 1.0)
            f *= crashRecoveryFactor()
            f *= when (thermalLevel) { 1 -> 0.80; 2 -> 0.55; 3 -> 0.30; else -> 1.0 }
            if (heuristicIsEmulator) f *= 0.75
            f.coerceIn(0.15, 1.0)
        }
    }

    // ═══ 对外参数 ═══

    fun maxOldSpaceMb(): Int {
        val m = maxHeapMb()
        return (m * currentFactor()).toInt().coerceIn(256, m)
    }

    fun uvPoolSize(): Int {
        val m = maxPoolSize()
        return (m * currentFactor()).toInt().coerceIn(2, m)
    }

    fun niceValue(): Int = when {
        currentFactor() < 0.25 -> 10
        currentFactor() < 0.50 -> 5
        else -> 0
    }

    fun timerThrottleEnabled(): Boolean = currentFactor() < 0.25

    fun thermalLabel(): String = when (thermalLevel) { 1 -> "微热"; 2 -> "热"; 3 -> "过热"; else -> "正常" }

    // ═══ 跨进程性能参数快照 ═══
    // Node lives in the :node process with its own SettingsState instance.
    // All perf params are computed HERE (main process) and shipped to :node
    // via SharedPreferences, so UI and actual Node startup are consistent.

    /** Snapshot of the computed perf params, consumed by the :node process. */
    data class PerfParams(
        val maxOldSpaceMb: Int,
        val uvPoolSize: Int,
        val niceValue: Int,
        val factor: Double,
        val timerThrottle: Boolean,
    )

    private const val PERF_MAX_OLD_SPACE = "perf_max_old_space"
    private const val PERF_UV_POOL = "perf_uv_pool"
    private const val PERF_NICE = "perf_nice"
    private const val PERF_FACTOR = "perf_factor"
    private const val PERF_TIMER_THROTTLE = "perf_timer_throttle"

    /**
     * Prepare the perf snapshot for the :node process. Call in the main
     * process right before requesting Node startup: syncs the crash counter
     * from prefs (crashes are recorded by :node), then writes the freshly
     * computed params so :node picks them up.
     */
    fun preparePerfParams(ctx: Context) {
        // Sync crash state recorded by :node on previous runs.
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _crashCount.set(prefs.getInt(KEY_CRASH_COUNT, 0))
        _lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        writePerfParams(ctx)
    }

    fun writePerfParams(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(PERF_MAX_OLD_SPACE, maxOldSpaceMb())
                .putInt(PERF_UV_POOL, uvPoolSize())
                .putInt(PERF_NICE, niceValue())
                .putFloat(PERF_FACTOR, currentFactor().toFloat())
                .putBoolean(PERF_TIMER_THROTTLE, timerThrottleEnabled())
                .apply()
        } catch (_: Exception) {}
    }

    /** Read the params computed by the main process (used by :node). */
    fun readPerfParams(ctx: Context): PerfParams {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PerfParams(
            maxOldSpaceMb = prefs.getInt(PERF_MAX_OLD_SPACE, maxHeapMb()),
            uvPoolSize = prefs.getInt(PERF_UV_POOL, maxPoolSize()),
            niceValue = prefs.getInt(PERF_NICE, 0),
            factor = prefs.getFloat(PERF_FACTOR, 0.4f).toDouble(),
            timerThrottle = prefs.getBoolean(PERF_TIMER_THROTTLE, false),
        )
    }

    // ── 局域网访问 ──
    private var _lanAccessEnabled = false
    private val _lanAccessFlow = MutableStateFlow(false)
    val lanAccess: StateFlow<Boolean> = _lanAccessFlow.asStateFlow()
    fun lanAccessEnabled(): Boolean = _lanAccessEnabled
    fun setLanAccessEnabled(ctx: Context, on: Boolean) {
        _lanAccessEnabled = on; _lanAccessFlow.value = on
        if (!on) ApplicationState.lanSessionActive = false
    }

    private var _lanKeepEnabled = false
    fun lanKeepEnabled(): Boolean = _lanKeepEnabled
    fun setLanKeepEnabled(ctx: Context, on: Boolean) {
        _lanKeepEnabled = on
        // "保持开启" implies LAN access is on — flip it together so the
        // proxy actually starts for the current process, not just next boot.
        if (on && !_lanAccessEnabled) {
            _lanAccessEnabled = true
            _lanAccessFlow.value = true
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_LAN_KEEP, on).apply()
    }

    // ── 自定义 Token（可选）──
    // If set, the LAN proxy accepts this fixed token INSTEAD of the random
    // per-boot one. Leave blank to keep using the random token.
    private var _lanCustomToken = ""
    fun lanCustomToken(): String = _lanCustomToken
    fun setLanCustomToken(ctx: Context, value: String) {
        _lanCustomToken = value.trim()
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAN_CUSTOM_TOKEN, _lanCustomToken).apply()
    }
}
