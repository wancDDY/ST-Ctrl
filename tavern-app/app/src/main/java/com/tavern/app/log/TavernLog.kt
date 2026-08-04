package com.tavern.app.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*

object TavernLog {
    const val C_RED     = "\u001b[31m"
    const val C_GREEN   = "\u001b[32m"
    const val C_YELLOW  = "\u001b[33m"
    const val C_BLUE    = "\u001b[34m"
    const val C_MAGENTA = "\u001b[35m"
    const val C_CYAN    = "\u001b[36m"
    const val C_GRAY    = "\u001b[90m"
    const val C_RESET   = "\u001b[0m"

    private val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _lines = MutableStateFlow(listOf("${C_GRAY}${sdf.format(Date())}  日志就绪$C_RESET"))
    val lines: StateFlow<List<String>> = _lines

    fun i(tag: String, msg: String) { append(C_GREEN, tag, msg) }
    fun w(tag: String, msg: String) { append(C_YELLOW, tag, msg) }
    fun e(tag: String, msg: String) { append(C_RED, tag, msg) }
    fun webview(msg: String) { append(C_MAGENTA, "WebView", msg) }
    fun node(msg: String) { append(C_BLUE, "Node", msg) }

    private fun append(color: String, tag: String, msg: String) {
        val ts = sdf.format(Date())
        val line = "$color$ts  [$tag]  $msg$C_RESET"
        synchronized(_lines) {
            val current = _lines.value.toMutableList()
            current.add(line)
            if (current.size > 5000) current.removeAt(0)
            _lines.value = current
        }
    }

    fun rawServer(msg: String) {
        val clean = msg.replace(Regex("\u001b\\[[0-9;]*m"), "")
        synchronized(_lines) {
            val current = _lines.value.toMutableList()
            current.add("${C_BLUE}$clean$C_RESET")
            if (current.size > 5000) current.removeAt(0)
            _lines.value = current
        }
    }

    // File-position tracking for incremental reads
    // Line count tracking for incremental reads (not RandomAccessFile)
    @Volatile var serverLogLines: Int = 0

    fun pollServerLog(ctx: android.content.Context) {
        val logFile = java.io.File(ctx.filesDir, "tavern-server.log")
        if (!logFile.exists()) return
        try {
            val all = mutableListOf<String>()
            java.io.BufferedReader(java.io.FileReader(logFile)).use { br ->
                br.lines().forEach { all.add(it) }
            }
            val total = all.size
            if (total < serverLogLines) {
                // File was truncated/cleared — reset the cursor so new lines
                // keep flowing instead of being skipped forever.
                serverLogLines = 0
            }
            if (total > serverLogLines) {
                for (i in serverLogLines until total) rawServer(all[i])
                serverLogLines = total
            }
        } catch (_: Exception) {}
    }

    /** True clear: reset in-memory buffer, truncate the log file and reset
     *  the incremental cursor, so old logs don't reappear after a restart. */
    fun clear(ctx: android.content.Context) {
        synchronized(_lines) {
            _lines.value = listOf("${C_GRAY}${sdf.format(Date())}  已清空$C_RESET")
        }
        try {
            java.io.File(ctx.filesDir, "tavern-server.log").writeText("")
        } catch (_: Exception) {}
        serverLogLines = 0
    }
}
