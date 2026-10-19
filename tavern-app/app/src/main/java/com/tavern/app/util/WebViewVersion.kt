package com.tavern.app.util

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Version of the WebView engine that will render the core's pages.
 *
 * The shipped core uses color-mix(), oklch() and @layer, which Chromium only
 * understands from 111 on. Below that the page is not merely slower, it renders
 * wrong, so the version decides whether compat rendering is offered up front.
 */
object WebViewVersion {

    /** Lowest engine version on which the core's stylesheets render as written. */
    const val MIN_SUPPORTED_MAJOR = 111

    private val CHROME_UA = Regex("Chrome/(\\d+)")

    /**
     * Major engine version, or null when it cannot be determined — in which case
     * the caller stays quiet rather than warning about a version it never read.
     */
    fun majorVersion(context: Context): Int? {
        val fromPackage = try {
            WebView.getCurrentWebViewPackage()?.versionName
        } catch (_: Exception) {
            null
        }
        majorOf(fromPackage)?.let { return it }

        // Some builds report no package; the default user agent still carries the
        // engine's version, and reading it needs no WebView instance of our own.
        val userAgent = try {
            WebSettings.getDefaultUserAgent(context)
        } catch (_: Exception) {
            null
        } ?: return null
        return CHROME_UA.find(userAgent)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Leading digits of a WebView version name, e.g. 118.0.5993.111 -> 118. */
    fun majorOf(versionName: String?): Int? =
        versionName?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()
}
