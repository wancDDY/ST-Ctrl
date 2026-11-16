package com.tavern.app.console

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Which of the three ways recall compares an old turn against the question. */
enum class MemoryRetrievalMode {
    /** Words only. Free, offline, and blind to a paraphrase. */
    KEYWORD,

    /** The bundled model over loopback. Offline and free, costs memory. */
    LOCAL,

    /** The reader's own OpenAI-compatible /embeddings. Online, billed to them. */
    SERVICE,
}

data class MemoryEmbeddingConfig(
    // Whether vectors take part in recall at all. Off means neither the local vectors nor a
    // service is used; on with nothing filled in means the local vectors, and a completed
    // address means the reader's own service.
    //
    // Off by default. Turning it on costs a first pass over every sentence of the chat, and on a
    // long role-play that pass runs for tens of minutes on the device — measured, not estimated.
    // The words-only path answers most questions already, so the cost is the reader's to accept,
    // not something to spend on their behalf at first launch.
    val retrieval: Boolean = false,
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val autoIndex: Boolean = true,
    // Whether building the index hands the processor back between batches. On by default: the same
    // work either way, but continuous for nine minutes feels like a hot phone while intermittent
    // for fifteen does not, and the reader is going to be holding the device.
    val throttle: Boolean = true,
    /**
     * Whether the reader has been shown what the three ways of matching do.
     *
     * The default is words-only, which is also what a reader who never opened this screen gets.
     * That is a reasonable default, but it is not a decision they made, and the difference only
     * shows up as a reply that misses something the archive holds.
     */
    val onboarded: Boolean = false,
) {
    // A service is only asked for vectors when the switch is on and the address is complete.
    // The local vectors need no configuration, which is why the switch alone is enough.
    val providerEnabled: Boolean
        get() = retrieval && endpoint.isNotBlank() && model.isNotBlank()

    /**
     * The mode as one value.
     *
     * Kept here rather than in the screen because three places have to agree on it — the engine,
     * the page's own description, and the picker's initial selection — and a half-filled service
     * address must read as "local", not as "service that silently fails".
     */
    val mode: MemoryRetrievalMode
        get() = when {
            !retrieval -> MemoryRetrievalMode.KEYWORD
            providerEnabled -> MemoryRetrievalMode.SERVICE
            else -> MemoryRetrievalMode.LOCAL
        }
}

object MemoryEmbeddingPreferences {
    internal const val FILE_NAME = "memory-embedding.json"
    private const val KEY_RETRIEVAL = "retrieval"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_AUTO_INDEX = "auto_index"
    private const val KEY_SCHEMA = "schema"
    private const val KEY_THROTTLE = "throttle"
    private const val KEY_ONBOARDED = "onboarded"

    // Tells a stored "retrieval": true apart from a chosen one. Without this key the value is
    // the default of a build that had no switch, so it reads as off; with the key it is what the
    // reader picked.
    private const val SCHEMA = 2

    private fun configFile(context: Context): File {
        return File(context.noBackupFilesDir, FILE_NAME)
    }

    fun load(context: Context): MemoryEmbeddingConfig {
        val file = configFile(context)
        if (!file.exists()) {
            return MemoryEmbeddingConfig()
        }
        val root = try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            return MemoryEmbeddingConfig()
        }
        val schema = root.optInt(KEY_SCHEMA, 0)
        val stored = root.optBoolean(KEY_RETRIEVAL, false)
        return MemoryEmbeddingConfig(
            // A file without the key predates the switch, when the local vectors ran
            // unconditionally; it reads as off, same as a fresh install, because nobody chose
            // that cost. The schema separates that old default from a real choice — without it,
            // a stored "retrieval": true is the former, however it is written down.
            retrieval = if (schema >= SCHEMA) stored else false,
            endpoint = root.optString(KEY_ENDPOINT).orEmpty(),
            model = root.optString(KEY_MODEL).orEmpty(),
            apiKey = root.optString(KEY_API_KEY).orEmpty(),
            autoIndex = root.optBoolean(KEY_AUTO_INDEX, true),
            throttle = root.optBoolean(KEY_THROTTLE, true),
            onboarded = root.optBoolean(KEY_ONBOARDED, false),
        )
    }

    fun save(context: Context, config: MemoryEmbeddingConfig) {
        val file = configFile(context)
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        val root = JSONObject().apply {
            put(KEY_RETRIEVAL, config.retrieval)
            put(KEY_ENDPOINT, config.endpoint.trim())
            put(KEY_MODEL, config.model.trim())
            put(KEY_API_KEY, config.apiKey.trim())
            put(KEY_AUTO_INDEX, config.autoIndex)
            put(KEY_THROTTLE, config.throttle)
            put(KEY_ONBOARDED, config.onboarded)
            put(KEY_SCHEMA, SCHEMA)
        }
        temporary.writeText(root.toString())
        temporary.setReadable(false, false)
        temporary.setReadable(true, true)
        temporary.setWritable(false, false)
        temporary.setWritable(true, true)
        if (!temporary.renameTo(file)) {
            file.writeText(root.toString())
            temporary.delete()
        }
        // The engine reads these through the core's options file, per call. Rewriting it here is
        // what makes a change take effect on the next reply rather than at the next app start.
        SettingsState.writeTavernServerOpts(context, File(context.filesDir, "core"))
    }
}
