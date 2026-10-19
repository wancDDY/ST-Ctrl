package com.tavern.app.webview

import com.tavern.app.util.AppToast
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
                AppToast.show(context, "分享失败：${e.message}", Toast.LENGTH_SHORT)
            }
        }
    }

    @JavascriptInterface
    fun toggleFullscreen(): String {
        val enabled = !fullscreenEnabled
        fullscreenEnabled = enabled
        Handler(Looper.getMainLooper()).post {
            onFullscreenChanged?.invoke(enabled)
        }
        return if (enabled) "1" else "0"
    }

    @JavascriptInterface
    fun isFullscreen(): String = if (fullscreenEnabled) "1" else "0"

    @JavascriptInterface
    /** Returns 1 if the user enabled compatibility mode in settings. */
    fun compatModeEnabled(): String =
        if (com.tavern.app.console.SettingsState.compatModeEnabled()) "1" else "0"

    @JavascriptInterface
    /** Current perf factor (0.15..1.0), used by the injected chat renderer. */
    fun perfFactor(): String =
        String.format(java.util.Locale.US, "%.3f", com.tavern.app.console.SettingsState.currentFactor())

    /**
     * Returns 1 when the named tavern optimization is enabled.
     *
     * An unknown name answers "off". A core that is newer than the app asks for a name this
     * build has never heard of, and the page script of such a core reads the answer as consent
     * to change the prompt; "off" is the only safe answer for something the app cannot see in
     * its own settings.
     */
    @JavascriptInterface
    fun tavernOpt(name: String): String {
        val enabled = when (name) {
              "chat_virtual" -> com.tavern.app.console.SettingsState.tavernChatVirtualEnabled()
              "perf_animation" -> com.tavern.app.console.SettingsState.tavernPerfAnimEnabled()
              "long_memory" -> com.tavern.app.console.SettingsState.tavernLongMemoryEnabled()
              else -> false
        }
        return if (enabled) "1" else "0"
    }

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
                val comma = dataUrl.indexOf(',')
                if (comma < 0) {
                    AppToast.show(context, "导出失败：数据为空", Toast.LENGTH_SHORT)
                    return@post
                }
                val safeFileName = fileName.ifBlank { "tavern-export" }

                onSaveRequested?.invoke(dataUrl, mimeType, safeFileName)
                    ?: AppToast.show(context, "导出错误：未初始化", Toast.LENGTH_SHORT)
            } catch (e: Exception) {
                Log.w("WebViewBridge", "saveBytes failed: ${e.message}")
                AppToast.show(context, "导出失败：${e.message}", Toast.LENGTH_SHORT)
            }
        }
    }

    @JavascriptInterface
    /**
     * Receives a Blob in fixed-size chunks. Each chunk is decoded to a small
     * byte[] and appended to a cache file, so exports never materialize as one
     * huge Base64 string or byte[] in the app process.
     */
    fun saveChunk(dataUrl: String, mimeType: String, fileName: String, index: Int, total: Int) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return
            val payload = dataUrl.substring(comma + 1)
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            val file = synchronized(chunkLock) {
                if (index <= 0) {
                    chunkFile?.let { runCatching { it.delete() } }
                    chunkFile = java.io.File(context.cacheDir, "tavern-export-${System.currentTimeMillis()}.part")
                }
                val target = chunkFile ?: return@synchronized null
                java.io.FileOutputStream(target, true).use { it.write(bytes) }
                chunkMime = mimeType
                chunkName = fileName
                if (index + 1 >= total) {
                    val ready = target
                    chunkFile = null
                    ready
                } else {
                    null
                }
            }
            if (file != null) {
                val ready = file
                val mime = chunkMime.ifBlank { mimeType }
                val name = chunkName.ifBlank { fileName }
                Handler(Looper.getMainLooper()).post {
                    onChunkComplete?.invoke(ready, mime, name)
                        ?: AppToast.show(context, "导出错误：未初始化", Toast.LENGTH_SHORT)
                }
            }
        } catch (e: Exception) {
            Log.w("WebViewBridge", "saveChunk failed at $index/$total: ${e.message}")
            AppToast.show(context, "导出失败：${e.message}", Toast.LENGTH_SHORT)
        }
    }

    companion object {
        /**
         * Set by MainActivity. Called when JS requests a file save.
         * Parameters: (dataUrl, mimeType, suggestedFileName)
         * The handler should open a SAF CreateDocument launcher.
         */
        var onSaveRequested: ((String, String, String) -> Unit)? = null

        /**
         * Called with the assembled cache file after the last Blob chunk.
         * The handler should open a SAF CreateDocument launcher and delete the
         * cache file afterwards.
         */
        var onChunkComplete: ((java.io.File, String, String) -> Unit)? = null

        /** Called when the injected tavern fullscreen button changes window mode. */
        var onFullscreenChanged: ((Boolean) -> Unit)? = null

        @Volatile
        private var fullscreenEnabled = false

        fun setFullscreenState(enabled: Boolean) {
            fullscreenEnabled = enabled
        }

        /** Drop any half-assembled chunk export, usually on Activity teardown. */
        fun cancelChunkExport() {
            synchronized(chunkLock) {
                chunkFile?.let { runCatching { it.delete() } }
                chunkFile = null
                chunkMime = ""
                chunkName = ""
            }
        }

        private val chunkLock = Any()
        private var chunkFile: java.io.File? = null
        private var chunkMime = ""
        private var chunkName = ""
    }
}
