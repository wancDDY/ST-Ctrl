package com.tavern.app.console

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.tavern.app.node.NodeState

/**
 * A memory pass runs for minutes, and the reader is free to walk away from the page while it
 * works. Its state therefore lives here rather than in a composable: leaving the screen must not
 * cancel the pass, and the console's task island reads the same flow so progress stays visible
 * from anywhere in the app.
 */
object MemoryTaskState {
    data class Running(
        /**
         * Identifies the chat the pass belongs to. The task island needs it to send the reader
         * back to that chat rather than to the list of every chat with memory.
         */
        val scopeKey: String,
        val chatTitle: String,
        val batches: Int,
        val processed: Int,
    )

    data class Finished(
        val scopeKey: String,
        val chatTitle: String,
        val success: Boolean,
        val text: String,
    )

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running

    private val _finished = MutableStateFlow<Finished?>(null)
    val finished: StateFlow<Finished?> = _finished

    // Deliberately not tied to a composable's scope: navigating away is the normal case, not a
    // reason to abandon work the reader asked for.
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val busy: Boolean get() = _running.value != null

    /**
     * [run] receives a progress callback and returns how the pass ended. A second request while
     * one is in flight is ignored rather than queued — the engine advances one cursor per chat, so
     * two passes would fight over it.
     */
    fun start(
        scopeKey: String,
        chatTitle: String,
        run: suspend (onProgress: (batches: Int, processed: Int) -> Unit) -> Finished,
    ) {
        if (_running.value != null) return
        _running.value = Running(scopeKey, chatTitle, 0, 0)
        _finished.value = null
        worker.launch {
            val outcome = try {
                run { batches, processed ->
                    _running.value = Running(scopeKey, chatTitle, batches, processed)
                }
            } catch (exception: Exception) {
                // Creating a backup and updating the core both stop Node on purpose, and that takes
                // a running pass with it. Reporting the raw failure would read as a fault in the
                // pass itself, so the service state decides what this message says.
                val serviceStopped = NodeState.state.value != NodeState.State.RUNNING
                Finished(
                    scopeKey,
                    chatTitle,
                    false,
                    memoryTaskFailureText(serviceStopped, exception.message),
                )
            } finally {
                _running.value = null
            }
            _finished.value = outcome
        }
    }

    fun clearFinished() {
        _finished.value = null
    }
}

/**
 * Progress line for a running pass. A batch is one model call and can take a minute, so counting
 * from zero reads as nothing happening for exactly as long as the model is thinking; the first
 * batch says what it is doing instead.
 */
fun memoryTaskProgressText(batches: Int, processed: Int): String =
    if (batches == 0) "正在整理第 1 批…" else "$batches 批，$processed 条"

/**
 * How a failed pass is reported. Creating a backup and updating the core both stop Node on
 * purpose, and that takes a running pass with it; the raw failure would read as a fault in the
 * pass itself.
 */
fun memoryTaskFailureText(serviceStopped: Boolean, message: String?): String =
    if (serviceStopped) {
        "核心服务已停止（创建备份或更新核心时会这样），本次整理已中断；服务恢复后可以重新整理，已写入的记忆保留。"
    } else {
        message ?: "整理失败"
    }

/**
 * Whether creating a backup has to be confirmed first. A backup stops the core service, which
 * takes a running memory pass with it, so the reader is asked before that happens rather than
 * told afterwards. Collected into one place so the condition is testable without a device.
 */
fun memoryTaskBlocksBackup(taskRunning: Boolean, scanRunning: Boolean): Boolean =
    taskRunning || scanRunning
