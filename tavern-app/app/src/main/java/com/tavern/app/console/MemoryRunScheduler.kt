package com.tavern.app.console

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dispatches the per-chat memory pass on its own.
 *
 * The reader should not have to open a page to keep memory up to date, but every pass is a paid
 * model call, so the trigger is the size of the backlog rather than the clock: a chat that has
 * accumulated the number of floors the reader chose gets consolidated the next time the console is
 * idle. [INTERVAL_MS] only stops a long backlog from re-dispatching in a tight loop.
 */
/**
 * Whether an unattended pass may start right now.
 *
 * Blocked in exactly one place: the tavern page while the app is in front. There the reader is
 * waiting on their own reply from the same provider, and a consolidation call would queue ahead
 * of it. The same page with the app put away is fine — that is when a session's backlog actually
 * gets worked off — and so is the console. Holding it back on "webview" alone meant a reader who
 * lives in the chat never had a pass run at all.
 */
internal fun shouldDispatchMemoryNow(screen: String, appInForeground: Boolean): Boolean =
    !(screen == "webview" && appInForeground)

/**
 * A chat counts as "being read" this long after a generation went to it. Past that it is an
 * archive: the backlog is real, but nobody is waiting on it, and consolidating it on a schedule
 * bills for a conversation the reader has walked away from.
 */
internal const val MEMORY_ACTIVE_WINDOW_MS = 30L * 60 * 1000

/**
 * Which chat an unattended pass should read, or null when none is due.
 *
 * One rule, stated once: something was read in the last half hour, that same chat is behind by at
 * least the number of floors the reader chose, and the reader has not switched it off. A chat with
 * its switch off is not "behind" — the backlog is deliberate, and dispatching it spends a call to
 * be told nothing happened.
 */
internal fun scheduledChat(
    state: AgentClient.ChatMemoryState,
    threshold: Int,
    now: Long,
): AgentClient.ChatMemory? {
    if (threshold <= 0) return null
    if (now - state.lastInjectedAt > MEMORY_ACTIVE_WINDOW_MS) return null
    val open = state.lastInjectedChat.removeSuffix(".jsonl").ifBlank { null } ?: return null
    // A chat is named by its character as well, and the tavern reuses date-based names. Matching
    // on the name alone would spend the pass on the other character's chat of the same name.
    val openCharacter = state.lastInjectedCharacter
    return state.chats.firstOrNull { chat ->
        chat.chatId == open
            && (openCharacter.isBlank() || chat.characterId == openCharacter)
            && !chat.disabled
            && chat.pending >= threshold
    }
}

object MemoryRunScheduler {
    private const val TAG = "MemoryRun"
    private const val PREFS = "memory_run"
    private const val KEY_LAST_RUN = "last_run"
    private const val KEY_INTERVAL = "interval"
    // The gap between passes, not the trigger. One pass can only work through part of a long
    // backlog, so the number recorded alongside the run decides when the next one may start.
    private const val INTERVAL_MS = 10 * 60 * 1000L
    // After a pass that left messages behind, come back quickly instead of sitting out the full
    // interval: the threshold said "keep up", and waiting ten minutes with thirteen floors
    // still showing is what makes the switch look broken.
    private const val CATCH_UP_MS = 2 * 60 * 1000L

    fun isDue(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_RUN, 0L)
        val interval = prefs.getLong(KEY_INTERVAL, INTERVAL_MS).coerceAtLeast(CATCH_UP_MS)
        return System.currentTimeMillis() - last >= interval
    }

    /**
     * Runs one dispatch and reports how many chats advanced. Returns null when it did not run:
     * the switch is off, it is not due yet, or the runtime is not reachable.
     */
    suspend fun maybeRun(context: Context): Int? {
        if (!SettingsState.chatMemoryEnabled()) return null
        // A pass is at least one paid model call per chat, so it waits until a chat has piled up
        // the backlog the reader chose to pay for. Zero keeps it manual.
        val threshold = SettingsState.chatMemoryPendingThreshold()
        if (threshold <= 0) return null
        // Local-only mode has no model to call: a dispatch would be refused by the runtime.
        if (!SettingsState.memoryAgentTakeover()) return null
        if (!isDue(context)) return null
        if (MemoryTaskState.busy) return null
        val port = AgentClient.findPort() ?: return null
        val snapshot = AgentClient.memoryState(port) ?: return null
        // The chat a generation last went to, and only while it is still the one being read.
        val candidate = scheduledChat(snapshot, threshold, System.currentTimeMillis()) ?: return null
        // Recorded before the pass starts: a broken provider must not make the next idle tick
        // dispatch again while this one is still running.
        markRun(context)
        // Through the shared state, so an unattended pass shows on the task island exactly like
        // the one the reader starts by hand.
        val title = candidate.characterId.ifBlank { candidate.chatId }
        MemoryTaskState.start(
            memoryChatKey(candidate.characterId, candidate.chatId),
            title,
        ) { onProgress ->
            // One chat, a few rounds, one pass: enough to stay current, bounded so an idle console
            // cannot turn into a running bill.
            val report = runMemoryDispatch(
                port = port,
                chatId = candidate.chatId,
                // The tavern names chat files by date, so a name alone can belong to two
                // characters; the runtime refuses an ambiguous one rather than reading a stranger's
                // archive.
                characterId = candidate.characterId,
                maxChats = null,
                maxRounds = 3,
                deadlineMs = 6 * 60_000L,
                // Nothing here is a person deciding anything: a chat waiting out a failure is
                // waiting on purpose, and a scheduled pass that pushed past the wait would pay for
                // the same refused batch every couple of minutes.
                force = false,
                // No repair on a schedule: the rows a repair rewrites are old ones, and the reader
                // never asked for those to be touched — let alone paid for — while they were away.
                maintain = false,
                onProgress = onProgress,
            )
            if (report != null && report.failed > 0) {
                Log.w(TAG, "pass finished with ${report.failed} failure(s)")
            }
            // Still behind after the pass → shorten the wait so the next one is soon.
            if (report != null && report.remaining > 0) {
                markRun(context, CATCH_UP_MS)
            }
            MemoryTaskState.Finished(
                memoryChatKey(candidate.characterId, candidate.chatId),
                title,
                report != null && report.failed == 0,
                when {
                    report == null -> "自动整理未能启动"
                    // Before the "nothing to do" line: a stepped-over batch leaves the chat with no
                    // pending work and no rows either, which would otherwise read as a finished chat.
                    report.declinedFloors > 0 ->
                        "自动整理完成：$title 有 ${report.declinedFloors} 条没有整理"
                    report.processed == 0 && report.remaining == 0 -> "自动整理：$title 没有新消息需要整理"
                    report.remaining > 0 -> "自动整理完成本轮：$title 仍有 ${report.remaining} 条待整理"
                    else -> "自动整理完成：$title"
                },
            )
        }
        return null
    }

    /** Used by the page's manual button so an unattended run does not repeat it immediately. */
    fun markRun(context: Context, intervalMs: Long = INTERVAL_MS) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_RUN, System.currentTimeMillis())
            .putLong(KEY_INTERVAL, intervalMs)
            .apply()
    }

    suspend fun runNow(context: Context): AgentClient.MemoryRunReport? {
        val port = AgentClient.findPort() ?: return null
        val report = withContext(Dispatchers.IO) {
            AgentClient.memoryRun(port, chatId = null, force = true)
        }
        if (report != null) markRun(context)
        return report
    }
}
