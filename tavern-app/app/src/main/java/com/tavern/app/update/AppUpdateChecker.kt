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

    // GitHub API: gives real asset names so the direct APK link always matches
    // whatever the release assets are actually called.
    private const val API_URL =
        "https://api.github.com/repos/wancDDY/ST-Ctrl/releases?per_page=20"

    // Fallback feed (no auth, no rate limit) used when the API is unavailable.
    private const val ATOM_URL =
        "https://github.com/wancDDY/ST-Ctrl/releases.atom"

    // Cached version list
    private var cachedReleases: List<AppRelease>? = null

    suspend fun listAllVersions(): Result<List<AppRelease>> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                parseFromApi()
            } catch (e: Exception) {
                // API failed (rate limit / network) — fall back to the atom feed.
                parseFromAtom()
            }
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

    // ── GitHub API (primary) ──
    private fun parseFromApi(): List<AppRelease> {
        val json = httpGet(API_URL)
        val arr = org.json.JSONArray(json)
        val list = mutableListOf<AppRelease>()
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            val tag = rel.optString("tag_name", "").trim()
            if (tag.isEmpty() || tag.startsWith("st-", ignoreCase = true)) continue
            val version = tag.trimStart('v', 'V', ' ')
            val changelog = rel.optString("body", "").replace(Regex("<[^>]+>"), "").take(200)
            val downloadUrl = "https://github.com/wancDDY/ST-Ctrl/releases/tag/$tag"

            // Pick the first arm64 asset (excludes arm32/armv7 by name).
            var directUrl = ""
            val assets = rel.optJSONArray("assets")
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.optString("name", "")
                    if (name.contains("arm64", ignoreCase = true)) {
                        directUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
            }
            // No matching asset — point at the release page so the user can
            // still get the APK manually.
            if (directUrl.isEmpty()) directUrl = downloadUrl

            list += AppRelease(
                version = version,
                downloadUrl = downloadUrl,
                directDownloadUrl = directUrl,
                changelog = changelog
            )
        }
        if (list.isEmpty()) throw Exception("未找到发布版本")
        cachedReleases = list
        return list
    }

    // ── Atom feed (fallback) ──
    private fun parseFromAtom(): List<AppRelease> {
        val body = httpGet(ATOM_URL)
        val entryRegex = Regex("<entry>.*?</entry>", RegexOption.DOT_MATCHES_ALL)
        val releases = entryRegex.findAll(body).mapNotNull { match ->
            val entry = match.value
            val title = Regex("<title>(.*?)</title>").find(entry)?.groupValues?.get(1)?.trim()
                ?: return@mapNotNull null
            // Only v-tags (app releases), not st-tags
            if (title.startsWith("st-", ignoreCase = true)) return@mapNotNull null
            val version = title.trimStart('v', 'V', ' ')
            val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                .find(entry)?.groupValues?.get(1)?.trim() ?: ""
            val changelog = content.replace(Regex("<[^>]+>"), "").take(200)
            val downloadUrl = "https://github.com/wancDDY/ST-Ctrl/releases/tag/$title"
            // Best-effort guess; the API path is preferred for the real name.
            val directUrl = "https://github.com/wancDDY/ST-Ctrl/releases/download/$title/st-ctrl-arm64.apk"
            AppRelease(version = version, downloadUrl = downloadUrl,
                directDownloadUrl = directUrl, changelog = changelog)
        }.toList()
        if (releases.isEmpty()) throw Exception("未找到发布版本")
        cachedReleases = releases
        return releases
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "ST-Ctrl/1.0")
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) throw Exception("HTTP $responseCode")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
