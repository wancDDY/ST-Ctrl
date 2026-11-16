package com.tavern.app.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State shared between the backup worker, the console top bar and the keep-alive paths.
 *
 * A backup reads the data tree while the core keeps running, so the only thing the recovery
 * paths need to know is that a copy or a restore owns the tree: the heartbeat and the keep-alive
 * alarm must not start a restore against a directory being written, and a second backup must
 * not race the first.
 */
object BackupState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** What the copy is doing right now, shown next to the island label. */
    private val _detail = MutableStateFlow<String?>(null)
    val detail: StateFlow<String?> = _detail.asStateFlow()

    /** A due backup that the reader asked to be reminded about instead of run. */
    private val _reminder = MutableStateFlow<String?>(null)
    val reminder: StateFlow<String?> = _reminder.asStateFlow()

    private val _current = MutableStateFlow(0)
    val current: StateFlow<Int> = _current.asStateFlow()

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _startedAt = MutableStateFlow(System.currentTimeMillis())
    val startedAt: StateFlow<Long> = _startedAt.asStateFlow()

    /** Read from the recovery paths, which do not collect flows. */
    @Volatile
    var inProgress: Boolean = false
        private set

    @Volatile
    var restoreInProgress: Boolean = false
        private set

    val isDataOperationInProgress: Boolean
        get() = inProgress || restoreInProgress

    fun begin(context: String? = null) {
        inProgress = true
        _running.value = true
        _detail.value = context
        _current.value = 0
        _total.value = 0
        _log.value = emptyList()
        _startedAt.value = System.currentTimeMillis()
    }

    fun setDetail(context: String?) {
        if (inProgress) _detail.value = context
    }

    fun updateProgress(current: Int, total: Int, context: String? = null) {
        _current.value = current.coerceAtLeast(0)
        _total.value = total.coerceAtLeast(0)
        if (context != null) _detail.value = context
    }

    fun appendLog(line: String) {
        if (line.isBlank()) return
        _log.value = (_log.value + line).takeLast(200)
    }

    fun end() {
        inProgress = false
        _running.value = false
        _detail.value = null
    }

    fun beginRestore() {
        restoreInProgress = true
    }

    fun endRestore() {
        restoreInProgress = false
    }

    fun postReminder(text: String) {
        _reminder.value = text
    }

    fun clearReminder() {
        _reminder.value = null
    }
}
