package com.tavern.app.webview

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceResponse
import com.tavern.app.log.TavernLog
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Asks the user before a page from another site reaches the local tavern.
 *
 * The request is held rather than refused while the dialog is up: refusing it
 * first and asking afterwards would leave the page with a failed call, and the
 * user would have to trigger the same action again. Holding it means one tap on
 * "allow" is enough and the page carries on as if nothing had happened.
 *
 * A decision lasts for the current run only, so nothing stays granted behind the
 * user's back after a restart.
 */
object TavernAccessGate {

    private const val ASK_TIMEOUT_SECONDS = 60L
    private const val ANONYMOUS_LOG_LIMIT = 10

    private val loggedHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val loggedPaths: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val lock = Any()
    private val allowed = mutableSetOf<String>()
    private val refused = mutableSetOf<String>()

    /** Called when the tavern page is loaded: a freshly loaded page has not been vetted. */
    fun reset() {
        synchronized(lock) {
            allowed.clear()
            refused.clear()
        }
    }

    private fun isAllowed(host: String): Boolean = synchronized(lock) { host in allowed }

    /**
     * Decide one intercepted request. Returns null to let it through, or the
     * response to hand back instead. Runs on the WebView's interception thread, so
     * the dialog is posted to the main thread and this thread waits for the answer.
     */
    fun intercept(
        context: Context,
        targetUrl: String?,
        method: String?,
        headers: Map<String, String>,
    ): WebResourceResponse? {
        val verdict = TavernAccessPolicy.verdict(targetUrl, method, headers, ::isAllowed)
        if (verdict is TavernAccessPolicy.Verdict.Allow) {
            noteAnonymous(targetUrl, method, headers)
            return null
        }
        val host = (verdict as? TavernAccessPolicy.Verdict.Ask)?.host ?: return null
        noteForeign(host.ifEmpty { TavernAccessPolicy.requesterHost(headers) ?: "未知" }, method, targetUrl)

        // One dialog at a time: a page usually fires several calls at once, and the
        // first answer settles the rest without asking again.
        synchronized(lock) {
            if (host in allowed) return null
            if (host in refused) return deniedResponse()
        }

        val request = TavernAccessRequest(
            sourceUrl = TavernAccessPolicy.requesterUrl(headers),
            targetUrl = targetUrl.orEmpty(),
            method = method?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "GET",
        )
        val decision = ask(context, request)
        synchronized(lock) {
            if (decision) {
                allowed += host
                return null
            }
            refused += host
            return deniedResponse()
        }
    }

    /**
     * Put the request to the user. The dialog belongs to the View layer because the
     * tavern is a native WebView, so it is built on the main thread while this
     * thread waits for the answer.
     */
    private fun ask(context: Context, request: TavernAccessRequest): Boolean {
        val activity = context.findActivity() ?: return false
        val latch = CountDownLatch(1)
        val answer = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post {
            try {
                TavernAccessDialog.show(activity, request) { allowed ->
                    answer.set(allowed)
                    latch.countDown()
                }
            } catch (e: Exception) {
                android.util.Log.w(
                    "TavernAccessGate",
                    "Could not ask about ${request.sourceUrl ?: request.targetUrl}: ${e.message}"
                )
                latch.countDown()
            }
        }
        return try {
            // No answer in time is not consent, so it stays refused.
            latch.await(ASK_TIMEOUT_SECONDS, TimeUnit.SECONDS) && answer.get()
        } catch (_: InterruptedException) {
            false
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    /**
     * A request from another site is the interesting case and worth one line per
     * site: a workshop that stays broken with none of these lines never reached the
     * interceptor, which is a different fault from the user not being asked.
     */
    private fun noteForeign(host: String, method: String?, targetUrl: String?) {
        if (!loggedHosts.add(host)) return
        TavernLog.webview(
            "外部站点请求本地核心：来源=$host 方法=${method ?: "?"} ${targetUrl?.take(120) ?: ""}"
        )
    }

    /**
     * A request to the tavern whose origin could not be read is let through and
     * would otherwise leave no trace at all. Only data paths are noted: the tavern
     * loads its own page and stylesheets anonymously too, and those would fill the
     * cap before anything interesting arrives. One line per path.
     */
    private fun noteAnonymous(targetUrl: String?, method: String?, headers: Map<String, String>) {
        if (!TavernAccessPolicy.isTavernHost(TavernAccessPolicy.hostOf(targetUrl))) return
        if (TavernAccessPolicy.requesterHost(headers) != null) return
        val verb = method?.uppercase() ?: "GET"
        val path = try { java.net.URI(targetUrl ?: "").path ?: "" } catch (_: Exception) { "" }
        if ((verb == "GET" || verb == "HEAD") && !path.startsWith("/api/")) return
        if (loggedPaths.size >= ANONYMOUS_LOG_LIMIT) return
        if (!loggedPaths.add("$verb $path")) return
        TavernLog.webview("本地核心请求读不到来源：方法=$verb $path")
    }

    /** A refused request still has to look like an answer, not like a dead network. */
    private fun deniedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Forbidden",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream(ByteArray(0)),
    )
}
