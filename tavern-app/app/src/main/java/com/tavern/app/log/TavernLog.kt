package com.tavern.app.log

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
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

    /**
     * Where a line came from. Four sources reach the screen — the app's own
     * startup steps, the Node process the app manages, the tavern server's
     * stdout/stderr, and the WebView that renders it — and the report of a
     * failure is only readable once they can be told apart.
     */
    enum class Channel(val label: String) {
        BOOT("启动"),
        NODE("Node"),
        SERVER("服务端"),
        PAGE("页面"),
        APP("应用"),
    }

    data class Entry(val channel: Channel, val text: String)

    private const val MAX_LINES = 5000
    private const val APP_LOG = "stctrl-app.log"
    private const val APP_LOG_PREVIOUS = "stctrl-app.1.log"
    private const val APP_LOG_MAX_BYTES = 512L * 1024L
    private const val CRASH_REPORT = "last-crash.txt"
    private const val CRASH_REPORTED = "last-crash-reported.txt"
    private const val CRASH_LINES = 24

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    // "2026-10-04 19:09:11  [tag]  message"
    private val PersistedTag = Regex("^\\S+ \\S+\\s+\\[([^\\]]+)]")
    private val AnsiPattern = Regex("\u001b\\[[0-9;]*m")

    private val _entries = MutableStateFlow(
        listOf(Entry(Channel.APP, "${C_GRAY}${clock.format(Date())}  日志就绪$C_RESET"))
    )
    val entries: StateFlow<List<Entry>> = _entries

    private var appLogFile: File? = null

    // Cursors for the Node lines re-read out of that same file.
    private var appLogLength: Long = -1L
    private var appLogLinesRead: Int = 0

    fun i(tag: String, msg: String) { append(Channel.APP, C_GREEN, tag, msg) }
    fun w(tag: String, msg: String) { append(Channel.APP, C_YELLOW, tag, msg) }
    fun e(tag: String, msg: String) { append(Channel.APP, C_RED, tag, msg) }
    fun webview(msg: String) { append(Channel.PAGE, C_MAGENTA, "WebView", msg) }
    fun node(msg: String) { append(Channel.NODE, C_BLUE, "Node", msg) }
    // A page that never loaded is the failure users hit most and it left no trace
    // before: the WebView reported it to a toast that disappeared with the message.
    fun page(msg: String) { append(Channel.PAGE, C_RED, "页面", msg) }

    // Triage category for everything boot/startup related — extraction, Node,
    // timeouts, crashes — so a user can quickly copy just this category.
    fun boot(level: Char, msg: String) {
        val color = when (level) {
            'e' -> C_RED
            'w' -> C_YELLOW
            else -> C_CYAN
        }
        append(Channel.BOOT, color, "启动", msg)
    }

    private fun append(channel: Channel, color: String, tag: String, msg: String) {
        val now = Date()
        push(Entry(channel, "$color${clock.format(now)}  [$tag]  $msg$C_RESET"))
        persist("${stamp.format(now)}  [$tag]  $msg")
    }

    /**
     * The newest [limit] lines of one channel, newest last. Used by the diagnostic export: the
     * page console is the only place a failing extension names itself, and the screen buffer is
     * the copy that survives without re-reading the log file.
     */
    fun tail(channel: Channel, limit: Int): List<Entry> =
        _entries.value.filter { it.channel == channel }.takeLast(limit.coerceAtLeast(0))

    private fun push(entry: Entry) {
        pushAll(listOf(entry))
    }

    private fun pushAll(newEntries: List<Entry>) {
        if (newEntries.isEmpty()) return
        synchronized(_entries) {
            val current = _entries.value.toMutableList()
            current.addAll(newEntries)
            if (current.size > MAX_LINES) current.subList(0, current.size - MAX_LINES).clear()
            _entries.value = current
        }
    }

    /**
     * Main process: persists its lines and restores what earlier runs left behind.
     */
    fun attach(context: Context) {
        attachWriter(context)
        archivePreviousLog(File(context.filesDir, APP_LOG))
        reportCrash(context)
    }

    /**
     * Persist-only hook for the Node process. That process runs the runner and
     * never shows this screen, so without it every Node line stayed in a buffer
     * nobody reads — the Node view was empty by construction.
     */
    fun attachWriter(context: Context) {
        appLogFile = File(context.filesDir, APP_LOG)
    }

    /**
     * The log starts empty on every entry, so the previous run is moved aside instead of being
     * replayed into the screen. It stays on disk as `stctrl-app.1.log` for the one case that
     * still wants it: working out why the last start failed.
     */
    private fun archivePreviousLog(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val previous = File(file.parentFile, APP_LOG_PREVIOUS)
            previous.delete()
            file.renameTo(previous)
        } catch (_: Exception) {
        }
    }

    private fun channelForTag(tag: String): Channel = when (tag) {
        "启动", "崩溃" -> Channel.BOOT
        "Node" -> Channel.NODE
        "WebView", "页面" -> Channel.PAGE
        else -> Channel.APP
    }

    private fun reportCrash(context: Context) {
        val crash = File(context.filesDir, CRASH_REPORT)
        if (!crash.exists() || crash.length() == 0L) return
        val lines = try {
            crash.readLines()
        } catch (_: Exception) {
            return
        }
        if (lines.isEmpty()) return
        append(Channel.BOOT, C_RED, "崩溃", "上次运行发生未捕获异常：")
        lines.take(CRASH_LINES).forEach { push(Entry(Channel.BOOT, "${C_RED}$it$C_RESET")) }
        if (lines.size > CRASH_LINES) {
            push(Entry(Channel.BOOT, "${C_RED}…完整报告见 files/$CRASH_REPORTED$C_RESET"))
        }
        // Renamed, not deleted: the report stays on disk, and the next start does not
        // replay a crash the log already carries.
        try {
            crash.renameTo(File(context.filesDir, CRASH_REPORTED))
        } catch (_: Exception) {
        }
    }

    private fun persist(line: String) {
        val file = appLogFile ?: return
        try {
            if (file.length() > APP_LOG_MAX_BYTES) {
                val previous = File(file.parentFile, APP_LOG_PREVIOUS)
                previous.delete()
                file.renameTo(previous)
            }
            file.appendText("$line\n")
        } catch (_: Exception) {
        }
    }

    fun rawServer(msg: String) {
        // Server output already reaches disk through tavern-server.log; only the
        // in-memory copy is added here.
        val clean = AnsiPattern.replace(msg, "")
        push(Entry(Channel.SERVER, "$C_BLUE$clean$C_RESET"))
    }

    // Byte offset of the last complete server line read. The screen polls the tail
    // every few seconds for as long as it is open, and the file is the tavern's
    // whole stdout; re-reading it from the start grew with uptime.
    @Volatile private var serverLogOffset: Long = -1L

    // A first read starts inside this window instead of at the beginning. Only the
    // newest lines are shown, and a log that has run for weeks is tens of megabytes
    // before the screen gets its first frame.
    private val serverLogTailBytes = 2L * 1024L * 1024L

    fun pollServerLog(ctx: android.content.Context) {
        val logFile = File(ctx.filesDir, "tavern-server.log")
        if (!logFile.exists()) return
        val length = logFile.length()
        if (length == serverLogOffset) return
        val firstRead = serverLogOffset < 0L || length < serverLogOffset
        val start = if (firstRead) {
            (length - serverLogTailBytes).coerceAtLeast(0L)
        } else {
            serverLogOffset
        }
        val bytes = try {
            val chunk = ByteArray((length - start).toInt())
            java.io.RandomAccessFile(logFile, "r").use { file ->
                file.seek(start)
                file.readFully(chunk)
            }
            chunk
        } catch (_: Exception) {
            return
        }
        val lastNewline = bytes.indexOfLast { it == '\n'.code.toByte() }
        if (lastNewline < 0) {
            // The write that changed the length has not reached a line end yet.
            return
        }
        serverLogOffset = start + lastNewline + 1
        var complete = String(bytes, 0, lastNewline, Charsets.UTF_8)
        if (firstRead && start > 0L) {
            // start landed inside a line or exactly on its terminator; either way the text
            // before the first newline is not a line of its own.
            complete = if (bytes.isNotEmpty() && bytes[0] == '\n'.code.toByte()) {
                complete.substring(1)
            } else {
                complete.substringAfter('\n', "")
            }
        }
        if (complete.isEmpty()) return
        val lines = complete.split('\n')
        val visible = if (lines.size > MAX_LINES) lines.takeLast(MAX_LINES) else lines
        pushAll(visible.map { line ->
            Entry(Channel.SERVER, "$C_BLUE${AnsiPattern.replace(line, "")}$C_RESET")
        })
    }

    /**
     * Re-reads the Node process's lines from the shared file. The runner lives in
     * the :node process, so its lines are written to disk by that process and would
     * otherwise never reach this one; every other line in the file was written here
     * and is already in the buffer, so only the Node ones are taken.
     */
    fun pollNodeLog() {
        val file = appLogFile ?: return
        val length = try {
            file.length()
        } catch (_: Exception) {
            return
        }
        if (length == appLogLength) return
        appLogLength = length
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return
        }
        if (lines.size < appLogLinesRead) appLogLinesRead = 0
        val nodeEntries = (appLogLinesRead until lines.size).mapNotNull { i ->
            val channel = channelForTag(PersistedTag.find(lines[i])?.groupValues?.get(1).orEmpty())
            if (channel == Channel.NODE) {
                Entry(Channel.NODE, "$C_BLUE${lines[i]}$C_RESET")
            } else {
                null
            }
        }
        pushAll(nodeEntries)
        appLogLinesRead = lines.size
    }

    /** True clear: reset in-memory buffer, truncate both log files and reset
     *  the incremental cursors, so old logs don't reappear after a restart. */
    fun clear(ctx: android.content.Context) {
        synchronized(_entries) {
            _entries.value = listOf(Entry(Channel.APP, "${C_GRAY}${clock.format(Date())}  已清空$C_RESET"))
        }
        try {
            File(ctx.filesDir, APP_LOG).writeText("")
        } catch (_: Exception) {
        }
        try {
            File(ctx.filesDir, "tavern-server.log").writeText("")
        } catch (_: Exception) {
        }
        serverLogOffset = -1L
        appLogLinesRead = 0
        appLogLength = 0L
    }
}
