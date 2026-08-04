package com.tavern.app

import android.annotation.SuppressLint
import android.content.Context

@SuppressLint("StaticFieldLeak")
object ApplicationState {
    @Volatile
    var ctx: Context? = null

    /** LAN IP detected at startup — empty if not on WiFi */
    @Volatile
    var lanIp: String = ""

    /** Current LAN access token — regenerated on each cold start and manual refresh */
    @Volatile
    var lanToken: String = ""

    /** Whether a LAN client has been authenticated in this session */
    @Volatile
    var lanSessionActive: Boolean = false
}
