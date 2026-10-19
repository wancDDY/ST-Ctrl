package com.tavern.app.log

import android.content.Context
import java.io.File

/**
 * Boot tracing that survives a start which never finishes.
 *
 * The console page that shows the app log sits behind the very entry screen that fails to
 * appear, and MIUI drops release-build logcat, so a boot that stops half way leaves nothing
 * to read. This file lands in the app's external files directory, which adb can read:
 *
 *     adb shell cat /sdcard/Android/data/com.stctrl.mobile/files/boot-trace.log
 *
 * It is append-only and never throws: tracing must not be the reason a start fails.
 */
object BootTrace {

    private const val FILE_NAME = "boot-trace.log"

    fun log(context: Context, message: String) {
        try {
            val directory = context.getExternalFilesDir(null) ?: return
            if (!directory.exists() && !directory.mkdirs()) {
                return
            }
            File(directory, FILE_NAME).appendText("${System.currentTimeMillis()} $message\n")
        } catch (_: Exception) {
            // Ignored on purpose.
        }
    }

    fun reset(context: Context) {
        try {
            val directory = context.getExternalFilesDir(null) ?: return
            File(directory, FILE_NAME).writeText("")
        } catch (_: Exception) {
            // Ignored on purpose.
        }
    }
}
