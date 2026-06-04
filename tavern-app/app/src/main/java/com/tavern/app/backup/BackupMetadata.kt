package com.tavern.app.backup

import org.json.JSONObject

data class BackupMetadata(
    val version: Int = 1,
    val timestamp: String,
    val appVersion: String,
    val coreVersion: String,
    val fileCount: Int,
    val totalSizeBytes: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("version", version)
        put("timestamp", timestamp)
        put("app_version", appVersion)
        put("core_version", coreVersion)
        put("file_count", fileCount)
        put("total_size_bytes", totalSizeBytes)
    }.toString(2)

    companion object {
        fun fromJson(json: String): BackupMetadata {
            val obj = JSONObject(json)
            return BackupMetadata(
                version = obj.optInt("version", 1),
                timestamp = obj.getString("timestamp"),
                appVersion = obj.getString("app_version"),
                coreVersion = obj.getString("core_version"),
                fileCount = obj.getInt("file_count"),
                totalSizeBytes = obj.getLong("total_size_bytes")
            )
        }
    }
}
