package com.tavern.app.console

import android.content.Context
import com.tavern.app.util.AssetExtractor
import org.json.JSONObject
import java.io.File

data class MemoryEnhancementConfig(
    // One switch per helper. Off means the helper does not run at all: no local rewriting or
    // reordering either. On without a usable address means the local implementation, which is
    // what the engine ran before these switches existed.
    val rewriteRetrieval: Boolean = true,
    val rewriteEndpoint: String = "",
    val rewriteModel: String = "",
    val rewriteApiKey: String = "",
    // Local rerank is a new layer: an install that never asked for a service keeps the order
    // the retrieval sources produced.
    val rerankRetrieval: Boolean = false,
    val rerankEndpoint: String = "",
    val rerankModel: String = "",
    val rerankApiKey: String = "",
) {
    val rewriteProviderEnabled: Boolean
        get() = rewriteRetrieval && rewriteEndpoint.isNotBlank() && rewriteModel.isNotBlank()

    val rerankProviderEnabled: Boolean
        get() = rerankRetrieval && rerankEndpoint.isNotBlank() && rerankModel.isNotBlank()
}

object MemoryEnhancementPreferences {
    private const val FILE_NAME = "memory-enhancement.json"

    private fun configFile(context: Context): File {
        val directory = File(AssetExtractor.getCoreDir(context), "data")
        if (!directory.exists()) {
            directory.mkdirs()
        }
        return File(directory, FILE_NAME)
    }

    fun load(context: Context): MemoryEnhancementConfig {
        val file = configFile(context)
        if (!file.exists()) {
            return MemoryEnhancementConfig()
        }
        val root = try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            return MemoryEnhancementConfig()
        }
        val rewrite = root.optJSONObject("rewrite") ?: JSONObject()
        val rerank = root.optJSONObject("rerank") ?: JSONObject()
        return MemoryEnhancementConfig(
            // The file written before the switches existed recorded only whether a service was
            // used. Query rewriting ran locally either way, so its absence means on; rerank had
            // no local layer, so its absence means whatever the old flag said.
            rewriteRetrieval = root.optBoolean("rewriteRetrieval", true),
            rewriteEndpoint = rewrite.optString("endpoint").orEmpty(),
            rewriteModel = rewrite.optString("model").orEmpty(),
            rewriteApiKey = rewrite.optString("apiKey").orEmpty(),
            rerankRetrieval = root.optBoolean(
                "rerankRetrieval",
                root.optBoolean("rerankEnabled", false),
            ),
            rerankEndpoint = rerank.optString("endpoint").orEmpty(),
            rerankModel = rerank.optString("model").orEmpty(),
            rerankApiKey = rerank.optString("apiKey").orEmpty(),
        )
    }

    fun save(context: Context, config: MemoryEnhancementConfig) {
        val file = configFile(context)
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        val root = JSONObject().apply {
            put("rewriteRetrieval", config.rewriteRetrieval)
            put("rewrite", JSONObject().apply {
                put("endpoint", config.rewriteEndpoint.trim())
                put("model", config.rewriteModel.trim())
                put("apiKey", config.rewriteApiKey.trim())
            })
            put("rerankRetrieval", config.rerankRetrieval)
            put("rerank", JSONObject().apply {
                put("endpoint", config.rerankEndpoint.trim())
                put("model", config.rerankModel.trim())
                put("apiKey", config.rerankApiKey.trim())
            })
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
