package com.tavern.app.console

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The task island lives on the home tab but the work it reports can belong to anywhere else in
 * the console. Tapping it navigates to that screen, and this carries what the screen needs in
 * order to arrive where the work is: which sub-tab of the extensions hub was meant, and — when
 * the task belongs to a single row of that sub-tab — which row.
 *
 * The two are kept apart on purpose. The hub owns the sub-tab and consumes it as soon as it has
 * switched; the row cannot be resolved before that sub-tab has loaded its own list, so the two
 * are consumed at different times by different screens.
 */
object ExtensionsTabRequest {

    /** The memory screen is the third sub-tab of the extensions hub. */
    const val MEMORY_TAB = 2

    private val _tab = MutableStateFlow<Int?>(null)
    val tab: StateFlow<Int?> = _tab

    private val _scopeKey = MutableStateFlow<String?>(null)
    val scopeKey: StateFlow<String?> = _scopeKey

    fun request(index: Int) {
        _tab.value = index
    }

    /**
     * The memory island names one chat, so landing on the library of every chat drops what the
     * reader tapped for.
     */
    fun requestMemoryScope(scopeKey: String) {
        _tab.value = MEMORY_TAB
        _scopeKey.value = scopeKey
    }

    fun consume() {
        _tab.value = null
    }

    fun consumeScopeKey() {
        _scopeKey.value = null
    }
}
