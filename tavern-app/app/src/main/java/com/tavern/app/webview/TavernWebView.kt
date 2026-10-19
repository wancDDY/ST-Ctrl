package com.tavern.app.webview

import com.tavern.app.console.theme.FluentColors
import com.tavern.app.util.AppToast
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
import androidx.compose.ui.graphics.toArgb
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class TavernWebView(context: Context) : WebView(context) {

    private var onPageLoaded: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    /** Address of the last main-frame load that failed, for the recovery path. */
    var lastFailedUrl: String? = null
        private set
    @Volatile private var cssInjected = false
    @Volatile private var optimizationsInjected = false
    @Volatile private var requestCompatMode = false
    private var lastLoadCompatMode: Boolean? = null
    private var loadGeneration = 0

    /** Port of the last page this view was asked to load. */
    private var lastRequestedPort = 0

    /** Set when a load moves to another port; the old entry is dropped once it loads. */
    private var clearHistoryWhenLoaded = false

    // Set while this WebView navigates itself (a console-driven reload, a compat
    // change). The tavern page asks for confirmation on unload, so a reload the user
    // asked for by tapping the refresh button or by changing a setting used to stop on
    // that dialog.
    @Volatile private var suppressNextUnloadConfirm = false

    /** Called when the WebView needs to show a file chooser. Activity should call back with results. */
    var onFileChooserRequested: ((android.webkit.ValueCallback<Array<Uri>>, Intent) -> Unit)? = null

    @Volatile private var isPaused = false

    init { configure() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // Pre-paint colour shown before the page renders. The WebView is a
        // plain View, so it cannot read the Compose theme; a neutral dark plate
        // avoids a bright flash and matches the window behind it.
        setBackgroundColor(FluentColors.DarkSurface.toArgb())
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
            // Always allow. Strict mode looks like it should be enough — loopback is a
            // trustworthy origin, so a workshop page on https calling http://127.0.0.1
            // is not mixed content by the spec — but the WebView blocks the request
            // before the interceptor anyway: on a real device nothing arrived, so no
            // decision could be put to the user. Letting the request through is what
            // gives TavernAccessGate something to decide about.
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            // Marks this WebView for the app runtime, which records tavern entries made
            // from anywhere else (a browser on the LAN) so the console can say where
            // the tavern was opened from.
            userAgentString = "$userAgentString STCtrlApp"
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }

        addJavascriptInterface(WebViewBridge(context), "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)

                cssInjected = false
                optimizationsInjected = false
            }

            // Inject compat polyfill into HTML before any scripts load
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val urlStr = request?.url?.toString() ?: return null

                // Another site reaching the tavern is a decision for the user, and it
                // has to be made before the compat check below: that check is about
                // what the page receives, not about who is allowed to ask.
                TavernAccessGate.intercept(
                    context,
                    urlStr,
                    request?.method,
                    request?.requestHeaders ?: emptyMap(),
                )?.let { return it }

                // The main page gets its missing standard APIs even with compat mode off. Filling a
                // gap is not a rewrite: every entry below checks for the native one first, so a
                // WebView that already has them runs the page unchanged. Gating this on compat mode
                // meant any WebView older than AbortSignal.throwIfAborted lost generation
                // altogether, and the error surfaced nowhere near the switch that caused it. What
                // does change the page — the CSSOM stubs, the CDN redirect and the stylesheet
                // rewrite — stays behind the switch, which must keep the app invisible when off.
                val isMainPage = urlStr.matches(Regex("http://127\\.0\\.0\\.1:\\d+/?$"))

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
                        // Everything here only defines what the WebView is missing, so it is served
                        // regardless of compat mode.
                        val polyfill = "<script>" +
                            "var _st_at=function(n){n=Math.trunc(n)||0;if(n<0)n+=this.length;return n<0||n>=this.length?undefined:this[n];};if(!Array.prototype.at){Object.defineProperty(Array.prototype,'at',{value:_st_at,writable:true,configurable:true});}" +
                            "if(!String.prototype.at){Object.defineProperty(String.prototype,'at',{value:function(n){return _st_at.call(this,n);},writable:true,configurable:true});}" +
                            "if(typeof Uint8Array!=='undefined'&&!Uint8Array.prototype.at){[Uint8Array,Uint16Array,Uint32Array,Int8Array,Int16Array,Int32Array,Float32Array,Float64Array].forEach(function(C){if(C&&!C.prototype.at){Object.defineProperty(C.prototype,'at',{value:_st_at,writable:true,configurable:true});}});}" +
                            "if(!Object.hasOwn){Object.hasOwn=function(o,k){return Object.prototype.hasOwnProperty.call(o,k);};}" +
                            "if(!Array.prototype.findLast){Object.defineProperty(Array.prototype,'findLast',{value:function(f,t){for(var i=this.length-1;i>=0;i--){if(f.call(t,this[i],i,this))return this[i];}},writable:true,configurable:true});}" +
                            "if(!Array.prototype.findLastIndex){Object.defineProperty(Array.prototype,'findLastIndex',{value:function(f,t){for(var i=this.length-1;i>=0;i--){if(f.call(t,this[i],i,this))return i;}return -1;},writable:true,configurable:true});}" +
                            "if(!String.prototype.replaceAll){Object.defineProperty(String.prototype,'replaceAll',{value:function(q,r){var k=String(q);if(typeof r!=='function'){return this.split(k).join(r);}var parts=this.split(k);var out=parts[0];for(var i=1;i<parts.length;i++){out+=String(r(k,i-1,this))+parts[i];}return out;},writable:true,configurable:true});}" +
                            "if(!window.structuredClone){window.structuredClone=function(v){if(v===null||typeof v!=='object')return v;if(v instanceof Date)return new Date(v.getTime());if(v instanceof RegExp)return new RegExp(v.source,v.flags);if(v instanceof Map){var m=new Map();v.forEach(function(val,key){m.set(window.structuredClone(key),window.structuredClone(val));});return m;}if(v instanceof Set){var st=new Set();v.forEach(function(val){st.add(window.structuredClone(val));});return st;}if(Array.isArray(v))return v.map(window.structuredClone);var o={};for(var k in v){if(Object.prototype.hasOwnProperty.call(v,k))o[k]=window.structuredClone(v[k]);}return o;};}" +
                            "if(!Promise.any){Promise.any=function(list){return new Promise(function(res,rej){var errs=[],left=list.length;if(!left)return rej(new Error('no promises'));list.forEach(function(pr,i){Promise.resolve(pr).then(res,function(e){errs[i]=e;if(--left===0)rej(errs[0] instanceof Error?errs[0]:new Error('no promise resolved'));});});});};}" +
                            "if(window.crypto&&!window.crypto.randomUUID){try{Object.defineProperty(window.crypto,'randomUUID',{value:function(){var b=new Uint8Array(16);window.crypto.getRandomValues(b);b[6]=b[6]&15|64;b[8]=b[8]&63|128;var h='';for(var i=0;i<16;i++){h+=(b[i]+256).toString(16).slice(1);}return h.slice(0,8)+'-'+h.slice(8,12)+'-'+h.slice(12,16)+'-'+h.slice(16,20)+'-'+h.slice(20);},configurable:true});}catch(e){}}" +
                            "if(typeof AbortSignal!=='undefined'){" +
                            "if(!AbortSignal.prototype.throwIfAborted){Object.defineProperty(AbortSignal.prototype,'throwIfAborted',{value:function(){if(this.aborted){throw this.reason!==undefined?this.reason:new DOMException('The operation was aborted.','AbortError');}},writable:true,configurable:true});}" +
                            "if(!('reason' in AbortSignal.prototype)){Object.defineProperty(AbortSignal.prototype,'reason',{get:function(){return this.aborted?new DOMException('The operation was aborted.','AbortError'):undefined;},configurable:true});}" +
                            "if(!AbortSignal.timeout){AbortSignal.timeout=function(ms){var c=new AbortController();setTimeout(function(){c.abort(new DOMException('TimeoutError','TimeoutError'));},ms);return c.signal;};}" +
                            "if(!AbortSignal.abort){AbortSignal.abort=function(r){var c=new AbortController();if(r!==undefined){c.abort(r);}else{c.abort();}return c.signal;};}" +
                            "}" +
                            "if(!Object.groupBy){Object.groupBy=function(items,cb){var o=Object.create(null);var i=0;for(var it of items){var k=cb(it,i++);if(!o[k]){o[k]=[];}o[k].push(it);}return o;};}" +
                            "if(!Map.groupBy){Map.groupBy=function(items,cb){var m=new Map();var i=0;for(var it of items){var k=cb(it,i++);var g=m.get(k);if(g){g.push(it);}else{m.set(k,[it]);}}return m;};}" +
                            "if(typeof URL!=='undefined'&&!URL.canParse){URL.canParse=function(u,b){try{new URL(u,b);return true;}catch(e){return false;}};}" +
                            "if(!Array.fromAsync){Array.fromAsync=async function(items,mapFn,thisArg){var out=[];var i=0;for await(var v of items){out.push(mapFn?await mapFn.call(thisArg,v,i++):v);}return out;};}" +
                            "if(typeof Element!=='undefined'&&!Element.prototype.checkVisibility){Element.prototype.checkVisibility=function(){var el=this;while(el&&el.nodeType===1){var s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'){return false;}el=el.parentElement;}return true;};}" +
                            "</script>"
                        // These do change the page, so they keep following the switch.
                        val compatPatch = if (requestCompatMode) {
                            "<script>" +
                            "window.CSSMediaRule=window.CSSStyleRule=window.CSSKeyframesRule=window.CSSSupportsRule=window.CSSImportRule=window.CSSContainerRule=function(){};" +
                            "var _st_origFetch=window.fetch;" +
                            "window.fetch=function(u,o){var s=String(u);if(s.includes('jsdelivr.net/npm/vue')){console.log('[st-ctrl] Redirecting CDN fetch');return _st_origFetch('/vue-runtime.js',o);}return _st_origFetch(u,o);};" +
                            "</script>"
                        } else {
                            ""
                        }
                        val injection = polyfill + compatPatch
                        var fixed = raw.replaceFirst("<head>", "<head>$injection")
                                       .replaceFirst("<HEAD>", "<HEAD>$injection")
                        if (fixed == raw) {
                            fixed = raw.replaceFirst("<html>", "<html><head>$injection</head>")
                                      .replaceFirst("<HTML>", "<HTML><head>$injection</head>")
                        }
                        return WebResourceResponse("text/html", "UTF-8", fixed.byteInputStream(Charsets.UTF_8))
                    } catch (e: Exception) {
                        android.util.Log.w("TavernWebView", "Polyfill inject failed: ${e.message}")
                        return null
                    }
                }

                if (!requestCompatMode) return null

                val isCSS = urlStr.endsWith(".css") || urlStr.contains(".css?")
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

                // The reload this WebView started on its own is over. Cleared here
                // rather than when the dialog was answered, so a page that never asks
                // cannot leave the flag set for the next legitimate confirm.
                suppressNextUnloadConfirm = false

                if (url?.startsWith("http://127.0.0.1") == true) {
                    // Clearing the history when the load is *requested* does not remove the
                    // page still on screen: it only drops the back/forward list, so the old
                    // origin stays as the current entry and the new page is appended after
                    // it — back then walks straight into the port the service has left.
                    // Doing it once the new page is the current one removes that entry for
                    // real.
                    if (clearHistoryWhenLoaded) {
                        clearHistoryWhenLoaded = false
                        clearHistory()
                        com.tavern.app.log.TavernLog.page("history cleared after loading $url")
                    }
                    com.tavern.app.log.TavernLog.page("主页加载完成 $url")
                    injectCSS()
                    injectOptimizations()
                    injectTimerThrottle()
                    applyPerformanceProfile()
                    onPageLoaded?.invoke()
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only show for main frame errors; request can be null on older WebViews
                if (request != null && request.isForMainFrame) {
                    val description = error?.description?.toString() ?: "加载失败"
                    // Which address failed decides whether this is a page pointed at the
                    // wrong port or a service that is really down; the message alone
                    // cannot tell them apart.
                    lastFailedUrl = request.url?.toString()
                    // The toast that carries this message is gone in seconds and the
                    // user is left with a page that never opened and no reason for it.
                    com.tavern.app.log.TavernLog.page("主页加载失败：$description（${request.url}）")
                    onError?.invoke(description)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?, request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Only show error for main page, not sub-resources
                if (request?.isForMainFrame == true) {
                    val status = errorResponse?.statusCode
                    com.tavern.app.log.TavernLog.page("主页返回 HTTP $status（${request.url}）")
                    onError?.invoke("HTTP $status")
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

            // The tavern page warns on unload, which turns every console-driven reload
            // into a question the user already answered by tapping the refresh button or
            // by changing a setting. Answering it here is scoped to navigations this
            // WebView started: a confirm the page raises on its own still reaches the
            // user, because the flag is only armed by loadTavern.
            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: android.webkit.JsResult?
            ): Boolean {
                if (suppressNextUnloadConfirm) {
                    result?.confirm()
                    return true
                }
                return super.onJsConfirm(view, url, message, result)
            }

            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                if (msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.LOG ||
                    msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.DEBUG ||
                    msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.TIP) {
                    return true
                }
                val level = when (msg.messageLevel()) {
                    android.webkit.ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                    else -> "WARN"
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

    // Mixed content and navigation scope

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
                    ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir,
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
                    AppToast.show(ctx,
                        "已导出到应用专属目录：${dest.name}",
                        android.widget.Toast.LENGTH_SHORT)
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

        // HTTP download via DownloadManager
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
                setDescription("ST Ctrl 导出")
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

            AppToast.show(context,
                "正在导出到 Downloads/TavernExports/$filename",
                android.widget.Toast.LENGTH_SHORT)
        } catch (e: Exception) {
            android.util.Log.w("TavernWebView", "Download failed: ${e.message}")
            AppToast.show(context,
                "导出失败：${e.message}", android.widget.Toast.LENGTH_SHORT)
        }
    }

    /** Fallback HTTP download when DownloadManager is blocked. */
    private fun downloadViaDirectHttp(url: String, userAgent: String?, filename: String) {
        val thread = Thread {
            try {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                    "TavernExports"
                )
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
                        AppToast.show(context,
                            "已导出到应用专属目录：$filename",
                            android.widget.Toast.LENGTH_SHORT)
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

    // CSS / JS injection

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

    private fun injectOptimizations() {
        if (optimizationsInjected) return
        optimizationsInjected = true
        try {
            val js = context.assets.open("tavern-optimization-inject.js")
                .bufferedReader().use { it.readText() }
            evaluateJavascript(js, null)
        } catch (e: Exception) {
            android.util.Log.w("TavernWebView", "Failed to inject tavern optimizations: ${e.message}")
        }
    }

    fun setOnPageLoaded(callback: () -> Unit) { onPageLoaded = callback }
    fun setOnError(callback: (String) -> Unit) { onError = callback }

    fun loadTavern(port: Int) {
        // Guard: don't attempt to load if WebView has already been destroyed
        try {
            cssInjected = false
            suppressNextUnloadConfirm = true
            TavernAccessGate.reset()
            val compat = com.tavern.app.console.SettingsState.compatModeEnabled()
            requestCompatMode = compat
            val generation = ++loadGeneration
            val origin = "http://127.0.0.1:$port"
            if (lastRequestedPort != 0 && lastRequestedPort != port) {
                clearHistoryWhenLoaded = true
            }
            lastRequestedPort = port
            val cookie = "st_compat=" + (if (compat) "1" else "0") + "; Path=/"
            val cookieManager = android.webkit.CookieManager.getInstance()

            loadWhenCookieCommitted(cookieManager, origin, cookie, generation) {
                if (lastLoadCompatMode != compat) {
                    clearCache(true)
                    lastLoadCompatMode = compat
                }
                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                loadUrl(origin)
            }
        } catch (e: IllegalStateException) {
            android.util.Log.w("TavernWebView", "loadTavern called after destroy, ignoring")
        }
    }

    private fun loadWhenCookieCommitted(
        cookieManager: android.webkit.CookieManager,
        origin: String,
        cookie: String,
        generation: Int,
        load: () -> Unit
    ) {
        var delivered = false
        fun complete() {
            if (delivered || generation != loadGeneration) return
            delivered = true
            post {
                if (generation == loadGeneration) {
                    cookieManager.flush()
                    load()
                }
            }
        }

        try {
            cookieManager.setCookie(origin, cookie) { complete() }
        } catch (_: Exception) {
            cookieManager.setCookie(origin, cookie)
            cookieManager.flush()
            complete()
            return
        }

        // WebView versions that never invoke the callback must still complete the load.
        postDelayed({ complete() }, 500L)
    }

    /** Push the current perf profile into the loaded page without a reload. */
    fun applyPerformanceProfile() {
        // The card workshop is an https page inside the tavern that signs in through
        // Discord and keeps that session in a cross-site cookie. This WebView refuses
        // third-party cookies by default, so the sign-in was forgotten on every visit
        // and the page stopped seeing the tavern after the redirect; a browser accepts
        // them, which is why the same workshop behaves there.
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        val factor = com.tavern.app.console.SettingsState.currentFactor()
        val throttle = com.tavern.app.console.SettingsState.timerThrottleEnabled()
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        try {
            val js = """
                (function(){
                    window.__stctrlPerfFactor = $factor;
                    window.__stctrlTimerThrottle = $throttle;
                    if (window.__stctrlPerfApply) window.__stctrlPerfApply();
                })();
            """.trimIndent()
            evaluateJavascript(js, null)
        } catch (_: Exception) { /* WebView may be destroyed */ }
    }

    private fun injectTimerThrottle() {
        evaluateJavascript("""
            (function(){
                if (window.__stctrl_throttled) return;
                window.__stctrl_throttled = true;
                var origSI = window.setInterval;
                // Only throttle setInterval calls with interval >= 500ms — these are
                // polling loops (character list refresh, status checks, etc.), not UI
                // interactions. setTimeout is left untouched to keep UI responsive.
                window.__stctrlTimerThrottle = false;
                window.setInterval = function(fn, ms) {
                    if (ms && ms >= 500 && window.__stctrlTimerThrottle) {
                        ms = ms * (Number(window.__stctrlThrottleFactor) || 2);
                    }
                    return origSI.call(window, fn, ms);
                };
            })();
        """.trimIndent(), null)
    }

    /** Pause WebView rendering and JS timers when user leaves the tavern. */
    fun pauseRendering() {
        if (!com.tavern.app.console.SettingsState.tavernPauseRenderEnabled()) return
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

        // Release renderer memory when in background for > 30s. The HTTP cache is deliberately
        // left alone: clearing it does not free the renderer's heap, and it makes the next visit
        // re-download every front-end card dependency, which measured in tens of seconds.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isPaused) {
                try {
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
    private fun fixLegacyValueSyntax(css: String): String {
        var fixed = css
        val identities = mapOf(
            "--tw-backdrop-blur" to "blur(0px)",
            "--tw-backdrop-brightness" to "brightness(1)",
            "--tw-backdrop-contrast" to "contrast(1)",
            "--tw-backdrop-grayscale" to "grayscale(0)",
            "--tw-backdrop-hue-rotate" to "hue-rotate(0deg)",
            "--tw-backdrop-invert" to "invert(0)",
            "--tw-backdrop-opacity" to "opacity(1)",
            "--tw-backdrop-saturate" to "saturate(1)",
            "--tw-backdrop-sepia" to "sepia(0)",
            "--tw-blur" to "blur(0px)",
            "--tw-brightness" to "brightness(1)",
            "--tw-contrast" to "contrast(1)",
            "--tw-grayscale" to "grayscale(0)",
            "--tw-hue-rotate" to "hue-rotate(0deg)",
            "--tw-invert" to "invert(0)",
            "--tw-saturate" to "saturate(1)",
            "--tw-sepia" to "sepia(0)",
            "--tw-drop-shadow" to "drop-shadow(0 0 #0000)",
            "--tw-rotate-x" to "rotateX(0deg)",
            "--tw-rotate-y" to "rotateY(0deg)",
            "--tw-rotate-z" to "rotateZ(0deg)",
            "--tw-skew-x" to "skewX(0deg)",
            "--tw-skew-y" to "skewY(0deg)"
        )
        identities.forEach { (name, value) ->
            fixed = fixed.replace("var($name,)", "var($name, $value)")
        }
        fixed = fixed.replace("var(--tw-ring-inset,)", "var(--tw-ring-inset)")
        fixed = Regex("""calc\(\s*(-?)infinity\s*\*\s*1(px|%|rem|em|vh|vw)\s*\)""", RegexOption.IGNORE_CASE)
            .replace(fixed) { match -> "${match.groupValues[1]}9999${match.groupValues[2]}" }
        fixed = Regex("""(--tw-gradient-position\s*:\s*[^;{}]+?)\s+in\s+(?:oklab|oklch|lab|lch|xyz(?:-[a-z0-9-]+)?)""", RegexOption.IGNORE_CASE)
            .replace(fixed, "$1")
        return Regex("""((?:linear|radial|conic)-gradient\([^)]*?)\s+in\s+(?:oklab|oklch|lab|lch|xyz(?:-[a-z0-9-]+)?)""", RegexOption.IGNORE_CASE)
            .replace(fixed, "$1")
    }

    private fun protectSupportsConditions(css: String): Pair<String, List<String>> {
        val conditions = mutableListOf<String>()
        val out = StringBuilder()
        var i = 0
        while (i < css.length) {
            val idx = css.indexOf("@supports", i)
            if (idx < 0) { out.append(css, i, css.length); break }
            out.append(css, i, idx)
            var pos = idx + "@supports".length
            var depth = 0
            var open = -1
            while (pos < css.length) {
                when (css[pos]) {
                    '(' -> depth++
                    ')' -> depth--
                    '{' -> if (depth == 0) { open = pos; break }
                }
                pos++
            }
            if (open < 0) { out.append(css, idx, css.length); break }
            conditions.add(css.substring(idx, open))
            out.append("__STCTRL_SUPPORTS_").append(conditions.lastIndex).append("__")
            i = open
        }
        return out.toString() to conditions
    }

    private fun restoreSupportsConditions(css: String, conditions: List<String>): String {
        var fixed = css
        conditions.forEachIndexed { index, condition ->
            fixed = fixed.replace("__STCTRL_SUPPORTS_${index}__", condition)
        }
        return fixed
    }

    private fun convertContainerQueries(css: String): String =
        Regex("""@container(?:\s+[-\w]+)?\s*\(\s*((?:inline-size|width|min-width|max-width|min-inline-size|max-inline-size)\s*(?::|[<>]=?)\s*[^)]*)\)""", RegexOption.IGNORE_CASE)
            .replace(css, "@media ($1)")

    private fun fixCompatCSS(css: String): String {
        val protectedSupports = protectSupportsConditions(css)
        var fixed = convertContainerQueries(fixLegacyValueSyntax(protectedSupports.first))
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
            val alphaText = Regex("""/\s*([\d.]+%?)\s*$""").find(m.groupValues[1])?.groupValues?.get(1)
            val alpha = if (alphaText != null) {
                val parsed = alphaText.replace("%", "").toDoubleOrNull() ?: 1.0
                if (alphaText.endsWith("%")) parsed / 100.0 else parsed
            } else 1.0
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
            return if (mixedA >= 1.0) "rgb($r,$g,$b)" else "rgba($r,$g,$b,${String.format(Locale.US, "%.3f", mixedA).trimEnd('0').trimEnd('.')})"
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
                if ((c1.contains("var(") && parseColorToRgba(c1) == null) ||
                    (c2.contains("var(") && parseColorToRgba(c2) == null)
                ) {
                    sb.append(fixed, idx, j)
                    i = j
                    continue
                }
                val best = if (p2 > p1 && !c2.equals("transparent", ignoreCase = true)) c2 else c1
                sb.append(best.ifEmpty { "transparent" })
                i = j
            }
            fixed = sb.toString()
        }
        return restoreSupportsConditions(fixed, protectedSupports.second)
    }

    // Oklab matrices are the reference values Björn Ottosson published with the
    // colour space; the transform has one published definition.
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
