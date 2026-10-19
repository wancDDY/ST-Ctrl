package com.tavern.app.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
        // &amp; decodes last: decoding it first would turn "&amp;lt;" into a tag.
        return raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
    }

    /**
     * Release bodies arrive as escaped markup. Decoding has to happen before the
     * tag strip, otherwise the strip finds nothing and the decoded tags reach the
     * reader; block ends become newlines so paragraphs stay apart.
     */
    private fun plainText(raw: String): String = decodeHtmlEntities(raw)
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</p\\s*>"), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    // Check the user's own repo for pre-patched ST core releases.
    // Each release should have a source zip (e.g. "tavern-core.zip") and a tag like "st-1.12.0".
    private const val API_URL =
        "https://api.github.com/repos/wancDDY/ST-Ctrl/releases?per_page=20"
    private const val ATOM_URL =
        "https://github.com/wancDDY/ST-Ctrl/releases.atom"

    // Cached version list
    private var cachedVersions: List<ReleaseInfo>? = null

    suspend fun listAllVersions(): Result<List<ReleaseInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            try {
                parseFromApi()
            } catch (_: Exception) {
                parseFromAtom()
            }
        }
    }

    private fun parseFromApi(): List<ReleaseInfo> {
        val arr = JSONArray(httpGet(API_URL, "application/vnd.github+json"))
        val versions = mutableListOf<ReleaseInfo>()
        for (i in 0 until arr.length()) {
            val release = arr.getJSONObject(i)
            val tag = release.optString("tag_name", "").trim()
            if (!tag.startsWith("st-", ignoreCase = true)) continue
            val version = tag.removePrefix("st-").removePrefix("ST-").trimStart('v', 'V', ' ')
            // The full body, not a preview: this text is what the release-notes button opens,
            // and a truncated release note is worse than none — the reader cannot tell
            // where it stops. The list row shortens it for display instead.
            val changelog = plainText(release.optString("body", ""))
            var downloadUrl = "https://github.com/wancDDY/ST-Ctrl/releases/download/$tag/tavern-core.zip"
            val assets = release.optJSONArray("assets")
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    if (asset.optString("name", "").equals("tavern-core.zip", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", downloadUrl)
                        break
                    }
                }
            }
            versions += ReleaseInfo(version, downloadUrl, changelog)
        }
        if (versions.isEmpty()) throw Exception("未找到 ST 核心发布版本")
        return sorted(versions)
    }

    private fun parseFromAtom(): List<ReleaseInfo> {
        val body = httpGet(ATOM_URL, "application/atom+xml")
        val entryRegex = Regex("<entry>.*?</entry>", RegexOption.DOT_MATCHES_ALL)
        val versions = entryRegex.findAll(body).mapNotNull { match ->
            val entry = match.value
            val rawTitle = Regex("<title>(.*?)</title>").find(entry)
                ?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
            val title = decodeHtmlEntities(rawTitle)
            if (!title.startsWith("st-", ignoreCase = true)) return@mapNotNull null
            val version = title.removePrefix("st-").removePrefix("ST-").trimStart('v', 'V', ' ')
            val content = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
                .find(entry)?.groupValues?.get(1)?.trim() ?: ""
            val changelog = plainText(content)
            val downloadUrl =
                "https://github.com/wancDDY/ST-Ctrl/releases/download/$title/tavern-core.zip"
            ReleaseInfo(version = version, downloadUrl = downloadUrl, changelog = changelog)
        }.toList()
        if (versions.isEmpty()) throw Exception("未找到 ST 核心发布版本")
        return sorted(versions)
    }

    private fun sorted(versions: List<ReleaseInfo>): List<ReleaseInfo> {
        val result = versions.sortedWith { left, right ->
            VersionComparator.compare(right.version, left.version)
        }
        cachedVersions = result
        return result
    }

    private fun httpGet(url: String, accept: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "TavernApp")
        conn.setRequestProperty("Accept", accept)
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw Exception("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Return cached list or fetch fresh if none cached */
    fun getCachedVersions(): List<ReleaseInfo>? = cachedVersions

    suspend fun checkLatest(): Result<ReleaseInfo> {
        return listAllVersions().map {
            it.maxWithOrNull { left, right ->
                VersionComparator.compare(left.version, right.version)
            } ?: throw Exception("未找到 ST 核心发布版本")
        }
    }

    suspend fun refreshVersions(): Result<List<ReleaseInfo>> {
        cachedVersions = null
        return listAllVersions()
    }
}
