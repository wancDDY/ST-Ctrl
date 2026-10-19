package com.tavern.app.node

import java.io.Reader

/**
 * The decision behind the health probe, kept away from [NodeRunner] so it can be tested without
 * the native library: the probe's answer is what the console shows while an update or a cancel
 * finishes, and getting it wrong made a live core look dead for a whole timeout.
 */
internal object NodeHealth {

    /**
     * SillyTavern answers an unauthenticated POST with 403 and the words "Invalid CSRF token",
     * but where those words sit depends on how the response is rendered: the JSON form has them
     * on the first line, the HTML error page has them several lines down after the doctype.
     * Reading one line therefore found them only for the JSON form, and on devices that got the
     * HTML page a perfectly healthy core looked dead for the whole startup timeout.
     */
    fun isHealthyResponse(status: Int, body: String): Boolean {
        if (status in 200..399) return true
        // 403/419 from this port is the tavern refusing an unauthenticated POST.
        return (status == 403 || status == 419) && body.contains("CSRF", ignoreCase = true)
    }

    /** Reads the head of a response body; the marker is looked for in text, not in one line. */
    fun readHead(reader: Reader): String {
        val buffer = CharArray(4096)
        val read = try {
            reader.read(buffer, 0, buffer.size)
        } catch (_: Exception) {
            -1
        }
        return if (read > 0) String(buffer, 0, read) else ""
    }
}
