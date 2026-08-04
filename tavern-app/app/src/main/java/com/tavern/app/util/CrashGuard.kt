package com.tavern.app.util

import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the last JVM crash report to filesDir/last-crash.txt on uncaught exceptions.
 * Install once in Application.onCreate() — after that every fatal throwable leaves a
 * timestamped report with device info and the full Kotlin/Java stack trace.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val REPORT_FILE = "last-crash.txt"

    fun install(appFilesDir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val whenStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val stack = StringWriter().also { PrintWriter(it).use { pw -> throwable.printStackTrace(pw) } }
                val report = buildString {
                    appendLine("when = $whenStr")
                    appendLine("thread = ${thread.name} (id=${thread.id})")
                    appendLine("device = ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("android = ${Build.VERSION.RELEASE} (sdk=${Build.VERSION.SDK_INT})")
                    appendLine()
                    appendLine(stack)
                }
                File(appFilesDir, REPORT_FILE).writeText(report)
                Log.e(TAG, "Crash captured to $REPORT_FILE", throwable)
            } catch (_: Throwable) { /* must not throw */ }
            // Delegate to previous handler or kill
            if (previous != null && previous !== Thread.getDefaultUncaughtExceptionHandler()) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }
}
