package com.tavern.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateChecker {

    data class AppRelease(
        val version: String,
        val downloadUrl: String,
        val directDownloadUrl: String,
        val changelog: String
    )

    private const val ATOM_URL =
        "https://github.com/wancDDY/ST-Ctrl/releases.atom"

    // Cached version list
    private var cachedReleases: List<AppRelease>? = null

    suspend fun listAllVersions(): Result<List<AppRelease>> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(ATOM_URL).openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/atom+xml")
            connection.setRequestProperty("User-Agent", "ST-Ctrl/1.0")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000

            val body: String
            try {
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) throw Exception("HTTP $responseCode")
                body = connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val entryRegex = Regex("<entry>.*?</entry>", RegexOption.DOT_MATCHES_ALL)
            val releases = entryRegex.findAll(body).mapNotNull { match ->
                val entry = match.value
                val title = Regex("<title>(.*?)</title>").find(entry)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
                // Only v-tags (app releases), not st-tags
                if (title.startsWith("st-", ignoreCase = true)) return@mapNotNull null
                val version = title.trimStart('v', 'V', ' ')
                val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                    .find(entry)?.groupValues?.get(1)?.trim() ?: ""
                val changelog = content.replace(Regex("<[^>]+>"), "").take(200)
                val downloadUrl = "https://github.com/wancDDY/ST-Ctrl/releases/tag/$title"
                // Direct APK download: try arm64 first, user can pick arm32 from release page
                val directUrl = "https://github.com/wancDDY/ST-Ctrl/releases/download/$title/st-ctrl-arm64.apk"
                AppRelease(version = version, downloadUrl = downloadUrl,
                    directDownloadUrl = directUrl, changelog = changelog)
            }.toList()
            cachedReleases = releases
            releases
        }
    }

    fun getCachedReleases(): List<AppRelease>? = cachedReleases

    suspend fun check(): Result<AppRelease> {
        return listAllVersions().map { it.firstOrNull() ?: throw Exception("未找到应用发布版本") }
    }

    suspend fun refreshReleases(): Result<List<AppRelease>> {
        cachedReleases = null
        return listAllVersions()
    }
}
