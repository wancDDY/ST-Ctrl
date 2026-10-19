package com.tavern.app.log

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Copies the prompt-reuse reports the core writes into its own stdout file out to a place a host
 * tool can read.
 *
 * The core's console output is tee'd to `files/tavern-server.log`, which is private storage; the
 * app reads it for the log screen only while that screen is open, so a measurement run would
 * leave no record unless someone watched the page. Each `[stctrl-cache]` line is appended to the
 * external files directory instead, where `adb` can pull it after a run.
 *
 * The tail only ever appends: the cursor is a byte length, and a file that shrank (a new session
 * truncated it) resets the cursor rather than skipping ahead.
 */
object CacheTraceTailer {
    private const val SERVER_LOG = "tavern-server.log"
    private const val TRACE_FILE = "cache-trace.log"
    private const val TRACE_TAG = "[stctrl-cache]"
    private const val POLL_INTERVAL_MS = 2000L
    private const val TRACE_MAX_BYTES = 256L * 1024L
    private const val TRACE_KEEP_LINES = 400

    fun start(scope: CoroutineScope, context: Context) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            var readLength = 0L
            var seenLines = 0
            while (isActive) {
                runCatching { tailOnce(appContext, readLength, seenLines) }
                    .onSuccess { cursor ->
                        readLength = cursor.first
                        seenLines = cursor.second
                    }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun tailOnce(context: Context, readLength: Long, seenLines: Int): Pair<Long, Int> {
        val source = File(context.filesDir, SERVER_LOG)
        if (!source.exists()) {
            return readLength to seenLines
        }
        val length = source.length()
        if (length == readLength) {
            return readLength to seenLines
        }
        // Read the whole file: the log is small next to the core, and a byte-accurate incremental
        // read would have to deal with partial UTF-8 sequences at the boundary.
        val lines = source.readLines()
        val cursor = if (lines.size < seenLines) 0 else seenLines
        var next = lines.size
        val target = context.getExternalFilesDir(null)?.let { File(it, TRACE_FILE) }
        if (target != null) {
            for (index in cursor until lines.size) {
                val line = lines[index]
                if (line.contains(TRACE_TAG)) {
                    appendTrace(target, line)
                }
            }
        } else {
            next = cursor
        }
        return length to next
    }

    private fun appendTrace(target: File, line: String) {
        if (target.length() > TRACE_MAX_BYTES) {
            val kept = target.readLines().takeLast(TRACE_KEEP_LINES)
            target.writeText(kept.joinToString("\n", postfix = "\n"))
        }
        target.appendText("$line\n")
    }
}
