package com.tavern.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val changelog: String
    )

    private const val GITHUB_API =
        "https://api.github.com/repos/SillyTavern/SillyTavern/releases/latest"

    suspend fun checkLatest(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(GITHUB_API).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.setRequestProperty("User-Agent", "TavernApp/1.0")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (e: Exception) { "" }
                throw Exception("GitHub API returned $responseCode: $errorBody")
            }

            val json = connection.inputStream.bufferedReader().readText()
            val release = JSONObject(json)
            val tagName = release.getString("tag_name").trimStart('v')

            val assets = release.getJSONArray("assets")
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".zip") && (name.contains("Source") || name.contains("source"))) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }

            if (downloadUrl.isEmpty()) {
                throw Exception("No matching source ZIP found in release assets")
            }

            val changelog = release.optString("body", "No changelog available")

            ReleaseInfo(version = tagName, downloadUrl = downloadUrl, changelog = changelog)
        }
    }
}
