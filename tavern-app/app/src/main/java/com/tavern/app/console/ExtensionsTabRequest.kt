package com.tavern.app.console

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The task island lives on the home tab but the work it reports can belong to anywhere else in
 * the console. Tapping it navigates to that screen, and this carries what the screen needs in
 * order to arrive where the work is: which sub-tab of the extensions hub was meant.
 */
object ExtensionsTabRequest {

    /** Landing on the extensions hub root again. */
    const val RESET_TAB = -1

    /** The memory screen is the third sub-tab of the extensions hub. */
    const val MEMORY_TAB = 2

    private val _tab = MutableStateFlow<Int?>(null)
    val tab: StateFlow<Int?> = _tab

    fun request(index: Int) {
        _tab.value = index
    }

    fun consume() {
        _tab.value = null
    }

}
