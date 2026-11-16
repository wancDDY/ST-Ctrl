package com.tavern.app.console

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Which connection the memory pass talks to.
 *
 * The tavern's own model is the default. A separate one exists because consolidation reads whole
 * chats: it is the most expensive thing the memory feature does, and a small model does it for a
 * fraction of the bill. Whatever is typed here is used by the pass only — the chat page keeps
 * whatever the reader chose there.
 */
data class MemoryModelConfig(
    val reuse: Boolean = true,
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
)

object MemoryModelPreferences {
    internal const val FILE_NAME = "memory-model.json"
    private const val KEY_REUSE = "reuse"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key"

    private fun configFile(context: Context): File {
        return File(context.noBackupFilesDir, FILE_NAME)
    }

    fun load(context: Context): MemoryModelConfig {
        val file = configFile(context)
        if (!file.exists()) return MemoryModelConfig()
        val root = try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            return MemoryModelConfig()
        }
        return MemoryModelConfig(
            reuse = root.optBoolean(KEY_REUSE, true),
            endpoint = root.optString(KEY_ENDPOINT).orEmpty(),
            model = root.optString(KEY_MODEL).orEmpty(),
            apiKey = root.optString(KEY_API_KEY).orEmpty(),
        )
    }

    fun save(context: Context, config: MemoryModelConfig) {
        val file = configFile(context)
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        val root = JSONObject().apply {
            put(KEY_REUSE, config.reuse)
            put(KEY_ENDPOINT, config.endpoint.trim())
            put(KEY_MODEL, config.model.trim())
            put(KEY_API_KEY, config.apiKey.trim())
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
        // The pass reads this through the core's options file, per call, so the next reply uses
        // the new connection instead of the one the core started with.
        SettingsState.writeTavernServerOpts(context, File(context.filesDir, "core"))
    }

    /** One line for the row: which connection the pass is on. */
    fun summary(context: Context): String {
        val config = load(context)
        if (config.reuse) return "复用核心里的模型"
        val name = config.model.trim()
        return when {
            name.isNotEmpty() -> name
            config.endpoint.isNotBlank() -> "已填写地址，还没有模型名"
            else -> "还没有填写"
        }
    }
}
