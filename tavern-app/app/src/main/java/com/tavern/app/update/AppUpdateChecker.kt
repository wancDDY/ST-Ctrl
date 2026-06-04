package com.tavern.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateChecker {

    data class AppRelease(
        val version: String,
        val downloadUrl: String,
        val changelog: String
    )

    private const val GITHUB_API =
        "https://api.github.com/repos/wancDDY/ST-Ctrl/releases/latest"

    suspend fun check(): Result<AppRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(GITHUB_API).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "ST-Ctrl/1.0")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (e: Exception) { "" }
                throw Exception("$responseCode $errorBody")
            }

            val json = connection.inputStream.bufferedReader().readText()
            val release = JSONObject(json)
            val tagName = release.getString("tag_name").trimStart('v')

            val assets = release.getJSONArray("assets")
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            val changelog = release.optString("body", "")

            AppRelease(version = tagName, downloadUrl = apkUrl, changelog = changelog)
        }
    }
}
