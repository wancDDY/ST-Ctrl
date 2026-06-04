package com.tavern.app.webview

import android.util.Log
import android.webkit.JavascriptInterface

class WebViewBridge {

    @JavascriptInterface
    fun log(message: String) {
        Log.d("TavernWebView", message)
    }

    @JavascriptInterface
    fun getPlatform(): String = "android"

    @JavascriptInterface
    fun getAppVersion(): String = "1.0.0"

    @JavascriptInterface
    fun shareText(text: String) {
        // Reserved: call system share
    }
}
