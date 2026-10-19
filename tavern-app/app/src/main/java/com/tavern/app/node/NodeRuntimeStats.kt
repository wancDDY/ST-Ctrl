package com.tavern.app.node

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Live stats for the embedded Node process. Node runs in its own ":node"
 * process, so these numbers cannot come from the current process's runtime.
 */
object NodeRuntimeStats {

    private const val NODE_PROCESS_SUFFIX = ":node"
    private val PidPattern = Regex("\\d+")

    /**
     * Memory used by the Node process in kilobytes, or null when the process is
     * absent (service stopped).
     *
     * The pid comes from ActivityManager first. Scanning /proc for a process
     * whose cmdline ends in ":node" used to be the only way, but newer Android
     * releases stop handing out other processes' /proc entries, and a scan that
     * finds nothing reads as "service stopped" while Node is running — on the
     * device that reported this, the memory tile stayed blank all session.
     *
     * VmRSS is preferred so the figure keeps the meaning it had. Where /proc is
     * unreadable the framework's PSS is reported instead: a different metric
     * (shared pages counted once, so a lower number), but a real one, and the
     * alternative is showing nothing.
     */
    fun readNodeMemoryKb(context: Context, processSuffix: String = NODE_PROCESS_SUFFIX): Long? {
        val pid = findNodePid(context, processSuffix) ?: return null
        return readProcRssKb(pid) ?: readFrameworkPssKb(context, pid)
    }

    /**
     * True while the ":node" process exists, whether or not it serves yet. The
     * port only answers after Node has loaded SillyTavern, so the process
     * appearing is the earliest fact a start-up wait can report.
     */
    fun isNodeProcessRunning(context: Context, processSuffix: String = NODE_PROCESS_SUFFIX): Boolean =
        findNodePid(context, processSuffix) != null

    private fun findNodePid(context: Context, suffix: String): Int? =
        findNodePidByActivityManager(context, suffix) ?: findNodePidByProcScan(suffix)

    private fun findNodePidByActivityManager(context: Context, suffix: String): Int? = runCatching {
        activityManager(context)?.runningAppProcesses
            ?.firstOrNull { it.processName?.endsWith(suffix) == true }
            ?.pid
    }.getOrNull()

    private fun findNodePidByProcScan(suffix: String): Int? {
        val entries = runCatching { File("/proc").listFiles() }.getOrNull() ?: return null
        for (entry in entries) {
            if (!entry.isDirectory || !PidPattern.matches(entry.name)) continue
            // /proc/<pid>/cmdline is NUL separated; matching on the suffix
            // avoids depending on the applicationId staying constant.
            val raw = runCatching { File(entry, "cmdline").readText() }.getOrNull() ?: continue
            if (raw.replace('\u0000', ' ').contains(suffix)) {
                return entry.name.toIntOrNull()
            }
        }
        return null
    }

    private fun readProcRssKb(pid: Int): Long? {
        val text = runCatching { File("/proc/$pid/status").readText() }.getOrNull() ?: return null
        text.lineSequence().forEach { line ->
            if (line.startsWith("VmRSS:")) {
                return line.filter { it.isDigit() }.toLongOrNull()
            }
        }
        return null
    }

    private fun readFrameworkPssKb(context: Context, pid: Int): Long? = runCatching {
        activityManager(context)
            ?.getProcessMemoryInfo(intArrayOf(pid))
            ?.firstOrNull()
            ?.totalPss
            ?.toLong()
            ?.takeIf { it > 0 }
    }.getOrNull()

    private fun activityManager(context: Context): ActivityManager? =
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
}
