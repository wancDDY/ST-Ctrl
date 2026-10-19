package com.tavern.app.util

object FormatUtils {
    fun fileSize(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes == 0L) return "0 B"
        // Decimal steps, the ones Android's own storage screen and file managers use.
        // The old 1024 steps reported a 352 MB backup as 336 MB, which reads as the
        // app measuring something other than the file's real footprint.
        val units = arrayOf("B", "kB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1000 && unitIndex < units.size - 1) {
            value /= 1000
            unitIndex++
        }
        return "%.1f %s".format(value, units[unitIndex])
    }
}
