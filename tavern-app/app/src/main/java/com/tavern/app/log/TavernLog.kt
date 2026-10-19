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

    private fun push(entry: Entry) {
        synchronized(_entries) {
            val current = _entries.value.toMutableList()
            current.add(entry)
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
        val clean = msg.replace(Regex("\u001b\\[[0-9;]*m"), "")
        push(Entry(Channel.SERVER, "$C_BLUE$clean$C_RESET"))
    }

    // Line count tracking for incremental reads (not RandomAccessFile)
    @Volatile var serverLogLines: Int = 0

    // Skips re-reading the server log while nothing has been appended to it: the
    // tail runs every few seconds for as long as the screen is open, and the file
    // is the tavern's whole stdout.
    private var serverLogLength: Long = -1L

    fun pollServerLog(ctx: android.content.Context) {
        val logFile = File(ctx.filesDir, "tavern-server.log")
        if (!logFile.exists()) return
        val length = logFile.length()
        if (length == serverLogLength) return
        serverLogLength = length
        try {
            val all = mutableListOf<String>()
            logFile.bufferedReader().use { br ->
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
        } catch (_: Exception) {
        }
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
        for (i in appLogLinesRead until lines.size) {
            val channel = channelForTag(PersistedTag.find(lines[i])?.groupValues?.get(1).orEmpty())
            if (channel == Channel.NODE) push(Entry(Channel.NODE, "$C_BLUE${lines[i]}$C_RESET"))
        }
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
        serverLogLines = 0
        serverLogLength = 0L
        appLogLinesRead = 0
        appLogLength = 0L
    }
}
