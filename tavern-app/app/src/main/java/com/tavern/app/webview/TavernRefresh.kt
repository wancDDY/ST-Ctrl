package com.tavern.app.webview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Asks for the tavern page to be reloaded after an operation changed data the running
 * page already read (a deleted character card, a written world book, a new extension).
 * The console observes this and reloads the WebView in the background, so reopening the
 * tavern shows the new state instead of a stale page the user has to refresh by hand.
 */
object TavernRefresh {

    private val _requests = MutableStateFlow(0L)
    val requests: StateFlow<Long> = _requests.asStateFlow()

    fun request() {
        _requests.value += 1
    }
}
