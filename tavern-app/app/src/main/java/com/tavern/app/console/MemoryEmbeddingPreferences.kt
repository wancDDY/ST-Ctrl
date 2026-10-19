package com.tavern.app.console

import android.content.Context
import org.json.JSONObject
import java.io.File

data class MemoryEmbeddingConfig(
    // Whether vectors take part in recall at all. Off means neither the local vectors nor a
    // service is used; on with nothing filled in means the local vectors, and a completed
    // address means the reader's own service.
    val retrieval: Boolean = true,
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val autoIndex: Boolean = true,
) {
    // A service is only asked for vectors when the switch is on and the address is complete.
    // The local vectors need no configuration, which is why the switch alone is enough.
    val providerEnabled: Boolean
        get() = retrieval && endpoint.isNotBlank() && model.isNotBlank()
}

object MemoryEmbeddingPreferences {
    private const val FILE_NAME = "memory-embedding.json"
    private const val KEY_RETRIEVAL = "retrieval"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_AUTO_INDEX = "auto_index"

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
        return MemoryEmbeddingConfig(
            // Written by builds that had no switch: the local vectors ran unconditionally then,
            // so a file without the key keeps them running.
            retrieval = root.optBoolean(KEY_RETRIEVAL, true),
            endpoint = root.optString(KEY_ENDPOINT).orEmpty(),
            model = root.optString(KEY_MODEL).orEmpty(),
            apiKey = root.optString(KEY_API_KEY).orEmpty(),
            autoIndex = root.optBoolean(KEY_AUTO_INDEX, true),
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
    }
}
