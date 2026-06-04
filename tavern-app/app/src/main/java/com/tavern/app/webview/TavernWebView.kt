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

    private var isPaused = false

    init { configure() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
            cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            setRenderPriority(android.webkit.WebSettings.RenderPriority.NORMAL)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
        }

        addJavascriptInterface(WebViewBridge(), "AndroidBridge")

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                cssInjected = false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith("http://127.0.0.1") == true) {
                    clearFormData() // clear autofill from previous sessions
                    injectCSS()
                    onPageLoaded?.invoke()
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean = false

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
                val intent = fileChooserParams?.createIntent()
                if (intent != null && filePathCallback != null) {
                    onFileChooserRequested?.invoke(filePathCallback, intent)
                    return true
                }
                return super.onShowFileChooser(webView, filePathCallback, fileChooserParams)
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
    html{-webkit-overflow-scrolling:touch}
    body{overscroll-behavior-y:contain}

    .tavern-page-overlay{
      position:fixed!important;top:0!important;left:0!important;
      width:100vw!important;height:100vh!important;z-index:200!important;
      background:var(--SmartThemeBlurTintColor,#0a0a12)!important;
      overflow-y:auto!important;-webkit-overflow-scrolling:touch!important;
      transform:translateX(30%)!important;
      opacity:0!important;
      transition:transform 0.25s cubic-bezier(0.05,0.7,0.1,1.0),
                 opacity 0.2s ease-out!important;
      will-change:transform,opacity!important;
      -webkit-transform:translateZ(0)!important;
    }
    .tavern-page-overlay.open{
      transform:translateX(0)!important;
      opacity:1!important;
    }
    .tavern-page-back{
      position:sticky;top:0;z-index:10;display:flex;align-items:center;gap:8px;
      padding:12px 16px;background:var(--SmartThemeBlurTintColor,#0a0a12);
      border-bottom:1px solid rgba(255,255,255,0.06);
      color:#d4a853;font-size:15px;font-weight:500;cursor:pointer;
    }
    .tavern-page-back svg{width:20px;height:20px;stroke:#d4a853}
  `;
  document.head.appendChild(s);
})();
        """.trimIndent(), null)

        evaluateJavascript("""
(function(){
  if (window.__tavernOverlay) return;
  window.__tavernOverlay = true;

  var overlay = null;
  var originalPanel = null;
  var panelParent = null;
  var panelNextSibling = null;
  var activeTriggerId = null;
  var scanTimer = null;
  var isTransitioning = false;

  var PANEL_SELECTORS = [
    '#sheld',
    '.drawer-content',
    '#world_info',
    '#character_popup',
    '#dialogs',
    '[id*="nav-panel"]',
    '[class*="drawer-content"]',
    '.popup-content',
    '#extensionsPanel',
    '#extensions-panel',
    '#WorldInfo',
    '#world-info-panel',
    '.world-info-panel',
    '#settings-panel',
    '[class*="-panel"]:not([class*="hidden"])',
    '.panel-visible'
  ];

  function findVisiblePanel() {
    for (var i = 0; i < PANEL_SELECTORS.length; i++) {
      try {
        var els = document.querySelectorAll(PANEL_SELECTORS[i]);
        for (var j = 0; j < els.length; j++) {
          var el = els[j];
          if (el === originalPanel && overlay) continue;
          var rect = el.getBoundingClientRect();
          if (rect.height > 60 && rect.width > 60) {
            var s = window.getComputedStyle(el);
            if (s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0') {
              return el;
            }
          }
        }
      } catch(e) {}
    }
    return null;
  }

  function getTriggerId(el) {
    if (!el) return null;
    // Use the closest identifiable ancestor
    var target = el.closest('div,button,a,span,i,[role="button"]') || el;
    var text = (target.textContent || '').replace(/\\s+/g,' ').trim().substring(0, 40);
    var rect = target.getBoundingClientRect();
    var pos = Math.round(rect.left) + ',' + Math.round(rect.top);
    var id = target.id || '';
    var cls = (target.className || '').toString().split(' ').slice(0,2).join('.');
    return (id || cls || text || 'unknown') + '|' + pos;
  }

  function isInHeaderArea(el) {
    // Check for known header containers
    if (el.closest('#top-bar,header,nav,.top-bar,[class*="top-bar"],'+
      '[class*="header-bar"],#header,#top-nav,.navbar,[class*="navbar"],'+
      '[class*="action-bar"],#action-bar,.sticky-top')) return true;
    // Check viewport position: top 56px or bottom 56px
    var rect = el.getBoundingClientRect();
    if (rect.top <= 56 || rect.bottom >= window.innerHeight - 56) return true;
    return false;
  }

  function closeOverlay() {
    if (!overlay || isTransitioning) return;
    isTransitioning = true;
    overlay.classList.remove('open');

    if (originalPanel && panelParent) {
      try {
        if (panelNextSibling && panelNextSibling.parentNode === panelParent) {
          panelParent.insertBefore(originalPanel, panelNextSibling);
        } else if (panelParent.isConnected) {
          panelParent.appendChild(originalPanel);
        } else {
          document.body.appendChild(originalPanel);
        }
      } catch(e) {
        try { document.body.appendChild(originalPanel); } catch(e2) {}
      }
    }

    var ov = overlay;
    setTimeout(function(){
      try { if (ov.parentNode) ov.parentNode.removeChild(ov); } catch(e) {}
      // Scroll main chat to top
      window.scrollTo({top: 0, behavior: 'smooth'});
      var chat = document.querySelector('#chat');
      if (chat) chat.scrollTop = chat.scrollHeight;
      overlay = null;
      originalPanel = null;
      activeTriggerId = null;
      isTransitioning = false;
    }, 280);
  }

  function openOverlay(panel, triggerId) {
    if (!panel || isTransitioning) return;
    if (panel === originalPanel && overlay) return;

    // Same trigger toggles close
    if (overlay && triggerId && activeTriggerId === triggerId) {
      closeOverlay();
      return;
    }

    // Different panel → close first, then reopen
    if (overlay) {
      var p = panel, t = triggerId;
      closeOverlay();
      setTimeout(function(){ openOverlay(p, t); }, 320);
      return;
    }

    isTransitioning = true;
    panelParent = panel.parentNode;
    panelNextSibling = panel.nextSibling;
    originalPanel = panel;
    activeTriggerId = triggerId || null;

    // Extract title from panel
    var title = '';
    var titleEl = panel.querySelector('h2,h3,h4,.panel-title,[class*="title"]');
    if (titleEl) title = titleEl.textContent.replace(/\\s+/g,' ').trim();

    // Build overlay
    overlay = document.createElement('div');
    overlay.className = 'tavern-page-overlay';

    var backBtn = document.createElement('div');
    backBtn.className = 'tavern-page-back';
    backBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg><span>' + title + '</span>';
    backBtn.addEventListener('click', function(e){ e.stopPropagation(); closeOverlay(); });
    overlay.appendChild(backBtn);
    overlay.appendChild(panel);
    document.body.appendChild(overlay);

    requestAnimationFrame(function(){
      requestAnimationFrame(function(){
        overlay.classList.add('open');
        isTransitioning = false;
      });
    });
  }

  function scanAndCapture(triggerId) {
    if (overlay || isTransitioning || window.innerWidth >= 768) return;
    var panel = findVisiblePanel();
    if (panel) {
      openOverlay(panel, triggerId);
    }
  }

  document.addEventListener('click', function(e){
    if (window.innerWidth >= 768) return;
    if (e.target.closest('input,textarea,select')) return;
    if (e.target.closest('.tavern-page-overlay,.tavern-page-back')) return;

    var el = e.target;
    if (!isInHeaderArea(el)) return;

    var triggerId = getTriggerId(el);

    // If overlay is open and same trigger → close
    if (overlay && triggerId && activeTriggerId === triggerId) {
      e.stopPropagation();
      e.preventDefault();
      closeOverlay();
      return;
    }

    // Wait for ST to render, then capture
    clearTimeout(scanTimer);
    scanTimer = setTimeout(function(){ scanAndCapture(triggerId); }, 200);
  }, true);

  var observer = new MutationObserver(function(mutations){
    if (overlay || isTransitioning || window.innerWidth >= 768) return;

    var relevant = false;
    for (var i = 0; i < mutations.length && !relevant; i++) {
      var m = mutations[i];
      if (m.type === 'childList' && m.addedNodes.length > 0) relevant = true;
      if (m.type === 'attributes' &&
          (m.attributeName === 'style' || m.attributeName === 'class')) relevant = true;
    }
    if (!relevant) return;

    clearTimeout(scanTimer);
    scanTimer = setTimeout(function(){ scanAndCapture(null); }, 250);
  });

  observer.observe(document.body, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['style','class']
  });

  window.__tavernCloseOverlay = closeOverlay;
  window.__tavernIsOverlayOpen = function(){ return !!overlay; };
})();
        """.trimIndent(), null)
    }

    fun setOnPageLoaded(callback: () -> Unit) { onPageLoaded = callback }
    fun setOnError(callback: (String) -> Unit) { onError = callback }

    fun loadTavern(port: Int) {
        cssInjected = false
        loadUrl("http://127.0.0.1:$port")
    }


    /** Apply performance settings based on the user's chosen mode. */
    fun applyPerfMode(mode: com.tavern.app.console.PerfMode) {
        settings.setRenderPriority(
            if (mode == com.tavern.app.console.PerfMode.FULL)
                android.webkit.WebSettings.RenderPriority.HIGH
            else android.webkit.WebSettings.RenderPriority.NORMAL
        )
        // FULL: LOAD_DEFAULT (balanced), non-FULL: LOAD_CACHE_ELSE_NETWORK (prefer cache)
        settings.cacheMode = if (mode == com.tavern.app.console.PerfMode.FULL)
            android.webkit.WebSettings.LOAD_DEFAULT
        else android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
    }

    /** Pause WebView rendering and JS timers when user leaves the tavern. */
    fun pauseRendering() {
        if (isPaused) return
        isPaused = true
        onPause()
        evaluateJavascript("""
            (function(){
                if (window.__tavernTimersPaused) return;
                window.__tavernTimersPaused = true;
                // Clear animation frames
                var animId = window.__tavernLastAnimId || 0;
                if (animId) cancelAnimationFrame(animId);
            })();
        """.trimIndent(), null)
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
