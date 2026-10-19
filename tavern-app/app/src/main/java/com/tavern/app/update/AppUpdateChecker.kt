package com.tavern.app.update

import android.os.Build
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
        return listAllVersions().map {
            it.maxWithOrNull { left, right ->
                VersionComparator.compare(left.version, right.version)
            } ?: throw Exception("未找到应用发布版本")
        }
    }

    suspend fun refreshReleases(): Result<List<AppRelease>> {
        cachedReleases = null
        return listAllVersions()
    }

    // GitHub API (primary)
    private fun parseFromApi(): List<AppRelease> {
        val json = httpGet(API_URL)
        val arr = org.json.JSONArray(json)
        val list = mutableListOf<AppRelease>()
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            val tag = rel.optString("tag_name", "").trim()
            if (tag.isEmpty() || tag.startsWith("st-", ignoreCase = true)) continue
            // A pre-release is not something to push at users who never opted in.
            if (rel.optBoolean("prerelease", false)) continue
            val version = tag.trimStart('v', 'V', ' ')
            val changelog = cleanChangelog(rel.optString("body", ""))
            val downloadUrl = "https://github.com/wancDDY/ST-Ctrl/releases/tag/$tag"

            // Pick the asset matching this install's ABI family.
            var directUrl = ""
            val abiName = preferredAbiName()
            val assets = rel.optJSONArray("assets")
            if (assets != null && abiName != null) {
                for (j in 0 until assets.length()) {
                    val a = assets.getJSONObject(j)
                    val name = a.optString("name", "")
                    if (name.contains(abiName, ignoreCase = true)) {
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
        list.sortWith { left, right ->
            VersionComparator.compare(right.version, left.version)
        }
        cachedReleases = list
        return list
    }

    // Atom feed (fallback)
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
            val changelog = cleanChangelog(content)
            val downloadUrl = "https://github.com/wancDDY/ST-Ctrl/releases/tag/$title"
            val abiName = preferredAbiName()
            // Best-effort guess; the API path is preferred for the real name.
            val directUrl = if (abiName == null) {
                downloadUrl
            } else {
                "https://github.com/wancDDY/ST-Ctrl/releases/download/$title/ST-Ctrl-$title-$abiName.apk"
            }
            AppRelease(version = version, downloadUrl = downloadUrl,
                directDownloadUrl = directUrl, changelog = changelog)
        }.toList()
        if (releases.isEmpty()) throw Exception("未找到发布版本")
        val sorted = releases.sortedWith { left, right ->
            VersionComparator.compare(right.version, left.version)
        }
        cachedReleases = sorted
        return sorted
    }

    private fun preferredAbiName(): String? {
        val supported = Build.SUPPORTED_ABIS
        return when {
            supported.any { it == "arm64-v8a" } -> "arm64"
            supported.any { it == "armeabi-v7a" || it == "armeabi" } -> "arm32"
            else -> null
        }
    }

    private fun cleanChangelog(raw: String): String {
        // Decode before stripping. The release body arrives with its markup
        // escaped, so a strip that runs first finds no tags, and the entities then
        // decode into literal tags that the reader ends up seeing. Block ends turn
        // into newlines first so paragraphs do not run together.
        return decodeHtmlEntities(raw)
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n\n")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun decodeHtmlEntities(raw: String): String {
        // &amp; decodes last: decoding it first would turn "&amp;lt;" into a tag.
        return raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
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
