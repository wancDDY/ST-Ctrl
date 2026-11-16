package com.tavern.app.console

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tavern.app.node.NodeState

/**
 * A memory pass runs for minutes, and the reader is free to walk away from the page while it
 * works. Its state therefore lives here rather than in a composable: leaving the screen must not
 * cancel the pass, and the console's task island reads the same flow so progress stays visible
 * from anywhere in the app.
 */
/**
 * The identity of one chat.
 *
 * The tavern files a chat under a name inside a character's folder, so a name on its own does not
 * identify anything: two characters can both own a chat of the same name. Anything keyed by chat —
 * a running task, a pass read back from the service, a row in the list — has to carry both halves,
 * or one character's work shows up as another's.
 */
fun memoryChatKey(characterId: String, chatId: String): String = characterId + "/" + chatId

object MemoryTaskState {
    /** Scope key for a dispatch that was not narrowed to one chat. */
    const val ALL_CHATS = "all"

    data class Running(
        /**
         * Identifies the chat the pass belongs to. The task island needs it to send the reader
         * back to that chat rather than to the list of every chat with memory.
         */
        val scopeKey: String,
        val chatTitle: String,
        /**
         * One line, already worded for the island: which batch and which messages it is reading.
         * A pass over a long chat is minutes of model calls, and a bare "running" line cannot
         * tell a slow batch from a stuck one.
         */
        val detail: String,
    )

    data class Finished(
        val scopeKey: String,
        val chatTitle: String,
        val success: Boolean,
        val text: String,
        /** When it ended, so a result left over from an earlier visit is not reported as news. */
        val at: Long = System.currentTimeMillis(),
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
        run: suspend (onProgress: (detail: String) -> Unit) -> Finished,
    ) {
        if (_running.value != null) return
        _running.value = Running(scopeKey, chatTitle, "正在读取…")
        _finished.value = null
        worker.launch {
            val outcome = try {
                run { detail ->
                    _running.value = Running(scopeKey, chatTitle, detail)
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
 * One dispatch with the batch line kept up to date.
 *
 * The request blocks for as long as the model takes and carries no progress of its own, so the
 * batch is read back from the state the engine writes while it waits. Shared by the page and the
 * unattended dispatch: two copies of this loop would word the same batch differently.
 */
suspend fun runMemoryDispatch(
    port: Int,
    chatId: String?,
    /**
     * The character the chat belongs to. Two characters can own a chat of the same name, and the
     * runtime refuses a name it cannot resolve instead of picking one.
     */
    characterId: String = "",
    maxChats: Int? = null,
    maxRounds: Int = 40,
    deadlineMs: Long = 20 * 60_000L,
    /**
     * Whether rows that predate the current prompt may be repaired on this pass.
     *
     * A repair reads nothing new and costs a call, so it belongs to a pass a person asked for.
     * The unattended one shares this function and must pass false, or an archive the reader has
     * not opened in months gets billed on a schedule they never agreed to.
     */
    maintain: Boolean = true,
    /**
     * Try even though the chat is waiting out a failure. True for the dispatch a person started —
     * pressing the button is deciding to spend a call — and false for the scheduled one, which
     * would otherwise pay for the same refused batch every couple of minutes.
     */
    force: Boolean = true,
    onProgress: (String) -> Unit,
): AgentClient.MemoryRunReport? = coroutineScope {
    onProgress("正在准备…")
    val poll = launch {
        while (isActive) {
            val snapshot = withContext(Dispatchers.IO) { AgentClient.memoryState(port) }
            val progress = if (chatId != null) {
                snapshot?.chats?.firstOrNull {
                    it.chatId == chatId && (characterId.isBlank() || it.characterId == characterId)
                }?.progress
            } else {
                snapshot?.running
            }
            onProgress(progress?.describe() ?: "正在准备…")
            delay(1_500L)
        }
    }
    // One request stops after a few batches by design. Asking for the next round here is that
    // decision made once instead of pressing the button again for every three batches: it keeps
    // going until the chat is caught up, the provider fails, a round moves nothing at all, or the
    // budget runs out.
    val startedAt = System.currentTimeMillis()
    var rounds = 0
    var processed = 0
    var failed = 0
    var remaining = 0
    var last: AgentClient.MemoryRunReport? = null
    while (rounds < maxRounds && System.currentTimeMillis() - startedAt < deadlineMs) {
        // Pressing the button is the reader deciding to spend a call; a chat with nothing new but
        // rows written by an older prompt is exactly what a maintenance run is for.
        val report = AgentClient.memoryRun(
            port,
            chatId,
            maxChats,
            maintain = maintain,
            characterId = characterId,
            force = force,
        ) ?: break
        last = report
        rounds += 1
        processed += report.processed
        failed = maxOf(failed, report.failed)
        remaining = report.remaining
        if (report.failed > 0) break
        if (report.remaining <= 0) break
        // Nothing written and nothing skipped in a round means the next one would repeat it.
        if (report.processed == 0 && report.skipped == 0) break
        onProgress("还剩 $remaining 条，接着读第 ${rounds + 1} 轮")
    }
    poll.cancel()
    last?.copy(processed = processed, failed = failed, remaining = remaining)
}

/**
 * How a failed pass is reported. Creating a backup and updating the core both stop Node on
 * purpose, and that takes a running pass with it; the raw failure would read as a fault in the
 * pass itself.
 */
fun memoryTaskFailureText(serviceStopped: Boolean, message: String?): String =
    if (serviceStopped) {
        "核心服务已停止（创建备份或更新核心时会这样），本次整理已中断；服务恢复后可以再整理。"
    } else {
        message ?: "整理未完成"
    }

/**
 * Whether creating a backup has to be confirmed first. A backup now runs beside the core, so a
 * memory pass no longer blocks it.
 */
