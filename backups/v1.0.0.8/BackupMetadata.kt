package com.tavern.app.backup

import org.json.JSONObject

data class BackupMetadata(
    val version: Int = 1,
    val timestamp: String,
    val appVersion: String,
    val coreVersion: String,
    val fileCount: Int,
    val totalSizeBytes: Long,
    val source: String = ""
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version)
        put("timestamp", timestamp)
        put("app_version", appVersion)
        put("core_version", coreVersion)
        put("file_count", fileCount)
        put("total_size_bytes", totalSizeBytes)
        if (source.isNotEmpty()) put("source", source)
    }.toString(2)

    companion object {
        fun fromJson(json: String): BackupMetadata {
            val obj = JSONObject(json)
            return BackupMetadata(
                version = obj.optInt("version", 1),
                timestamp = obj.optString("timestamp", ""),
                appVersion = obj.optString("app_version", "unknown"),
                coreVersion = obj.optString("core_version", "unknown"),
                fileCount = obj.optInt("file_count", 0),
                totalSizeBytes = obj.optLong("total_size_bytes", 0L),
                source = obj.optString("source", "")
            )
        }
    }
}
