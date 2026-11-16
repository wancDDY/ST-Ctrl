package com.tavern.app.log

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

/**
 * Copies the prompt-reuse reports the core writes into its own stdout file out to a place a host
 * tool can read.
 *
 * The core's console output is tee'd to `files/tavern-server.log`, which is private storage; the
 * app reads it for the log screen only while that screen is open, so a measurement run would
 * leave no record unless someone watched the page. Each `[stctrl-cache]` line is appended to the
 * external files directory instead, where `adb` can pull it after a run.
 *
 * The tail reads only bytes added since the previous poll. A file that shrank (a new session
 * truncated it) resets the cursor rather than skipping ahead.
 */
object CacheTraceTailer {
    private const val SERVER_LOG = "tavern-server.log"
    private const val TRACE_FILE = "cache-trace.log"
    private const val TRACE_TAG = "[stctrl-cache]"
    private const val POLL_INTERVAL_MS = 2000L
    private const val TRACE_MAX_BYTES = 256L * 1024L
    private const val TRACE_KEEP_LINES = 400
    private const val SERVER_LOG_TAIL_BYTES = 2L * 1024L * 1024L

    fun start(scope: CoroutineScope, context: Context) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            var readOffset = -1L
            while (isActive) {
                runCatching { tailOnce(appContext, readOffset) }
                    .onSuccess { readOffset = it }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun tailOnce(context: Context, readOffset: Long): Long {
        val source = File(context.filesDir, SERVER_LOG)
        if (!source.exists()) {
            return readOffset
        }
        val length = source.length()
        val firstRead = readOffset < 0L || length < readOffset
        val start = if (firstRead) {
            (length - SERVER_LOG_TAIL_BYTES).coerceAtLeast(0L)
        } else {
            readOffset
        }
        if (start == length) {
            return readOffset
        }
        val chunk = try {
            val bytes = ByteArray((length - start).toInt())
            RandomAccessFile(source, "r").use { file ->
                file.seek(start)
                file.readFully(bytes)
            }
            bytes
        } catch (_: Exception) {
            return readOffset
        }
        val lastNewline = chunk.indexOfLast { it == '\n'.code.toByte() }
        if (lastNewline < 0) {
            return readOffset
        }
        var text = String(chunk, 0, lastNewline, Charsets.UTF_8)
        if (firstRead && start > 0L) {
            text = text.substringAfter('\n', "")
        }
        val target = context.getExternalFilesDir(null)?.let { File(it, TRACE_FILE) }
        if (target != null) {
            text.lineSequence().forEach { line ->
                if (line.contains(TRACE_TAG)) {
                    appendTrace(target, line)
                }
            }
        }
        return start + lastNewline + 1
    }

    private fun appendTrace(target: File, line: String) {
        if (target.length() > TRACE_MAX_BYTES) {
            val kept = target.readLines().takeLast(TRACE_KEEP_LINES)
            target.writeText(kept.joinToString("\n", postfix = "\n"))
        }
        target.appendText("$line\n")
    }
}
