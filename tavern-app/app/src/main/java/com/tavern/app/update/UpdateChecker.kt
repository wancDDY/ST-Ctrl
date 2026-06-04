package com.tavern.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    data class ReleaseInfo(
        val version: String,
        val downloadUrl: String,
        val changelog: String
    )

    // Check the user's own repo for pre-patched ST core releases.
    // Each release should have a source zip (e.g. "tavern-core.zip") and a tag like "st-1.12.0".
    private const val ATOM_URL =
        "https://github.com/wancDDY/ST-Ctrl/releases.atom"

    suspend fun checkLatest(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(ATOM_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "TavernApp")
            conn.setRequestProperty("Accept", "application/atom+xml")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $code")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val entryRegex = Regex("<entry>.*?</entry>", RegexOption.DOT_MATCHES_ALL)
            // Only look at st- tags, ignore v-tags (app releases)
            val stEntry = entryRegex.findAll(body).firstOrNull { entry ->
                val t = Regex("<title>(.*?)</title>").find(entry.value)?.groupValues?.get(1)?.trim() ?: ""
                t.startsWith("st-", ignoreCase = true)
            } ?: throw Exception("未找到 ST 核心发布版本")

            val entry = stEntry.value
            val title = Regex("<title>(.*?)</title>").find(entry)?.groupValues?.get(1)?.trim()
                ?: throw Exception("无法解析版本号")
            // Tags are like "st-1.12.0", extract version number
            val version = title.removePrefix("st-").removePrefix("ST-").trimStart('v', 'V', ' ')

            val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                .find(entry)?.groupValues?.get(1)?.trim() ?: ""
            val changelog = content.replace(Regex("<[^>]+>"), "").take(500)

            // Download the pre-patched zip from the release
            val downloadUrl = "https://codeload.github.com/wancDDY/ST-Ctrl/zip/refs/tags/$title"

            ReleaseInfo(version = version, downloadUrl = downloadUrl, changelog = changelog)
        }
    }
}
