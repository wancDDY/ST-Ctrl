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

    /**
     * Whether the tavern page is the surface in front. A backup stops Node to copy a
     * still tree, which would tear down the session the page is holding; the flag lets
     * the automatic backup wait for a moment when the console is in front instead.
     */
    @Volatile
    var tavernPageOpen: Boolean = false

    /**
     * Whether an activity is in front. Android 12+ refuses to start a foreground
     * service from the background, so a backup that stops Node and finishes after the
     * user switched away cannot bring it back — the service stays down and the next
     * entry to the app reads as a cold boot. Work that stops the service checks this.
     */
    @Volatile
    var appInForeground: Boolean = false
}
