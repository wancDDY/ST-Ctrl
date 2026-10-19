package com.tavern.app.webview

import java.net.URI

/**
 * Who is asking to reach the local tavern, and may they.
 *
 * Any page the user loads inside the tavern window can reach the local tavern, so
 * a page from elsewhere has to be identified before its request goes out. The
 * requesting page can only be named by the headers the WebView sends with the
 * request: from the interceptor there is no other handle on it.
 */
internal object TavernAccessPolicy {

    /** A loopback host is the tavern itself; nothing else in the window is. */
    fun isTavernHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removeSuffix(".") ?: return false
        return h == "127.0.0.1" || h == "localhost" || h == "::1"
    }

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            URI(url).host?.lowercase()?.removeSuffix(".")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The address of the page that issued the request, taken from the headers the
     * browser sets itself. A page cannot forge them, but it can suppress the
     * referrer, so a missing answer means "unknown" rather than "the tavern".
     *
     * A value without a readable host counts as missing as well: it names no site,
     * so nobody downstream could act on it.
     */
    fun requesterUrl(headers: Map<String, String>): String? =
        header(headers, "Referer")?.takeIf { hostOf(it) != null }
            ?: header(headers, "Origin")?.takeIf { hostOf(it) != null }

    fun requesterHost(headers: Map<String, String>): String? = hostOf(requesterUrl(headers))

    private fun header(headers: Map<String, String>, name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.trim()?.takeIf { it.isNotEmpty() }

    sealed interface Verdict {
        /** Not aimed at the tavern; nothing to decide here. */
        data object Unrelated : Verdict

        /** The tavern's own page, or a page the user already allowed. */
        data object Allow : Verdict

        /** A page from elsewhere is asking; put it to the user. An empty host means the origin could not be read. */
        data class Ask(val host: String) : Verdict
    }

    /**
     * Only requests aimed at the tavern are judged. Everything else in the window
     * is left alone: images, fonts and third-party pages have no business being
     * filtered here, and touching them would change how the tavern renders.
     *
     * A request whose origin cannot be read is judged by what it could do. A page
     * that wants to *read* an answer needs CORS, and a CORS request always carries
     * the origin header, so a plain GET with no origin can only produce a response
     * the caller cannot look at — the tavern's own service worker traffic is of
     * that kind and must not be interrupted. Anything that could change state is
     * put to the user, because the tavern's own writes come from its own origin
     * and are never anonymous.
     */
    fun verdict(
        targetUrl: String?,
        method: String?,
        headers: Map<String, String>,
        granted: (String) -> Boolean,
    ): Verdict {
        if (!isTavernHost(hostOf(targetUrl))) return Verdict.Unrelated
        val requester = requesterHost(headers)
        if (requester != null && isTavernHost(requester)) return Verdict.Allow
        if (requester == null) {
            val m = method?.uppercase() ?: "GET"
            return if (m == "GET" || m == "HEAD") Verdict.Allow else Verdict.Ask("")
        }
        return if (granted(requester)) Verdict.Allow else Verdict.Ask(requester)
    }
}
