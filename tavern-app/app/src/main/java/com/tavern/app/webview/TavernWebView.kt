package com.tavern.app.webview

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream

class TavernWebView(context: Context) : WebView(context) {

    private var onPageLoaded: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    @Volatile private var cssInjected = false

    /** Called when the WebView needs to show a file chooser. Activity should call back with results. */
    var onFileChooserRequested: ((android.webkit.ValueCallback<Array<Uri>>, Intent) -> Unit)? = null

    @Volatile private var isPaused = false

    init { configure() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        setBackgroundColor(0xFF0a0a12.toInt())
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        // Enable WebView debugging so injected polyfill console.logs reach
        // logcat (debug builds; harmless in release when stripped by proguard).
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            loadsImagesAutomatically = true
            blockNetworkImage = false
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }

        addJavascriptInterface(WebViewBridge(context), "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                cssInjected = false
            }

            // Inject compat polyfill into HTML before any scripts load
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: return null

                if (!com.tavern.app.console.SettingsState.compatModeEnabled()) return null

                // Intercept main page (polyfill) or CSS files (color fix)
                val isMainPage = urlStr.matches(Regex("http://127\\.0\\.0\\.1:\\d+/?$"))
                val isCSS = urlStr.endsWith(".css") || urlStr.contains(".css?")

                if (isMainPage) {
                    try {
                        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                        request.requestHeaders?.forEach { (k, v) ->
                            if (!k.equals("Accept-Encoding", ignoreCase = true))
                                conn.setRequestProperty(k, v)
                        }
                        conn.setRequestProperty("Accept-Encoding", "identity")
                        conn.connect()
                        val encoding = conn.contentEncoding
                        val raw: String = if (encoding != null && encoding.equals("gzip", ignoreCase = true)) {
                            java.util.zip.GZIPInputStream(conn.inputStream).bufferedReader().readText()
                        } else {
                            conn.inputStream.bufferedReader().readText()
                        }
                        conn.disconnect()
                        val polyfill = "<script>window.CSSMediaRule=window.CSSStyleRule=window.CSSKeyframesRule=window.CSSSupportsRule=window.CSSImportRule=window.CSSContainerRule=function(){};" +
                            "var _st_origFetch=window.fetch;" +
                            "window.fetch=function(u,o){var s=String(u);if(s.includes('jsdelivr.net/npm/vue')){console.log('[st-ctrl] Redirecting CDN fetch');return _st_origFetch('/vue-runtime.js',o);}return _st_origFetch(u,o);};" +
                            "</script>"
                        var fixed = raw.replaceFirst("<head>", "<head>$polyfill")
                                       .replaceFirst("<HEAD>", "<HEAD>$polyfill")
                        if (fixed == raw) {
                            fixed = raw.replaceFirst("<html>", "<html><head>$polyfill</head>")
                                      .replaceFirst("<HTML>", "<HTML><head>$polyfill</head>")
                        }
                        return WebResourceResponse("text/html", "UTF-8", fixed.byteInputStream(Charsets.UTF_8))
                    } catch (e: Exception) {
                        android.util.Log.w("TavernWebView", "Polyfill inject failed: ${e.message}")
                        return null
                    }
                }

                if (isCSS) {
                    try {
                        val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                        request.requestHeaders?.forEach { (k, v) ->
                            if (!k.equals("Accept-Encoding", ignoreCase = true))
                                conn.setRequestProperty(k, v)
                        }
                        conn.setRequestProperty("Accept-Encoding", "identity")
                        conn.connect()
                        val encoding = conn.contentEncoding
                        val raw: String = if (encoding != null && encoding.equals("gzip", ignoreCase = true)) {
                            java.util.zip.GZIPInputStream(conn.inputStream).bufferedReader().readText()
                        } else {
                            conn.inputStream.bufferedReader().readText()
                        }
                        conn.disconnect()
                        val fixed = fixCompatCSS(raw)
                        return WebResourceResponse("text/css", "UTF-8", fixed.byteInputStream(Charsets.UTF_8))
                    } catch (e: Exception) {
                        android.util.Log.w("TavernWebView", "CSS fix failed: ${e.message}")
                        return null
                    }
                }

                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (url?.startsWith("http://127.0.0.1") == true) {
                    injectCSS()
                    injectTimerThrottle()
                    onPageLoaded?.invoke()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {

                return false
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only show for main frame errors; request can be null on older WebViews
                if (request != null && request.isForMainFrame) {
                    onError?.invoke(error?.description?.toString() ?: "加载失败")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Only show error for main page, not sub-resources
                if (request?.isForMainFrame == true) {
                    onError?.invoke("HTTP ${errorResponse?.statusCode}")
                }
            }
        }

        // File picker support for importing character cards, presets, etc.
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                android.util.Log.w("TavernWebView", "onShowFileChooser called, handler=${onFileChooserRequested != null}")
                // createIntent() returns ACTION_OPEN_DOCUMENT which depends on
                // DocumentsUI — may be missing on emulators / custom ROMs.
                // ACTION_GET_CONTENT is universally supported and works the same
                // for WebView's one-shot read of the picked file(s).
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // Always serve */* so the OS shows ALL file types.
                    // Old WebViews may send a narrow MIME like "image/png"
                    // for character card imports, blocking .json/.webp files.
                    // The tavern page itself handles filtering server-side.
                    type = "*/*"
                }
                onFileChooserRequested?.invoke(filePathCallback, intent)
                return true
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                if (msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.LOG ||
                    msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.DEBUG ||
                    msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.TIP) {
                    return true
                }
                val level = when (msg.messageLevel()) {
                    android.webkit.ConsoleMessage.MessageLevel.ERROR -> "❌"
                    else -> "⚠️"
                }
                val src = msg.sourceId()?.takeLast(30) ?: ""
                val text = msg.message().take(200)
                com.tavern.app.log.TavernLog.webview("$level [$src] $text")
                return true
            }
        }

        // Download listener — handles file exports from the tavern page.
        // Android WebView does NOT handle downloads by default; without this
        // any export (character card, chat log, theme, etc.) is silently dropped.
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    // ────────────────────────────────────────────────────────────
    // Download / Export handling
    // ────────────────────────────────────────────────────────────

    companion object {
        /**
         * Static helper for [WebViewBridge.onExportData] to save blob data.
         * Exposed as @JvmStatic so the bridge can call it without a WebView reference.
         */
        @JvmStatic
        fun handleBlobExport(dataUrl: String, suggestedFilename: String, ctx: Context): Boolean {
            return try {
                if (dataUrl.startsWith("blob:")) return false // signal only
                if (!dataUrl.startsWith("data:") || ',' !in dataUrl) return false

                val commaIdx = dataUrl.indexOf(',')
                val header = dataUrl.substring(0, commaIdx)
                val isBase64 = header.contains(";base64")
                val mime = header.removePrefix("data:").substringBefore(';')
                val ext = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mime) ?: mime.substringAfterLast('/')

                val exportsDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "TavernExports"
                )
                if (!exportsDir.exists()) exportsDir.mkdirs()

                val bytes = if (isBase64) {
                    Base64.decode(dataUrl.substring(commaIdx + 1), Base64.DEFAULT)
                } else {
                    java.net.URLDecoder.decode(dataUrl.substring(commaIdx + 1), "UTF-8").toByteArray()
                }

                var filename = suggestedFilename.ifBlank { "export" }
                if (!filename.contains('.')) filename += ".$ext"
                var dest = File(exportsDir, filename)
                var n = 1
                while (dest.exists()) {
                    dest = File(exportsDir,
                        "${filename.substringBeforeLast('.')}_($n).${filename.substringAfterLast('.')}")
                    n++
                }
                dest.writeBytes(bytes)

                Handler(Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(ctx,
                        "已导出: Downloads/TavernExports/${dest.name}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            } catch (e: Exception) {
                android.util.Log.w("TavernWebView", "Blob export failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Handle download triggered by the WebView (e.g. SillyTavern exports).
     *
     * Three patterns:
     * 1. Blob URL — JS blob hook sends data via bridge (handled in WebViewBridge).
     * 2. Data URL — decode and save directly.
     * 3. HTTP `Content-Disposition: attachment` — system DownloadManager.
     */
    private fun handleDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        android.util.Log.i("TavernWebView", "Download: url=${url.take(120)}, mime=$mimeType")

        if (url.startsWith("data:")) {
            handleBlobExport(url, "tavern-export", context)
            return
        }

        if (url.startsWith("blob:")) {
            // Blob URL reached here because the JS hook didn't handle it.
            // Inject a sync read of the stashed blob (kept alive 30s by the JS hook).
            val extractJs = """
                (function(){
                    try {
                        var b = window.__st_readBlob && window.__st_readBlob('$url');
                        if (!b) { window.AndroidBridge.onExportData('', '', '文件已过期，请重新导出'); return; }
                        var r = new FileReader();
                        r.onloadend = function() { window.AndroidBridge.onExportData(r.result, '', ''); };
                        r.onerror = function() { window.AndroidBridge.onExportData('', '', '读取失败'); };
                        r.readAsDataURL(b);
                    } catch(e) { window.AndroidBridge.onExportData('', '', e.message); }
                })();
            """.trimIndent()
            post { evaluateJavascript(extractJs, null) }
            return
        }

        // ── HTTP download via DownloadManager ──
        try {
            var filename = "tavern-export"
            if (!contentDisposition.isNullOrBlank()) {
                val m = Regex("""filename\*?=(?:UTF-8''|\"?)([^";]+)""").find(contentDisposition)
                if (m != null) filename = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")
            } else {
                val s = Uri.parse(url).lastPathSegment
                if (!s.isNullOrBlank()) filename = java.net.URLDecoder.decode(s, "UTF-8")
            }

            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "TavernExports/$filename")
                setTitle(filename)
                setDescription("ST-Ctrl 导出")
                userAgent?.let { addRequestHeader("User-Agent", it) }
                val ck = android.webkit.CookieManager.getInstance().getCookie(url)
                if (!ck.isNullOrBlank()) addRequestHeader("Cookie", ck)
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            try {
                dm.enqueue(req)
            } catch (e: SecurityException) {
                downloadViaDirectHttp(url, userAgent, filename)
            }

            android.widget.Toast.makeText(context,
                "正在导出到 Downloads/TavernExports/$filename",
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.w("TavernWebView", "Download failed: ${e.message}")
            android.widget.Toast.makeText(context,
                "导出失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Fallback HTTP download when DownloadManager is blocked. */
    private fun downloadViaDirectHttp(url: String, userAgent: String?, filename: String) {
        val thread = Thread {
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "TavernExports")
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, filename)
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", userAgent ?: "TavernApp")
                val ck = android.webkit.CookieManager.getInstance().getCookie(url)
                if (!ck.isNullOrBlank()) conn.setRequestProperty("Cookie", ck)
                conn.connect()
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { i -> java.io.FileOutputStream(dest).use { o -> i.copyTo(o) } }
                    Handler(Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context,
                            "已导出: Downloads/TavernExports/$filename",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                android.util.Log.w("TavernWebView", "Direct download failed: ${e.message}")
            }
        }
        thread.isDaemon = true
        thread.start()
    }

    // ────────────────────────────────────────────────────────────
    // CSS / JS injection
    // ────────────────────────────────────────────────────────────

    private fun injectCSS() {
        if (cssInjected) return
        cssInjected = true
        try {
            val js = context.assets.open("tavern-mobile-inject.js")
                .bufferedReader().use { it.readText() }
            evaluateJavascript(js, null)
        } catch (e: Exception) {
            android.util.Log.w("TavernWebView", "Failed to inject mobile JS: ${e.message}")
        }
    }

    fun setOnPageLoaded(callback: () -> Unit) { onPageLoaded = callback }
    fun setOnError(callback: (String) -> Unit) { onError = callback }

    fun loadTavern(port: Int) {
        // Guard: don't attempt to load if WebView has already been destroyed
        try {

            cssInjected = false
            // Set compat cookie so server can transform CSS
            var c = if (com.tavern.app.console.SettingsState.compatModeEnabled()) "1" else "0"
            android.webkit.CookieManager.getInstance().setCookie("http://127.0.0.1:$port", "st_compat=$c; Path=/")
            android.webkit.CookieManager.getInstance().flush()
            // Clear cache in compat mode to ensure CSS interception works
            if (com.tavern.app.console.SettingsState.compatModeEnabled()) {
                clearCache(true)
            }
            loadUrl("http://127.0.0.1:$port")
        } catch (e: IllegalStateException) {
            android.util.Log.w("TavernWebView", "loadTavern called after destroy, ignoring")
        }
    }


    private var pendingTimerThrottle = false

    /** Apply timer throttle based on optimization tier. */
    fun applyTimerThrottle(enabled: Boolean) {
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        pendingTimerThrottle = enabled
    }

    private fun injectTimerThrottle() {
        if (!pendingTimerThrottle) return
        pendingTimerThrottle = false
        evaluateJavascript("""
            (function(){
                if (window.__stctrl_throttled) return;
                window.__stctrl_throttled = true;
                var origSI = window.setInterval;
                // Only throttle setInterval calls with interval >= 500ms — these are
                // polling loops (character list refresh, status checks, etc.), not UI
                // interactions. setTimeout is left untouched to keep UI responsive.
                var factor = 2;
                window.setInterval = function(fn, ms) {
                    if (ms && ms >= 500) ms = ms * factor;
                    return origSI.call(window, fn, ms);
                };
            })();
        """.trimIndent(), null)
    }

    /** Pause WebView rendering and JS timers when user leaves the tavern. */
    fun pauseRendering() {
        if (isPaused) return
        isPaused = true
        onPause()
        try {
            evaluateJavascript("""
            (function(){
                if (window.__tavernTimersPaused) return;
                window.__tavernTimersPaused = true;
                var ids = window.__tavernAnimIds || [];
                for (var i = 0; i < ids.length; i++) {
                    if (ids[i]) cancelAnimationFrame(ids[i]);
                }
                window.__tavernAnimIds = [];
            })();
        """.trimIndent(), null)
        } catch (_: Exception) { /* WebView may be destroyed */ }

        // Release renderer memory when in background for > 30s
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isPaused) {
                try {
                    clearCache(true)
                    evaluateJavascript("if(window.gc)window.gc();", null)
                } catch (_: Exception) {}
            }
        }, 30_000)
    }

    /** Resume WebView rendering when user returns to the tavern. */
    fun resumeRendering() {
        if (!isPaused) return
        isPaused = false
        onResume()
        evaluateJavascript("""
            (function(){
                window.__tavernTimersPaused = false;
            })();
        """.trimIndent(), null)
    }

    /** Replace unsupported CSS features for old WebViews. */
    private fun fixCompatCSS(css: String): String {
        var fixed = css
        // Properly strip @layer blocks — count braces, no orphaned }
        var sb = StringBuilder()
        var i = 0
        while (i < fixed.length) {
            val idx = fixed.indexOf("@layer ", i)
            if (idx < 0) { sb.append(fixed, i, fixed.length); break }
            val bracePos = fixed.indexOf('{', idx)
            if (bracePos < 0 || bracePos - idx > 100) { sb.append(fixed, i, idx + 1); i = idx + 1; continue }
            sb.append(fixed, i, idx)
            var depth = 1; var j = bracePos + 1
            while (j < fixed.length && depth > 0) { if (fixed[j] == '{') depth++ else if (fixed[j] == '}') depth--; j++ }
            sb.append(fixed, bracePos + 1, j - 1) // inner content, skip closing }
            i = j
        }
        fixed = sb.toString()

        // Replace :root,:host → :root. Remove ::backdrop and :where()
        fixed = fixed.replace(":root,:host", ":root")
        fixed = fixed.replace(Regex("""::backdrop\s*,\s*"""), "")
        fixed = fixed.replace(Regex(""",\s*::backdrop"""), "")
        fixed = fixed.replace(Regex(""":where\(([^)]*)\)"""), "$1")
        // backdrop-filter: kept as-is — Chrome 76+ supports it. The injected
        // client polyfill handles the var()/calc() quirks at runtime.
        // oklch() → rgb() via helper
        fixed = fixed.replace(Regex("""oklch\(([^)]+)\)""")) { m ->
            val args = m.groupValues[1].split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (args.size < 3) return@replace "transparent"
            val L = args[0].replace("%","").toDoubleOrNull()?.let { if (args[0].contains("%")) it/100.0 else it } ?: return@replace "transparent"
            val C = args[1].toDoubleOrNull() ?: 0.0
            val H = args[2].toDoubleOrNull() ?: 0.0
            val alpha = if (args.size > 3) args[3].replace("/","").toDoubleOrNull() ?: 1.0 else 1.0
            oklchToRgb(L, C, H, alpha) ?: "transparent"
        }
        // rgb(from var(--X) r g b /A) → var(--X)
        fixed = fixed.replace(Regex("""rgb\(from\s+(var\(--[\w-]+\))\s+r\s+g\s+b\s*/?\s*[\d.]*\)""", RegexOption.IGNORE_CASE), "$1")
        // color-mix() — real sRGB weighted mixing where both colors are concrete; best-guess fallback for var() references
        var pass = 0
        fun parseColorToRgba(c: String): IntArray? {
            val trimmed = c.trim()
            val mRgb = Regex("""rgba?\((\d+)[,\s]+(\d+)[,\s]+(\d+)(?:[,\s/]+([\d.]+))?\)""", RegexOption.IGNORE_CASE).find(trimmed)
            if (mRgb != null) {
                val r = mRgb.groupValues[1].toIntOrNull() ?: return null
                val g = mRgb.groupValues[2].toIntOrNull() ?: return null
                val b = mRgb.groupValues[3].toIntOrNull() ?: return null
                val a = (mRgb.groupValues.getOrNull(4)?.toDoubleOrNull() ?: 1.0).coerceIn(0.0, 1.0)
                return intArrayOf(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), (a * 255).toInt().coerceIn(0, 255))
            }
            val mHex = Regex("""^#([0-9a-fA-F]{3,8})$""").find(trimmed)
            if (mHex != null) {
                val h = mHex.groupValues[1]
                return when (h.length) {
                    3 -> intArrayOf(h[0].toString().repeat(2).toInt(16), h[1].toString().repeat(2).toInt(16), h[2].toString().repeat(2).toInt(16), 255)
                    6 -> intArrayOf(h.substring(0,2).toInt(16), h.substring(2,4).toInt(16), h.substring(4,6).toInt(16), 255)
                    else -> null
                }
            }
            if (trimmed.equals("transparent", ignoreCase = true)) return intArrayOf(0, 0, 0, 0)
            return null
        }
        fun mixRgba(c1: IntArray?, c2: IntArray?, p1: Double, p2: Double): String? {
            if (c1 == null || c2 == null) return null
            val total = p1 + p2
            if (total <= 0.0) return null
            val a1 = c1[3] / 255.0; val a2 = c2[3] / 255.0
            val mixedA = (a1 * p1 + a2 * p2) / total
            if (mixedA == 0.0) return "transparent"
            val f1 = a1 * p1 / total / mixedA; val f2 = a2 * p2 / total / mixedA
            val r = (c1[0] * f1 + c2[0] * f2).toInt().coerceIn(0, 255)
            val g = (c1[1] * f1 + c2[1] * f2).toInt().coerceIn(0, 255)
            val b = (c1[2] * f1 + c2[2] * f2).toInt().coerceIn(0, 255)
            return if (mixedA >= 1.0) "rgb($r,$g,$b)" else "rgba($r,$g,$b,${String.format("%.3f", mixedA).trimEnd('0').trimEnd('.')})"
        }
        fun isConcrete(c: String) = !c.contains("var(") && !c.contains("currentColor", ignoreCase = true)
        while (fixed.contains("color-mix(") && pass < 10) {
            pass++
            val sb = StringBuilder()
            var i = 0
            while (i < fixed.length) {
                val idx = fixed.indexOf("color-mix(", i)
                if (idx < 0) { sb.append(fixed, i, fixed.length); break }
                sb.append(fixed, i, idx)
                var depth = 1; var j = idx + 10
                while (j < fixed.length && depth > 0) { if (fixed[j] == '(') depth++ else if (fixed[j] == ')') depth--; j++ }
                val body = fixed.substring(idx + 10, j - 1)
                val parts = mutableListOf<String>(); var d = 0; var s = 0
                for (k in body.indices) {
                    when { body[k] == '(' -> d++; body[k] == ')' -> d--; body[k] == ',' && d == 0 -> { parts.add(body.substring(s, k).trim()); s = k + 1 } }
                }
                parts.add(body.substring(s).trim())
                if (parts.size < 3) { sb.append("transparent"); i = j; continue }
                fun pp(s: String): Pair<String,Double> {
                    val m = Regex("""^(.+?)\s+(\d+(?:\.\d+)?)\s*%$""").find(s.trim())
                    return if (m != null) Pair(m.groupValues[1].trim(), m.groupValues[2].toDouble()) else Pair(s.trim(), -1.0)
                }
                val (c1,p1) = pp(parts[1]); val (c2,p2r) = pp(parts[2])
                val p2 = if (p2r < 0) Math.max(0.0, 100.0 - p1) else p2r
                if (isConcrete(c1) && isConcrete(c2)) {
                    val mix = mixRgba(parseColorToRgba(c1), parseColorToRgba(c2), p1, p2)
                    if (mix != null) { sb.append(mix); i = j; continue }
                }
                val best = if (p2 > p1 && !c2.equals("transparent", ignoreCase = true)) c2 else c1
                sb.append(best.ifEmpty { "transparent" })
                i = j
            }
            fixed = sb.toString()
        }
        return fixed
    }

    private fun oklchToRgb(L: Double, C: Double, H: Double, alpha: Double): String? {
        val hRad = H * Math.PI / 180.0
        val a = C * Math.cos(hRad)
        val b = C * Math.sin(hRad)
        val l_ = L + 0.3963377774 * a + 0.2158037573 * b
        val m_ = L - 0.1055613458 * a - 0.0638541728 * b
        val s_ = L - 0.0894841775 * a - 1.2914855480 * b
        val l3 = l_ * l_ * l_; val m3 = m_ * m_ * m_; val s3 = s_ * s_ * s_
        val rL = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3
        val gL = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3
        val bL = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3
        fun gam(c: Double) = if (c <= 0.0031308) 12.92 * c else 1.055 * Math.pow(c, 1/2.4) - 0.055
        val r = Math.round(Math.max(0.0, Math.min(1.0, gam(rL))) * 255).toInt()
        val g = Math.round(Math.max(0.0, Math.min(1.0, gam(gL))) * 255).toInt()
        val bv = Math.round(Math.max(0.0, Math.min(1.0, gam(bL))) * 255).toInt()
        if (alpha.isNaN() || alpha >= 1.0) return "rgb($r,$g,$bv)"
        return "rgba($r,$g,$bv,${alpha.toString().let { it.trimEnd('0').trimEnd('.') }})"
    }
}
