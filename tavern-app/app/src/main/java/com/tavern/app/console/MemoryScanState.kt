package com.tavern.app.console

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * State for the run that indexes chats predating the memory switch.
 *
 * It pages through every chat and can take minutes, so it lives here rather than in the memory
 * screen: switching hub sub-tabs used to cancel the run without a word — the same defect the
 * install path had — and the console's task island reads this flow to report it from anywhere.
 *
 * [Running.text] is what the progress dialog shows; it carries counts and sometimes a chat name,
 * so it is a whole line rather than a fraction.
 */
object MemoryScanState {

    data class Running(val text: String)

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result

    // Not tied to a composable's scope: leaving the screen is not a reason to abandon indexing
    // the reader asked for.
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val busy: Boolean get() = _running.value != null

    /**
     * [run] reports progress and returns the sentence the result dialog shows. It is expected to
     * handle its own failures and describe them in that sentence: what counts as a failure is a
     * property of the paging, not of this holder.
     */
    fun start(run: suspend (onProgress: (String) -> Unit) -> String) {
        if (_running.value != null) return
        _running.value = Running("正在收录旧聊天")
        _result.value = null
        worker.launch {
            val text = try {
                run { text -> _running.value = Running(text) }
            } catch (exception: Exception) {
                "收录失败：${exception.message ?: "未知错误"}"
            } finally {
                _running.value = null
            }
            _result.value = text
        }
    }

    fun clearResult() {
        _result.value = null
    }
}
