package com.tavern.app.util

import android.content.Context
import android.os.SystemClock
import android.widget.Toast

object AppToast {
    private const val DEDUP_WINDOW_MS = 2_000L

    private var lastMessage: String? = null
    private var lastShownAt = 0L
    private var current: Toast? = null

    fun show(context: Context, message: CharSequence?, duration: Int = Toast.LENGTH_SHORT) {
        val text = message?.toString().orEmpty()
        if (text.isBlank()) return

        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (text == lastMessage && now - lastShownAt < DEDUP_WINDOW_MS) return

            lastMessage = text
            lastShownAt = now
            current?.cancel()
            current = Toast.makeText(context.applicationContext, text, duration).also { it.show() }
        }
    }
}
