package com.tavern.app.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class TavernWebView(context: Context) : WebView(context) {

    private var onPageLoaded: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var cssInjected = false

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
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            saveFormData = false
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

        addJavascriptInterface(WebViewBridge(), "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                android.util.Log.d("TavernWebView", "onPageStarted: $url")
                cssInjected = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                android.util.Log.d("TavernWebView", "onPageFinished: $url")
                if (url?.startsWith("http://127.0.0.1") == true) {
                    injectCSS()
                    injectTimerThrottle()
                    onPageLoaded?.invoke()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                android.util.Log.d("TavernWebView", "shouldOverrideUrlLoading: ${request?.url}")
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
                val intent = (fileChooserParams?.createIntent() ?: Intent()).apply {
                    // Always redirect to the universally-supported GET_CONTENT path
                    action = Intent.ACTION_GET_CONTENT
                    addCategory(Intent.CATEGORY_OPENABLE)
                    // Preserve the negotiated MIME type from the page's accept attribute.
                    // Only fall back to */* if no type was provided or it is already */*.
                    if (type == null || type.isNullOrBlank() || type == "*/*") {
                        type = "*/*"
                    }
                }
                onFileChooserRequested?.invoke(filePathCallback, intent)
                return true
            }
        }
    }

    private fun injectCSS() {
        if (cssInjected) return
        cssInjected = true

        evaluateJavascript("""
(function(){
  if (document.getElementById('tavern-mobile-css')) return;
  var s=document.createElement('style');
  s.id='tavern-mobile-css';
  s.textContent=`
    *{-webkit-tap-highlight-color:transparent;touch-action:manipulation}
    body{overscroll-behavior-y:contain;background:#0a0a12!important}

    /* ── Rendering optimization: contain layout/style scope ── */
    #chat .mes, #chat [class*="mes"], #chat [class*="message"],
    .chat-content > *, #chat-content > * {
      contain:layout style;
    }

    /* ── Reduce paint area for frequently-updated elements ── */
    #chat, .chat-content, #chat-content {
      contain:layout style;
    }

  `;
  document.head.appendChild(s);

  // Track last rAF id so pauseRendering can cancel pending animation frames
  var _origRAF = window.requestAnimationFrame;
  window.requestAnimationFrame = function(cb) {
    var id = _origRAF.call(window, cb);
    window.__tavernLastAnimId = id;
    return id;
  };
})();
        """.trimIndent(), null)
    }

    fun setOnPageLoaded(callback: () -> Unit) { onPageLoaded = callback }
    fun setOnError(callback: (String) -> Unit) { onError = callback }

    fun loadTavern(port: Int) {
        // Guard: don't attempt to load if WebView has already been destroyed
        try {
            android.util.Log.d("TavernWebView", "loadTavern: port=$port lastLoadedPort was different, loading URL")
            cssInjected = false
            loadUrl("http://127.0.0.1:$port")
        } catch (e: IllegalStateException) {
            android.util.Log.w("TavernWebView", "loadTavern called after destroy, ignoring")
        }
    }


    private var pendingTimerThrottle = false

    /** Apply performance settings based on the user's chosen mode. */
    fun applyPerfMode(mode: com.tavern.app.console.PerfMode) {
        // Cache mode: always LOAD_DEFAULT — localhost content is dynamic,
        // LOAD_CACHE_ELSE_NETWORK/LOAD_CACHE_ONLY break tavern loading
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

        // SAVE mode: flag timer throttle for injection after page loads
        pendingTimerThrottle = (mode == com.tavern.app.console.PerfMode.SAVE)
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
                // Cancel the last known animation frame if tracked.
                // __tavernLastAnimId is set by the rAF wrapper injected on page load.
                var animId = window.__tavernLastAnimId || 0;
                if (animId) { cancelAnimationFrame(animId); window.__tavernLastAnimId = 0; }
            })();
        """.trimIndent(), null)
        } catch (_: Exception) { /* WebView may be destroyed */ }
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
}
