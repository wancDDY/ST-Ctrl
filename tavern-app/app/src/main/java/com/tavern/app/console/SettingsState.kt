package com.tavern.app.console

import android.content.Context
import com.tavern.app.ApplicationState
import com.tavern.app.backup.AutoBackupWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OptMode { AUTO, MANUAL }
enum class OptTier(val label: String, val factor: Double) {
    MAX("极限", 1.00),
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
    private const val KEY_LAN_ACCESS = "lan_access"
    private const val KEY_LAN_CUSTOM_TOKEN = "lan_custom_token"
    private const val KEY_BACKUP_LOCATION = "backup_location"
    private const val KEY_BACKUP_TREE_URI = "backup_tree_uri"
    private const val KEY_TAVERN_CHAT_VIRTUAL = "tavern_chat_virtual"
    private const val KEY_TAVERN_CHAR_IMPORT = "tavern_char_import"
    private const val KEY_TAVERN_PAUSE_RENDER = "tavern_pause_render"
    private const val KEY_TAVERN_PERF_ANIM = "tavern_perf_anim"
    private const val KEY_TAVERN_LONG_MEMORY = "tavern_long_memory"
    private const val KEY_TAVERN_CHAT_MEMORY = "tavern_chat_memory"
    private const val KEY_TAVERN_CHAT_MEMORY_GHOST = "tavern_chat_memory_ghost"
    private const val KEY_MEMORY_PROGRESS_NOTE = "memory_progress_note_shown"
    private const val KEY_TAVERN_CHAT_MEMORY_AUTO = "tavern_chat_memory_auto"
    private const val KEY_TAVERN_CHAT_MEMORY_PENDING = "tavern_chat_memory_pending_threshold"
    private const val KEY_TAVERN_CHAT_MEMORY_AGENT = "tavern_chat_memory_agent"
    private const val KEY_TAVERN_CHAT_MEMORY_BUDGET = "tavern_chat_memory_budget"
    private const val KEY_TAVERN_OPTS_SIGNATURE = "tavern_opts_signature"
    private const val KEY_SAMPLING_OVERRIDE = "sampling_override"
    private const val KEY_LAST_VERSION_NOTICE_CODE = "last_version_notice_code"
    private const val KEY_COMPAT_OFFER_VERSION = "compat_offer_webview_version"
    private const val KEY_LONG_MEMORY_DEFAULT_OFF = "long_memory_default_off"
    private const val KEY_NODE_PORT = "node_port"
    const val DEFAULT_NODE_PORT = 8000

    /**
     * The ceiling the engine clamps the budget to, repeated here so the page cannot offer a value
     * that arrives pre-clamped and reads back as something else.
     */
    internal const val MEMORY_BUDGET_MAX = 8000

    /**
     * The values the memory page offers. Zero is the engine's own sizing — a share of the prompt
     * window — and the rest are explicit ceilings, because the block is paid on every turn.
     */
    internal val MEMORY_BUDGET_CHOICES = listOf(0, 1200, 1600, 2400, 3200, 4000)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _keepTavernAlive = prefs.getBoolean(KEY_KEEP_TAVERN, true)
        _compatMode = prefs.getBoolean(KEY_COMPAT_MODE, false)
        _compatModeFlow.value = _compatMode
        _optMode = try { OptMode.valueOf(prefs.getString(KEY_OPT_MODE, "AUTO")!!) } catch (_: Exception) { OptMode.AUTO }
        _optTier = try { OptTier.valueOf(prefs.getString(KEY_OPT_TIER, "BALANCED")!!) } catch (_: Exception) { OptTier.BALANCED }
        _crashCount.set(prefs.getInt(KEY_CRASH_COUNT, 0))
        _lastCrashTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        _lanKeepEnabled = prefs.getBoolean(KEY_LAN_KEEP, false)
        // LAN access only auto-enables on launch when "keep-on" (lan_keep) is set.
        // The plain lan_access toggle is session-scoped: turning it on once does
        // NOT auto-open it on the next launch.
        _lanAccessEnabled = _lanKeepEnabled
        _lanAccessFlow.value = _lanAccessEnabled
        _lanCustomToken = prefs.getString(KEY_LAN_CUSTOM_TOKEN, "") ?: ""
        _backupLocation = prefs.getString(KEY_BACKUP_LOCATION, "public") ?: "public"
        _nodePort = prefs.getInt(KEY_NODE_PORT, DEFAULT_NODE_PORT).coerceIn(1024, 65535)
        _tavernChatVirtual = prefs.getBoolean(KEY_TAVERN_CHAT_VIRTUAL, true)
        _tavernCharImport = prefs.getBoolean(KEY_TAVERN_CHAR_IMPORT, true)
        _tavernPauseRender = prefs.getBoolean(KEY_TAVERN_PAUSE_RENDER, true)
        _tavernPerfAnim = prefs.getBoolean(KEY_TAVERN_PERF_ANIM, true)
        if (!prefs.getBoolean(KEY_LONG_MEMORY_DEFAULT_OFF, false)) {
            _tavernLongMemory = false
            prefs.edit()
                .putBoolean(KEY_TAVERN_LONG_MEMORY, false)
                .putBoolean(KEY_LONG_MEMORY_DEFAULT_OFF, true)
                .apply()
            writeTavernServerOpts(context, java.io.File(context.filesDir, "core"))
        } else {
            _tavernLongMemory = prefs.getBoolean(KEY_TAVERN_LONG_MEMORY, false)
        }
        // On by default: the per-chat pass replaced the retrieval engine, and a reader who never
        // opens the memory page would otherwise keep the feature they were told about turned off.
        _tavernChatMemory = prefs.getBoolean(KEY_TAVERN_CHAT_MEMORY, true)
        // Ghosting rewrites the prompt history, so it ships off until the reader opts in.
        _tavernChatMemoryGhost = prefs.getBoolean(KEY_TAVERN_CHAT_MEMORY_GHOST, false)
        // An unattended pass spends the reader's model quota; it stays off until they ask for it.
        _tavernChatMemoryAuto = prefs.getBoolean(KEY_TAVERN_CHAT_MEMORY_AUTO, false)
        _tavernChatMemoryPending = prefs.getInt(KEY_TAVERN_CHAT_MEMORY_PENDING, 0)
        // Off by default: with no takeover the feature is pure local, which is the only mode that
        // never spends the reader's quota without them asking for it.
        _tavernChatMemoryAgent = prefs.getBoolean(KEY_TAVERN_CHAT_MEMORY_AGENT, false)
        _tavernChatMemoryBudget = prefs.getInt(KEY_TAVERN_CHAT_MEMORY_BUDGET, 0)
            .coerceIn(0, MEMORY_BUDGET_MAX)
        // A flag switched before this launch may still be waiting for its restart.
        refreshCoreRestartPending(context)
    }

    private var _tavernChatVirtual = true
    fun tavernChatVirtualEnabled(): Boolean = _tavernChatVirtual
    fun setTavernChatVirtual(ctx: Context, on: Boolean) {
        _tavernChatVirtual = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAVERN_CHAT_VIRTUAL, on).apply()
    }

    private var _tavernCharImport = true
    fun tavernCharImportEnabled(): Boolean = _tavernCharImport
    fun setTavernCharImport(ctx: Context, on: Boolean) {
        _tavernCharImport = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAVERN_CHAR_IMPORT, on).apply()
        writeTavernServerOpts(ctx, java.io.File(ctx.filesDir, "core"))
    }

    private var _tavernPauseRender = true
    fun tavernPauseRenderEnabled(): Boolean = _tavernPauseRender
    fun setTavernPauseRender(ctx: Context, on: Boolean) {
        _tavernPauseRender = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAVERN_PAUSE_RENDER, on).apply()
    }

    private var _tavernPerfAnim = true
    fun tavernPerfAnimEnabled(): Boolean = _tavernPerfAnim
    fun setTavernPerfAnim(ctx: Context, on: Boolean) {
        _tavernPerfAnim = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAVERN_PERF_ANIM, on).apply()
    }

    private var _tavernChatMemory = false

    /** The per-chat summary pass, which replaces the retrieval engine while it is on. */
    fun chatMemoryEnabled(): Boolean = _tavernChatMemory
    fun setChatMemory(ctx: Context, on: Boolean) {
        _tavernChatMemory = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAVERN_CHAT_MEMORY, on).apply()
        writeTavernServerOpts(ctx, java.io.File(ctx.filesDir, "core"))
    }

    private var _tavernChatMemoryGhost = false

    /**
     * Hiding turns the memory already covers from the prompt. The core reads it once per start,
     * so unlike the memory switch itself this one waits for a restart.
     */
    /**
     * Whether the note about a pass stopping short has been shown.
     *
     * It explains the numbers on every chat, not one chat's state, so it is kept for the install
     * rather than per chat or per pass.
     */
    fun memoryProgressNotePending(ctx: Context): Boolean =
        !ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_MEMORY_PROGRESS_NOTE, false)

    fun markMemoryProgressNoteShown(ctx: Context) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MEMORY_PROGRESS_NOTE, true).apply()
    }

    fun chatMemoryGhostEnabled(): Boolean = _tavernChatMemoryGhost
    fun setChatMemoryGhost(ctx: Context, on: Boolean) {
        _tavernChatMemoryGhost = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAVERN_CHAT_MEMORY_GHOST, on).apply()
        writeTavernServerOpts(ctx, java.io.File(ctx.filesDir, "core"))
    }

    private var _tavernChatMemoryAgent = false

    /**
     * Whether memory maintenance may call a model at all. On means the agent owns it — the model
     * and the tools are whatever the agent page is configured with, and every pass bills there.
     * Off means the local rule ledger and the local retrieval index are the whole feature.
     */
    fun memoryAgentTakeover(): Boolean = _tavernChatMemoryAgent
    fun setMemoryAgentTakeover(ctx: Context, on: Boolean) {
        _tavernChatMemoryAgent = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAVERN_CHAT_MEMORY_AGENT, on).apply()
        // Read by the core once per start, so the switch waits for a restart to take effect.
        writeTavernServerOpts(ctx, java.io.File(ctx.filesDir, "core"))
    }

    private var _tavernChatMemoryBudget = 0

    /**
     * How many characters of memory may ride along with one generation. Zero lets the engine take
     * a share of the prompt window. A larger block reaches more old rows, and every turn pays for
     * it, so the number belongs to the reader rather than to a default.
     */
    fun chatMemoryBudget(): Int = _tavernChatMemoryBudget
    fun setChatMemoryBudget(ctx: Context, chars: Int) {
        _tavernChatMemoryBudget = chars.coerceIn(0, MEMORY_BUDGET_MAX)
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TAVERN_CHAT_MEMORY_BUDGET, _tavernChatMemoryBudget).apply()
        // Read per call from the options file, so this one takes effect on the next reply.
        writeTavernServerOpts(ctx, java.io.File(ctx.filesDir, "core"))
    }

    private var _tavernChatMemoryAuto = false
    private var _tavernChatMemoryPending = 0

    /**
     * Whether the console may run a memory pass on its own. Every pass is at least one model call,
     * so this is the reader's decision, not a default the feature takes for itself.
     */
    /**
     * How many unconsolidated floors a chat may accumulate before an unattended pass runs. Zero
     * means never; the reader picks the number because each pass is a paid model call and only
     * they know how much their own backlog is worth.
     */
    fun chatMemoryPendingThreshold(): Int = _tavernChatMemoryPending
    fun setChatMemoryPendingThreshold(ctx: Context, floors: Int) {
        _tavernChatMemoryPending = floors.coerceIn(0, 500)
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TAVERN_CHAT_MEMORY_PENDING, _tavernChatMemoryPending).apply()
    }

    fun chatMemoryAutoRun(): Boolean = _tavernChatMemoryAuto
    fun setChatMemoryAutoRun(ctx: Context, on: Boolean) {
        _tavernChatMemoryAuto = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAVERN_CHAT_MEMORY_AUTO, on).apply()
    }

    private var _tavernLongMemory = false
    fun tavernLongMemoryEnabled(): Boolean = _tavernLongMemory
    fun setTavernLongMemory(ctx: Context, on: Boolean) {
        _tavernLongMemory = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TAVERN_LONG_MEMORY, on).apply()
        writeTavernServerOpts(ctx, java.io.File(ctx.filesDir, "core"))
    }

    fun tavernReloadHash(): String = _tavernChatVirtual.toString()

    fun shouldShowVersionSummary(context: Context): Boolean {
        val currentCode = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionCode
                    .toLong()
            }
        } catch (_: Exception) {
            return false
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return currentCode > prefs.getLong(KEY_LAST_VERSION_NOTICE_CODE, 0L)
    }

    fun markVersionSummarySeen(context: Context) {
        val currentCode = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .versionCode
                    .toLong()
            }
        } catch (_: Exception) {
            return
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_VERSION_NOTICE_CODE, currentCode)
            .apply()
    }

    /** Persist server-side tavern opts so wrapper can expose them to the core. */
    fun writeTavernServerOpts(ctx: Context, coreDir: java.io.File) {
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = org.json.JSONObject().apply {
                put("charImport", prefs.getBoolean(KEY_TAVERN_CHAR_IMPORT, true))
                put("longMemory", prefs.getBoolean(KEY_TAVERN_LONG_MEMORY, false))
                put("chatMemory", prefs.getBoolean(KEY_TAVERN_CHAT_MEMORY, true))
                put("chatMemoryGhost", prefs.getBoolean(KEY_TAVERN_CHAT_MEMORY_GHOST, false))
                put("chatMemoryAgent", prefs.getBoolean(KEY_TAVERN_CHAT_MEMORY_AGENT, false))
                // Absent means "let the engine size it"; writing a zero would be a budget of zero
                // to anyone reading the file without the clamp in front of it.
                val memoryBudget = prefs.getInt(KEY_TAVERN_CHAT_MEMORY_BUDGET, 0)
                if (memoryBudget > 0) put("chatMemoryBudget", memoryBudget)
                // Vectors for recall. Written here, not only handed to the core's environment at
                // start-up, because the engine reads this file per call: switching between the
                // bundled model and a service then takes effect on the next reply instead of
                // waiting for a restart nobody announced.
                val embedding = com.tavern.app.console.MemoryEmbeddingPreferences.load(ctx)
                put("chatMemoryEmbedding", embedding.retrieval)
                // Whether building the index pauses between batches. Written beside the switch it
                // belongs to: one file answers both questions, and the engine reads it per call.
                put("chatMemoryWarmThrottle", embedding.throttle)
                if (embedding.providerEnabled) {
                    put("chatMemoryEmbeddingEndpoint", embedding.endpoint.trim())
                    put("chatMemoryEmbeddingModel", embedding.model.trim())
                    put("chatMemoryEmbeddingKey", embedding.apiKey.trim())
                }
                // Which connection the memory pass talks to. Written as a small object rather
                // than three loose keys so the core reads one decision instead of inferring it
                // from missing fields.
                val memoryModel = MemoryModelPreferences.load(ctx)
                put("chatMemoryModel", org.json.JSONObject().apply {
                    put("mode", if (memoryModel.reuse) "reuse" else "custom")
                    if (!memoryModel.reuse) {
                        put("endpoint", memoryModel.endpoint.trim())
                        put("model", memoryModel.model.trim())
                        put("apiKey", memoryModel.apiKey.trim())
                    }
                })
                // Mirrored so a restarted core keeps an override the reader switched on; the
                // console also posts it to the running service for immediate effect.
                samplingOverride(ctx)?.let { put("samplingOverride", it) }
            }
            java.io.File(coreDir, "tavern-opts.json").writeText(json.toString())
            // Not every key here is startup-only, so the write itself says nothing about a
            // restart: the decision compares the startup-only values against the ones the
            // running service read.
            refreshCoreRestartPending(ctx)
        } catch (_: Exception) {}
    }

    /**
     * The sampling override the reader switched on for this session, or null when they did
     * not. Never empty: an override with no fields is the same as no override.
     */
    fun samplingOverride(ctx: Context): org.json.JSONObject? {
        val stored = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SAMPLING_OVERRIDE, null)
            ?: return null
        return try {
            val parsed = org.json.JSONObject(stored)
            if (parsed.length() == 0) null else parsed
        } catch (_: Exception) {
            null
        }
    }

    fun setSamplingOverride(ctx: Context, override: org.json.JSONObject?) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(
                KEY_SAMPLING_OVERRIDE,
                if (override == null || override.length() == 0) null else override.toString(),
            )
            .apply()
    }

    /**
     * Records that the service that is now running read the options written so far.
     *
     * Called when the service is observed healthy. The boot flow writes the options before
     * asking the service to start, so "healthy" and "read these options" are the same moment.
     * What is recorded is [restartSignature]: the values a start has to read in order to match
     * what is switched on now.
     */
    fun markTavernServerOptsRead(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TAVERN_OPTS_SIGNATURE, restartSignature(prefs))
            .apply()
        refreshCoreRestartPending(ctx)
    }

    /**
     * Whether the running service is on older values than the console now holds, as state the
     * console can observe.
     *
     * A startup-only option reaches the running service only through a restart, and the reader
     * decides when that happens: switching a flag leaves the service on the old value and the
     * restart action on screen until they take it. The value has to follow a switch on its own,
     * because the page that owns the switch is not necessarily the page showing the action.
     */
    private val _coreRestartPending = MutableStateFlow(false)
    val coreRestartPending: StateFlow<Boolean> = _coreRestartPending.asStateFlow()

    /** Recomputes [coreRestartPending] from the recorded signature. */
    fun refreshCoreRestartPending(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _coreRestartPending.value = optionsPendingRestart(
            recorded = prefs.getString(KEY_TAVERN_OPTS_SIGNATURE, null),
            current = restartSignature(prefs),
        )
    }

    /**
     * The values a service start has to read for the startup-only options to be the ones on
     * screen. Compared as a whole rather than counted per write: switching a flag on and back
     * off ends at the values the running service already has, and offering a restart for that
     * would interrupt a conversation to apply nothing.
     */
    private fun restartSignature(prefs: android.content.SharedPreferences): String {
        return restartSignature(
            charImport = prefs.getBoolean(KEY_TAVERN_CHAR_IMPORT, true),
            longMemory = prefs.getBoolean(KEY_TAVERN_LONG_MEMORY, false),
        )
    }

    /**
     * The startup-only options on their own, so the set can be checked without a Context.
     *
     * The memory switches are deliberately absent: the core reads them from the options file on
     * every call, so a flip takes effect immediately and asking for a restart would interrupt a
     * conversation to apply nothing. What stays is the pair the core reads once, at boot.
     */
    internal fun restartSignature(
        charImport: Boolean,
        longMemory: Boolean,
    ): String = listOf(
        charImport,
        longMemory,
    ).joinToString("|")

    /**
     * The decision on its own, so it can be tested without a Context.
     *
     * Without a recorded signature the app cannot know what the running service saw (a build
     * that never wrote one, or a first start that has not happened yet), and claiming a restart
     * is needed would be a guess.
     */
    internal fun optionsPendingRestart(recorded: String?, current: String): Boolean {
        return recorded != null && recorded != current
    }

    /**
     * The app-side settings that make up "my setup": theme, port, the tavern
     * switches and the auto-backup schedule. They travel inside the backup archive
     * so a restore also puts the app back the way it was.
     *
     * Device-specific grants and engine-derived switches are left out on purpose: the
     * backup folder URI is granted per install and cannot be transplanted, and compat
     * rendering answers what this phone's WebView can render, so restoring a backup made
     * before it was turned on must not silently turn it off again. The LAN token is a
     * secret and follows [includeSecrets], the same opt-in the tavern's `secrets.json` uses.
     */
    /**
     * The memory switches a backup carries, read from the stored preferences.
     *
     * Pure and in one place because the two sides have to agree: a key added to the export but not
     * to the restore is a setting the archive appears to have and does not put back.
     */
    internal fun memoryBackupFields(all: Map<String, *>): Map<String, Any> = linkedMapOf(
        "tavern_chat_memory" to (all[KEY_TAVERN_CHAT_MEMORY] as? Boolean ?: true),
        "tavern_chat_memory_ghost" to (all[KEY_TAVERN_CHAT_MEMORY_GHOST] as? Boolean ?: false),
        "tavern_chat_memory_budget" to (all[KEY_TAVERN_CHAT_MEMORY_BUDGET] as? Int ?: 0),
        "tavern_chat_memory_agent" to (all[KEY_TAVERN_CHAT_MEMORY_AGENT] as? Boolean ?: false),
        "tavern_chat_memory_pending" to (all[KEY_TAVERN_CHAT_MEMORY_PENDING] as? Int ?: 0),
    )

    /**
     * The preference writes a backup's memory section asks for.
     *
     * A key the archive does not carry is left alone, so an older archive restores what this build
     * understands instead of resetting the rest to defaults. Bounds are applied here, not at the
     * call site: a hand-edited archive must not be able to set a threshold the console refuses.
     */
    internal fun memoryBackupWrites(settings: org.json.JSONObject): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        if (settings.has("tavern_chat_memory")) {
            out[KEY_TAVERN_CHAT_MEMORY] = settings.optBoolean("tavern_chat_memory", true)
        }
        if (settings.has("tavern_chat_memory_ghost")) {
            out[KEY_TAVERN_CHAT_MEMORY_GHOST] = settings.optBoolean("tavern_chat_memory_ghost", false)
        }
        if (settings.has("tavern_chat_memory_budget")) {
            out[KEY_TAVERN_CHAT_MEMORY_BUDGET] =
                settings.optInt("tavern_chat_memory_budget", 0).coerceIn(0, MEMORY_BUDGET_MAX)
        }
        if (settings.has("tavern_chat_memory_agent")) {
            out[KEY_TAVERN_CHAT_MEMORY_AGENT] = settings.optBoolean("tavern_chat_memory_agent", false)
        }
        if (settings.has("tavern_chat_memory_pending")) {
            out[KEY_TAVERN_CHAT_MEMORY_PENDING] =
                settings.optInt("tavern_chat_memory_pending", 0).coerceIn(0, 500)
        }
        return out
    }

    fun exportBackupSettings(context: Context, includeSecrets: Boolean): org.json.JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return org.json.JSONObject().apply {
            put("keep_tavern_alive", prefs.getBoolean(KEY_KEEP_TAVERN, true))
            put("opt_mode", _optMode.name)
            put("opt_tier", _optTier.name)
            put("backup_location", prefs.getString(KEY_BACKUP_LOCATION, "public") ?: "public")
            put("tavern_chat_virtual", prefs.getBoolean(KEY_TAVERN_CHAT_VIRTUAL, true))
            put("tavern_char_import", prefs.getBoolean(KEY_TAVERN_CHAR_IMPORT, true))
            put("tavern_pause_render", prefs.getBoolean(KEY_TAVERN_PAUSE_RENDER, true))
            put("tavern_perf_anim", prefs.getBoolean(KEY_TAVERN_PERF_ANIM, true))
            put("tavern_long_memory", prefs.getBoolean(KEY_TAVERN_LONG_MEMORY, false))
            memoryBackupFields(prefs.all).forEach { (key, value) -> put(key, value) }
            put("long_memory_default_off", prefs.getBoolean(KEY_LONG_MEMORY_DEFAULT_OFF, false))
            put("node_port", _nodePort)
            // Absent means "follow the system", which is a state worth keeping absent.
            if (prefs.contains(ThemeState.KEY_DARK_MODE)) {
                put(ThemeState.KEY_DARK_MODE, prefs.getBoolean(ThemeState.KEY_DARK_MODE, false))
            }
            put(AutoBackupWorker.KEY_ENABLED, prefs.getBoolean(AutoBackupWorker.KEY_ENABLED, true))
            put(AutoBackupWorker.KEY_INTERVAL, prefs.getInt(AutoBackupWorker.KEY_INTERVAL, 3))
            put(AutoBackupWorker.KEY_MAX_KEEP, prefs.getInt(AutoBackupWorker.KEY_MAX_KEEP, 3))
            put(
                AutoBackupWorker.KEY_INCLUDE_API_KEYS,
                prefs.getBoolean(AutoBackupWorker.KEY_INCLUDE_API_KEYS, true)
            )
            put(
                AutoBackupWorker.KEY_REMIND_ONLY,
                prefs.getBoolean(AutoBackupWorker.KEY_REMIND_ONLY, false)
            )
            if (includeSecrets) {
                put("lan_keep", prefs.getBoolean(KEY_LAN_KEEP, false))
                put("lan_custom_token", prefs.getString(KEY_LAN_CUSTOM_TOKEN, "") ?: "")
            }
        }
    }

    /**
     * Applies settings read from a backup archive. A key that is absent is left
     * alone, so an archive written by another build still restores what this one
     * understands instead of resetting the rest to defaults.
     */
    fun applyBackupSettings(context: Context, settings: org.json.JSONObject) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        if (settings.has("keep_tavern_alive")) {
            editor.putBoolean(KEY_KEEP_TAVERN, settings.optBoolean("keep_tavern_alive", true))
        }
        // "compat_mode" is ignored even when an older archive carries it: it describes the
        // engine this install renders with, not a choice the reader made.
        if (settings.has("backup_location")) {
            editor.putString(
                KEY_BACKUP_LOCATION,
                settings.optString("backup_location", "public").ifBlank { "public" }
            )
        }
        if (settings.has("tavern_chat_virtual")) {
            editor.putBoolean(KEY_TAVERN_CHAT_VIRTUAL, settings.optBoolean("tavern_chat_virtual", true))
        }
        if (settings.has("tavern_char_import")) {
            editor.putBoolean(KEY_TAVERN_CHAR_IMPORT, settings.optBoolean("tavern_char_import", true))
        }
        if (settings.has("tavern_pause_render")) {
            editor.putBoolean(KEY_TAVERN_PAUSE_RENDER, settings.optBoolean("tavern_pause_render", true))
        }
        if (settings.has("tavern_perf_anim")) {
            editor.putBoolean(KEY_TAVERN_PERF_ANIM, settings.optBoolean("tavern_perf_anim", true))
        }
        if (settings.has("tavern_long_memory")) {
            editor.putBoolean(KEY_TAVERN_LONG_MEMORY, settings.optBoolean("tavern_long_memory", false))
        }
        // The memory switches, each on its own key rather than nested under another setting's.
        // An archive written by a build that only knows the summariser carries no
        // `tavern_long_memory`, and nesting would leave the memory switch at this install's value.
        memoryBackupWrites(settings).forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
            }
        }
        if (settings.has("long_memory_default_off")) {
            editor.putBoolean(
                KEY_LONG_MEMORY_DEFAULT_OFF,
                settings.optBoolean("long_memory_default_off", false)
            )
        }
        if (settings.has("node_port")) {
            val port = settings.optInt("node_port", DEFAULT_NODE_PORT)
            if (port in 1024..65535) editor.putInt(KEY_NODE_PORT, port)
        }
        if (settings.has(AutoBackupWorker.KEY_ENABLED)) {
            editor.putBoolean(
                AutoBackupWorker.KEY_ENABLED,
                settings.optBoolean(AutoBackupWorker.KEY_ENABLED, true)
            )
        }
        if (settings.has(AutoBackupWorker.KEY_INTERVAL)) {
            editor.putInt(
                AutoBackupWorker.KEY_INTERVAL,
                settings.optInt(AutoBackupWorker.KEY_INTERVAL, 3).coerceAtLeast(1)
            )
        }
        if (settings.has(AutoBackupWorker.KEY_MAX_KEEP)) {
            editor.putInt(
                AutoBackupWorker.KEY_MAX_KEEP,
                settings.optInt(AutoBackupWorker.KEY_MAX_KEEP, 3).coerceAtLeast(1)
            )
        }
        if (settings.has(AutoBackupWorker.KEY_INCLUDE_API_KEYS)) {
            editor.putBoolean(
                AutoBackupWorker.KEY_INCLUDE_API_KEYS,
                settings.optBoolean(AutoBackupWorker.KEY_INCLUDE_API_KEYS, true)
            )
        }
        if (settings.has(AutoBackupWorker.KEY_REMIND_ONLY)) {
            editor.putBoolean(
                AutoBackupWorker.KEY_REMIND_ONLY,
                settings.optBoolean(AutoBackupWorker.KEY_REMIND_ONLY, false)
            )
        }
        if (settings.has("lan_keep")) {
            editor.putBoolean(KEY_LAN_KEEP, settings.optBoolean("lan_keep", false))
        }
        if (settings.has(ThemeState.KEY_DARK_MODE)) {
            editor.putBoolean(
                ThemeState.KEY_DARK_MODE,
                settings.optBoolean(ThemeState.KEY_DARK_MODE, false)
            )
        }
        if (settings.has("lan_custom_token")) {
            editor.putString(KEY_LAN_CUSTOM_TOKEN, settings.optString("lan_custom_token", ""))
        }
        // opt_mode / opt_tier are strings; a value this build does not know leaves
        // the current one in place rather than falling back to a default tier.
        if (settings.has("opt_mode")) {
            val mode = settings.optString("opt_mode", "")
            if (OptMode.entries.any { it.name == mode }) editor.putString(KEY_OPT_MODE, mode)
        }
        if (settings.has("opt_tier")) {
            val tier = settings.optString("opt_tier", "")
            if (OptTier.entries.any { it.name == tier }) editor.putString(KEY_OPT_TIER, tier)
        }
        editor.apply()

        // Re-read what was just written so the in-memory copy matches, then rebuild
        // the files the core and the native side read at start.
        init(context)
        ThemeState.init(context)
        writeTavernServerOpts(context, java.io.File(context.filesDir, "core"))
        writePerfParams(context)
    }

    @Volatile
    private var _compatMode = false
    private val _compatModeFlow = MutableStateFlow(false)
    val compatMode: StateFlow<Boolean> = _compatModeFlow.asStateFlow()
    fun compatModeEnabled(): Boolean = _compatMode
    fun setCompatMode(ctx: Context, on: Boolean) {
        _compatMode = on; _compatModeFlow.value = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_COMPAT_MODE, on).apply()
    }

    /**
     * WebView major version the compat-rendering offer was already made for. Kept per
     * version rather than as a plain flag: an engine that updates to another build still
     * below the threshold is a different renderer, and the reader has not been told about
     * that one.
     */
    fun compatOfferVersion(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_COMPAT_OFFER_VERSION, 0)

    fun markCompatOffered(ctx: Context, webViewMajor: Int) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COMPAT_OFFER_VERSION, webViewMajor).apply()
    }

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

    private var _keepTavernAlive = true
    fun keepTavernAlive(): Boolean = _keepTavernAlive
    fun setKeepTavernAlive(ctx: Context, on: Boolean) {
        _keepTavernAlive = on
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_TAVERN, on).apply()
    }

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
        val count = _crashCount.get()
        if (count == 0) return 1.0
        val elapsed = System.currentTimeMillis() - _lastCrashTime
        // Recovered once a full hour has passed since the last crash.
        if (elapsed >= 60 * 60_000) return 1.0
        // Progressive cut by how many times node crashed recently.
        val byCount = when {
            count >= 4 -> 0.25
            count >= 2 -> 0.35
            else -> 0.50
        }
        // Ease one step up once the crash is more than half an hour old.
        return if (elapsed >= 30 * 60_000) (byCount + 0.25).coerceAtMost(0.75) else byCount
    }

    @Volatile var heuristicBackupKb: Long = 0
    @Volatile var heuristicTotalRamMb: Long = 0
    @Volatile var heuristicIsEmulator: Boolean = false
    @Volatile var thermalLevel: Int = 0

    private val cpuCores: Int get() = Runtime.getRuntime().availableProcessors()

    fun keepAliveIntervalMinutes(): Long =
        // Shorter ticks: a killed :node (OEM memory pressure) is healed within
        // 2-3 minutes even if the main process is dead too.
        if (heuristicIsEmulator || heuristicTotalRamMb in 1..3000) 3L else 2L

    fun maxHeapMb(): Int {
        val ram = heuristicTotalRamMb
        if (ram <= 0) return 512
        return ((ram * 0.15).toInt()).coerceIn(256, 1024)
    }

    fun maxPoolSize(): Int = cpuCores.coerceIn(2, 8)

    fun currentFactor(): Double = when (_optMode) {
        OptMode.MANUAL -> _optTier.factor
        OptMode.AUTO -> {
            val ram = heuristicTotalRamMb
            // Maps 1000MB RAM to 0.15, 12000MB to 1.0.
            var f = ((ram - 1000) / 11000.0).coerceIn(0.15, 1.0)
            f *= crashRecoveryFactor()
            f *= when (thermalLevel) { 1 -> 0.80; 2 -> 0.55; 3 -> 0.30; else -> 1.0 }
            if (heuristicIsEmulator) f *= 0.75
            f.coerceIn(0.15, 1.0)
        }
    }

    fun maxOldSpaceMb(): Int {
        val m = maxHeapMb()
        // Keep a practical floor above the Node startup baseline; 256MB can
        // OOM while SillyTavern serves the full UI to an old WebView.
        val floor = 512.coerceAtMost(m)
        return (m * currentFactor()).toInt().coerceIn(floor, m)
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
        // A crash older than an hour counts as recovered: clear it in prefs so
        // the stale count does not keep lowering future launches after a reload.
        if (_lastCrashTime > 0 && System.currentTimeMillis() - _lastCrashTime >= 60 * 60_000) {
            _crashCount.set(0)
            prefs.edit().putInt(KEY_CRASH_COUNT, 0).apply()
        }
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
    private var _lanAccessEnabled = false
    private val _lanAccessFlow = MutableStateFlow(false)
    val lanAccess: StateFlow<Boolean> = _lanAccessFlow.asStateFlow()
    fun lanAccessEnabled(): Boolean = _lanAccessEnabled
    fun setLanAccessEnabled(ctx: Context, on: Boolean) {
        _lanAccessEnabled = on; _lanAccessFlow.value = on
        if (!on) ApplicationState.lanSessionActive = false
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_LAN_ACCESS, on).apply()
    }

    private var _lanKeepEnabled = false
    fun lanKeepEnabled(): Boolean = _lanKeepEnabled
    fun setLanKeepEnabled(ctx: Context, on: Boolean) {
        _lanKeepEnabled = on
        // "Keep-on" implies LAN access is on — flip it together so the
        // proxy starts for the current process, not just the next boot.
        if (on && !_lanAccessEnabled) {
            _lanAccessEnabled = true
            _lanAccessFlow.value = true
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_LAN_ACCESS, true).apply()
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_LAN_KEEP, on).apply()
    }

    // If set, the LAN proxy accepts this fixed token INSTEAD of the random
    // per-boot one. Leave blank to keep using the random token.
    private var _lanCustomToken = ""
    fun lanCustomToken(): String = _lanCustomToken
    fun setLanCustomToken(ctx: Context, value: String) {
        _lanCustomToken = value.trim()
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAN_CUSTOM_TOKEN, _lanCustomToken).apply()
    }

    // "public" = user-authorized SAF tree, "private" = app-private dir.
    // Node listens on this port. A change only applies on the next start,
    // because the socket is already bound for the running process.
    private var _nodePort = DEFAULT_NODE_PORT
    fun nodePort(): Int = _nodePort
    fun setNodePort(ctx: Context, port: Int) {
        _nodePort = port.coerceIn(1024, 65535)
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_NODE_PORT, _nodePort).apply()
    }

    private var _backupLocation = "public"
    fun backupLocation(): String = _backupLocation
    fun setBackupLocation(ctx: Context, location: String) {
        _backupLocation = if (location == "private") "private" else "public"
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BACKUP_LOCATION, _backupLocation).apply()
    }

    fun backupTreeUri(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BACKUP_TREE_URI, "") ?: ""

    fun setBackupTreeUri(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BACKUP_TREE_URI, value).apply()
    }
}
