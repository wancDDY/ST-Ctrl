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

    /** Decode common HTML entities found in Atom feed titles and content. */
    private fun decodeHtmlEntities(raw: String): String {
        return raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
    }

    // Check the user's own repo for pre-patched ST core releases.
    // Each release should have a source zip (e.g. "tavern-core.zip") and a tag like "st-1.12.0".
    private const val ATOM_URL =
        "https://github.com/wancDDY/ST-Ctrl/releases.atom"

    // Cached version list
    private var cachedVersions: List<ReleaseInfo>? = null

    suspend fun listAllVersions(): Result<List<ReleaseInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(ATOM_URL).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "TavernApp")
            conn.setRequestProperty("Accept", "application/atom+xml")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000

            val body: String
            try {
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw Exception("HTTP $code")
                body = conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }

            val entryRegex = Regex("<entry>.*?</entry>", RegexOption.DOT_MATCHES_ALL)
            val versions = entryRegex.findAll(body).mapNotNull { match ->
                val entry = match.value
                val rawTitle = Regex("<title>(.*?)</title>").find(entry)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
                val title = decodeHtmlEntities(rawTitle)
                if (!title.startsWith("st-", ignoreCase = true)) return@mapNotNull null
                val version = title.removePrefix("st-").removePrefix("ST-").trimStart('v', 'V', ' ')
                val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                    .find(entry)?.groupValues?.get(1)?.trim() ?: ""
                val changelog = decodeHtmlEntities(content.replace(Regex("<[^>]+>"), "")).take(200)
                val downloadUrl = "https://codeload.github.com/wancDDY/ST-Ctrl/zip/refs/tags/$title"
                ReleaseInfo(version = version, downloadUrl = downloadUrl, changelog = changelog)
            }.toList()
            cachedVersions = versions
            versions
        }
    }

    /** Return cached list or fetch fresh if none cached */
    fun getCachedVersions(): List<ReleaseInfo>? = cachedVersions

    suspend fun checkLatest(): Result<ReleaseInfo> {
        return listAllVersions().map { it.firstOrNull() ?: throw Exception("未找到 ST 核心发布版本") }
    }

    suspend fun refreshVersions(): Result<List<ReleaseInfo>> {
        cachedVersions = null
        return listAllVersions()
    }
}
