package com.tavern.app.console

/**
 * One operation the console reports in the top bar while it runs.
 *
 * The task island is the only surface that outlives the page that started the work, so every
 * operation that keeps running after its screen is gone has to be listed here. [kind] is what a
 * tap routes on, and the constants below are the same strings the completion notice tracks, so
 * a new task has to be added in both places.
 *
 * [label] has to survive a slot that is roughly a dozen characters wide, so it carries what
 * identifies the task and nothing else; [detail] holds the rest and is shown where there is
 * room for it.
 */
data class ConsoleTask(
    val kind: String,
    val label: String,
    val detail: String? = null,
) {
    companion object {
        const val BACKUP = "backup"
        const val RESTORE = "restore"
        const val CORE_UPDATE = "core_update"
        const val EXTENSION_INSTALL = "extension_install"
        const val MEMORY_SCAN = "memory_scan"
        const val MEMORY = "memory"
    }
}
