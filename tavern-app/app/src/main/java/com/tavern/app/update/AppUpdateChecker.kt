package com.tavern.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateChecker {

    data class AppRelease(
        val version: String,
        val downloadUrl: String,
        val changelog: String
    )

    private const val ATOM_URL =
        "https://github.com/wancDDY/ST-Ctrl/releases.atom"

    suspend fun check(): Result<AppRelease> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(ATOM_URL).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/atom+xml")
            connection.setRequestProperty("User-Agent", "ST-Ctrl/1.0")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode")
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()

            // Find the first v-tag entry (app releases, not st- tags)
            val entryRegex = Regex("<entry>.*?</entry>", RegexOption.DOT_MATCHES_ALL)
            val appEntry = entryRegex.findAll(body).firstOrNull { entry ->
                val t = Regex("<title>(.*?)</title>").find(entry.value)?.groupValues?.get(1)?.trim() ?: ""
                !t.startsWith("st-", ignoreCase = true)
            } ?: throw Exception("未找到应用发布版本")

            val entry = appEntry.value
            val title = Regex("<title>(.*?)</title>").find(entry)?.groupValues?.get(1)?.trim()
                ?: throw Exception("无法解析版本号")
            val version = title.trimStart('v', 'V', ' ')

            val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                .find(entry)?.groupValues?.get(1)?.trim() ?: ""
            val changelog = content.replace(Regex("<[^>]+>"), "").take(500)

            val downloadUrl = "https://github.com/wancDDY/ST-Ctrl/releases/tag/$title"

            AppRelease(version = version, downloadUrl = downloadUrl, changelog = changelog)
        }
    }
}
