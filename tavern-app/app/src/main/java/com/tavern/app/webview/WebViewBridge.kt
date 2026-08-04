package com.tavern.app.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast

class WebViewBridge(private val context: Context) {

    @JavascriptInterface
    fun log(message: String) {
        Log.d("TavernWebView", message)
    }

    @JavascriptInterface
    fun getPlatform(): String = "android"

    @JavascriptInterface
    fun getAppVersion(): String = com.tavern.app.BuildConfig.VERSION_NAME

    @JavascriptInterface
    fun shareText(text: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                context.startActivity(android.content.Intent.createChooser(send, "分享"))
            } catch (e: Exception) {
                Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    /** Returns 1 if the user enabled compatibility mode in settings. */
    fun compatModeEnabled(): String =
        if (com.tavern.app.console.SettingsState.compatModeEnabled()) "1" else "0"

    @JavascriptInterface
    /**
     * Called from injected JS to save exported blob data.
     * dataUrl is a "data:<mime>;base64,..." string.
     * Android opens a SAF file picker — the user chooses where to save.
     *
     * SAF-based export: no DownloadListener, no blob stash.
     */
    fun saveBytes(dataUrl: String, mimeType: String, fileName: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                // Extract base64 payload from data URL
                val comma = dataUrl.indexOf(',')
                if (comma < 0) {
                    Toast.makeText(context, "导出失败: 数据为空", Toast.LENGTH_SHORT).show()
                    return@post
                }
                val base64 = dataUrl.substring(comma + 1)
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val safeFileName = fileName.ifBlank { "tavern-export" }

                onSaveRequested?.invoke(bytes, mimeType, safeFileName)
                    ?: Toast.makeText(context, "导出错误: 未初始化", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.w("WebViewBridge", "saveBytes failed: ${e.message}")
                Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        /**
         * Set by MainActivity. Called when JS requests a file save.
         * Parameters: (bytes, mimeType, suggestedFileName)
         * The handler should open a SAF CreateDocument launcher.
         */
        var onSaveRequested: ((ByteArray, String, String) -> Unit)? = null
    }
}
