package com.tavern.app.console.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tavern.app.console.AgentClient
import com.tavern.app.console.memoryChatKey
import com.tavern.app.console.MemoryScanState
import com.tavern.app.console.components.PageScaffold
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.components.TaskTerminal
import com.tavern.app.console.theme.AppSpacing
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.tavern.app.util.AppToast

/**
 * Live view of every memory job, reached from the task island.
 *
 * The memory work outlives the pages that start it — a pass keeps reading after the reader leaves
 * the chat page, a rebuild runs for half an hour, an index build for longer — and until now the
 * only trace of that was a one-line chip that named a single chat. Two passes running at once had
 * nowhere to be seen at all.
 *
 * The lines are what this page has watched happen, not a log the runtime keeps: each poll compares
 * the batch number with the one before it and writes a line when it moves. Saying that is cheaper
 * and more honest than adding a log pipeline for a screen that only exists while something runs.
 */
@Composable
fun MemoryTasksScreen(onBack: () -> Unit) {
    var tasks by remember { mutableStateOf<List<AgentClient.MemoryTask>?>(null) }
    val logs = remember { mutableStateMapOf<String, TaskLog>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val scan by MemoryScanState.running.collectAsState()
    // Which row is waiting on a call that can take minutes. One entry per action, so a stop and a
    // rebuild on the same row do not share a spinner.
    var busy by remember { mutableStateOf(setOf<String>()) }
    // The scan reports no start time of its own; the page stamps it the first time it sees one.
    var scanStartedAt by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            val port = AgentClient.findPort()
            val fresh = port?.let { AgentClient.memoryTasks(it) }
            if (fresh == null) {
                // A stopped service is an answer: nothing is running, and the page says so rather
                // than freezing on the last list it saw.
                tasks = emptyList()
            } else {
                tasks = fresh
                val live = fresh.map { it.id() }.toSet()
                // The card keeps following the pass after a stop is asked for: the runtime writes
                // one closing batch while it winds down, and a card frozen at the press showed a
                // count that never moved again — which reads as a press that was ignored.
                fresh.forEach { observe(logs, it) }
                // Collected rather than corrected inside the loop: the map's forEach cannot suspend,
                // and a pass that has just ended is exactly the moment its closing line has to be
        // checked. Left to the periodic sweep below, the card sat on a stale sentence count for seven
                // seconds and then changed its mind — the reader saw a verdict, then a correction.
                val ended = mutableListOf<TaskLog>()
                logs.forEach { (id, log) ->
                    if (id !in live && log.running) {
                        // A stop the runtime has finished winding down is the reader's ending; the
                        // counts below would otherwise turn it back into a verdict about coverage.
                        log.finish()
                        ended.add(log)
                    }
                }
                ended.forEach { log ->
                    // An index build counts sentences; a pass counts floors. Asking both for the
                    // sentence total made a finished pass end on the reader's last sighting of it —
                    // 90 of 99 while the archive had already reached 96 — because the closing poll
                    // only ever saw the number before the final batch was written.
                    if (log.kind == "index") {
                        val now = port?.let {
                            AgentClient.memoryWarmStatus(it, log.chatId, log.characterId)
                        }
                        if (now != null && now.total > 0) log.conclude(now.filled, now.total)
                        else log.concludeLocal()
                    } else {
                        val now = port?.let {
                            AgentClient.memoryDetail(it, log.chatId, log.characterId)
                        }
                        if (now != null && now.messageCount > 0) {
                            log.conclude(now.lastIndex + 1, now.messageCount)
                        } else {
                            log.concludeLocal()
                        }
                    }
                }
                // Every sixth tick, ask the archive what it actually holds for the index records
                // this page has finished watching. One of them read 6240 / 6629 while the file on
                // disk had all 6629: the pass behind that log had done all it could, and the rest
                // was filled in later by a pass this page never saw. Left alone the card keeps
                // claiming work is missing, and the button in front of it does nothing when pressed.
                if (tick++ % 6 == 0) {
                    logs.values
                        .filter { it.kind == "index" && !it.running && it.chatId.isNotBlank() }
                        .forEach { log ->
                            val now = AgentClient.memoryWarmStatus(port, log.chatId, log.characterId)
                            if (now != null && now.total > 0) log.applyRemote(now.filled, now.total)
                        }
                }
            }
            delay(1200)
        }
    }

    val shown = tasks ?: emptyList()
    val running = logs.values.count { it.running }

    PageScaffold(title = "记忆任务", onBack = onBack) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.page, vertical = AppSpacing.md)
        ) {
    // The page is titled for what it lists already, and every card below says what it is doing.
            // A count of them, and a line explaining what a task page is for, are both the page
            // describing itself; only the two states with nothing to show need words.
            val headline = when {
                tasks == null -> "正在读取任务"
                running == 0 && scan == null -> "现在没有记忆任务"
                else -> ""
            }
            if (headline.isNotEmpty()) {
                Text(
                    headline,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(AppSpacing.md))
            }

            scan?.let { running ->
                if (scanStartedAt == 0L) scanStartedAt = System.currentTimeMillis()
                Text(
                    "收录旧聊天",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                TaskTerminal(
                    command = "memory --scan",
                    lines = running.text.split('\n').filter { it.isNotBlank() },
                    status = running.text.replace('\n', ' '),
                    startedAt = scanStartedAt,
                    running = true,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 10,
                )
                Spacer(Modifier.height(AppSpacing.md))
            }

            logs.values.forEach { log ->
                Text(
                    log.headline,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                TaskTerminal(
                    command = log.command,
                    lines = log.lines,
                    status = log.status,
                    startedAt = log.startedAt,
                    running = log.running,
                    fraction = log.fraction,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 12,
                )
                if (log.canContinue) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sm),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        val runToken = "continue/" + log.kind + "/" +
                            memoryChatKey(log.characterId, log.chatId)
                        SoftActionButton(
                            text = "继续建立",
                            loading = runToken in busy,
                            enabled = runToken !in busy,
                            onClick = {
                                scope.launch {
                                    busy = busy + runToken
                                    try {
                                        val port = AgentClient.findPort() ?: return@launch
                                        // Read the truth before asking for more work. This log is a
                                        // record of the pass that once ran; the count it ends on can
                                        // be behind the archive.
                                        val now = AgentClient.memoryWarmStatus(port, log.chatId, log.characterId)
                                        if (now != null && now.total > 0 && now.filled >= now.total) {
                                            log.conclude(now.filled, now.total)
                                            AppToast.show(ctx, "已经全部建立完成", Toast.LENGTH_SHORT)
                                            return@launch
                                        }
                                        val started = AgentClient.startMemoryWarm(port, log.chatId, log.characterId)
                                        // A silent no-op is indistinguishable from a broken button.
                                        if (!started) {
                                            AppToast.show(ctx, "无法开始：可能已有任务正在进行，请稍后重试", Toast.LENGTH_SHORT)
                                        }
                                    } finally {
                                        busy = busy - runToken
                                    }
                                }
                            },
                        )
                    }
                }
                if (log.canRebuild) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sm),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        val rebuildToken = "rebuild/" + log.kind + "/" +
                            memoryChatKey(log.characterId, log.chatId)
                        SoftActionButton(
                            text = "重新整理",
                            color = MaterialTheme.colorScheme.primary,
                            loading = rebuildToken in busy,
                            enabled = rebuildToken !in busy,
                            onClick = {
                                scope.launch {
                                    busy = busy + rebuildToken
                                    val port = try {
                                        AgentClient.findPort()
                                    } catch (e: Exception) {
                                        null
                                    }
                                    val outcome = try {
                                        port?.let {
                                            AgentClient.memoryRebuild(it, log.chatId, log.characterId)
                                        }
                                    } finally {
                                        busy = busy - rebuildToken
                                    }
                                    when {
                                        outcome == null ->
                                            AppToast.show(ctx, "没有收到回应，请稍后重试", Toast.LENGTH_LONG)
                                        outcome.started ->
                                            AppToast.show(ctx, "已开始重新整理", Toast.LENGTH_SHORT)
                                        // The runtime names why it refused, and the common one — an
                                        // address nothing answers on — is the reader's to fix. The
                                        // generic line that stood here hid exactly that.
                                        outcome.detail.isNotBlank() ->
                                            AppToast.show(ctx, outcome.detail, Toast.LENGTH_LONG)
                                        else ->
                                            AppToast.show(ctx, "没能开始，请稍后重试", Toast.LENGTH_LONG)
                                    }
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(AppSpacing.md))
            }
        }
    }
}

/**
 * What this page has watched one job do.
 *
 * Kept per job rather than per chat: two jobs on the same chat — a rebuild whose pass is running
 * while an index build follows it — are two different things to watch, and one shared buffer would
 * interleave them.
 */
private class TaskLog(
    val command: String,
    val headline: String,
    val startedAt: Long,
    // Kept so the row can offer the same button the chat's own page does. The closing line tells
    // Kept so the row can offer the same button the chat's own page does. The closing line points
    // at a button that lives one screen away, behind two taps this page never mentions.
    val chatId: String = "",
    val characterId: String = "",
    val kind: String = "",
) {
    val lines = mutableListOf<String>()
    var status by mutableStateOf("")
    var running by mutableStateOf(true)
    var fraction by mutableStateOf<Float?>(null)
    private var batch = -1
    private var decile = -1
    private var lastBatchDone = -1
    private var finished = false
    private var lastDone = 0
    private var lastTotal = 0

    /**
     * Whether this job can be picked up again from where it stopped.
     *
     * Only an index build has a resumable shape: it counts sentences and keeps what it wrote.
     * A finished pass with sentences left is exactly the state the closing line describes, and
     * offering the button here is what makes that line true.
     */
    val canContinue: Boolean
        get() = kind == "index" && !running && lastTotal > 0 && lastDone < lastTotal

    /**
     * Whether a pass can be asked to read the chat again.
     *
     * A pass writes rows or it does not; there is no half-written batch to pick up, so the way
     * on is to start over from the first floor. The runtime checks that the model answers before
     * it rewinds anything, so this button cannot leave the reader with an emptied archive.
     */
    val canRebuild: Boolean
        get() = kind == "rebuild" && !running && lastTotal > 0 && lastDone < lastTotal

    fun update(task: AgentClient.MemoryTask) {
        // A job in this list is a job that is running. The same chat can be started again after a
        // pass ends — that is what the continue action does — and the new pass reuses this buffer by id. Left
        // marked as finished it kept its old lines, wrote no new progress, and the button looked
        // like it did nothing at all.
        if (finished) {
            finished = false
            lines.clear()
            batch = -1
            decile = -1
            lastBatchDone = -1
        }
        running = true
        status = task.statusText()
        lastDone = task.done
        lastTotal = task.total
        fraction = if (task.total > 0) task.done.toFloat() / task.total.toFloat() else null
        if (task.batch != batch) {
            batch = task.batch
            lines.add(task.batchLine())
        }
        // An index build reports no batches: its only moving number is how many sentences are
        // done, and a log that never grows reads as a log that has stopped. One line per tenth is
        // enough to show it is alive without turning the pane into a counter.
        if (task.kind == "index" && task.total > 0) {
            val step = task.done * 10 / task.total
            if (step > decile) {
                decile = step
                lines.add("已算 " + task.done + " 句（" + step * 10 + "%）")
            }
        }
        // The same treatment for a pass that has not reached its first window yet: it reports how
        // many floors it has read but no range, so the pane sat on the start label for minutes while the bar
        // underneath moved. A range, once it exists, is the better line and takes over.
        if (task.kind != "index" && task.kind != "index-batch" && task.total > 0 && task.to < 0) {
            val step = task.done * 10 / task.total
            if (step > decile) {
                decile = step
                lines.add("已整理 " + task.done + " / " + task.total + " 层（" + step * 10 + "%）")
            }
        }
        // The batch row is the one that survives when a chat finishes, so its own progress has to
        // be written here rather than left to the per-chat rows that disappear with each handover.
        if (task.kind == "index-batch" && task.done != lastBatchDone) {
            lastBatchDone = task.done
            lines.add("已完成 " + task.done + " / " + task.total + " 个聊天")
        }
    }

    /**
     * Writes the ending, once, from whatever number is true at that moment.
     *
     * Every path ends here — the local count when a probe fails, the archive's count when it
     * answers — so the card is told one verdict instead of being told one and then corrected.
     */
    fun conclude(filled: Int, total: Int) {
        if (finished) return
        finished = true
        running = false
        lastDone = filled
        lastTotal = total
        fraction = if (total > 0) filled.toFloat() / total.toFloat() else null
        // The placeholder this record carried while it was being checked is withdrawn.
        if (lines.isNotEmpty() && lines.last().startsWith("—— 本批结束")) lines.removeAt(lines.lastIndex)
        val left = maxOf(0, total - filled)
        // Two jobs, two vocabularies. An index build counts sentences and keeps what it wrote; a
        // pass reads floors and writes rows. The page called both of them sentences and told the
        // reader to press the continue action after a pass, where that button does not exist and could not
        // work: a pass that wrote nothing has nothing to continue from.
        val indexing = kind == "index"
        val unit = if (indexing) "句" else "层"
        // Counted in lines, not in percent: a short chat missing three is normal, a long one
        // missing a hundred is not, even though that is the smaller share.
        val tolerance = maxOf(5, minOf(20, total / 50))
        when {
            left <= 0 -> {
                status = "已结束"
                lines.add(
                    if (indexing) "—— 全部建立完成：$filled / $total $unit ——"
                    else "—— 全部整理完成：$filled / $total $unit ——"
                )
            }
            left <= tolerance && indexing -> {
                status = "结束，$filled / $total $unit"
                lines.add("—— 结束：$filled / $total $unit ——")
                lines.add("  未建立的 $left 句无法计算向量：片段过短，或与上下文没有可比语义，重试结果相同。")
            }
            else -> {
                // One pass is bounded by time and rounds, so ending short is the normal shape of a
        // long chat, not a fault. Calling it abnormal in every row made a page of ordinary
                // batches read as a page of failures.
                status = "结束，$filled / $total $unit"
                lines.add("—— 结束：$filled / $total $unit ——")
                lines.add(
                    if (indexing) "  本批结束时还剩 $left 句未建立。点「继续建立」会接着建立剩下的部分。"
                    // Why it stopped, and what the next tap does. The old line described the run
                    // instead of the reason and pointed at a rewind that costs a second full pass.
                    // A tail of one or two floors is the newest exchange, which no pass reads until
                    // the chat moves on: promising to continue from there would be a false start.
                    else if (left <= 4) "  这次整理到第 $filled 层停下了。剩下的 $left 层是最近一轮对话，" +
                        "等新消息一起整理。"
                    else "  这次整理到第 $filled 层停下了，还有 $left 层没有整理。"
                )
            }
        }
    }

    /** The ending from what this record already knows, for when the archive cannot be asked. */
    fun concludeLocal() = conclude(lastDone, lastTotal)

    /**
     * Takes the archive's own count when it disagrees with this record.
     *
     * Only a finished record is corrected: a running pass reports its own numbers every tick, and
     * overwriting those with a probe taken between polls would make the bar jump backwards.
     */
    fun applyRemote(filled: Int, total: Int) {
        if (running) return
        if (finished && filled == lastDone && total == lastTotal) return
        if (!finished || filled > lastDone) {
            finished = false
            conclude(filled, total)
        }
    }

    fun finish() {
        if (finished) return
        running = false
        // An index build's last reported number can be behind the archive — the pass reports what
        // it managed, the file holds what was actually written, and a later pass can fill the rest.
        // Printing a leftover count here and retracting it a moment later showed the reader a verdict
        // followed by a correction; this record waits for the answer instead.
        if (kind == "index" && chatId.isNotBlank()) {
            status = "正在核对结果…"
            lines.add("—— 本批结束，正在核对 ——")
            return
        }
        conclude(lastDone, lastTotal)
    }

}

private fun AgentClient.MemoryTask.id(): String = kind + "/" + chatId

private fun AgentClient.MemoryTask.commandText(): String = when (kind) {
    "rebuild" -> "memory --rebuild"
    "index" -> "memory --index"
    "index-batch" -> "memory --index --all"
    else -> "memory --organize"
}

/** The chat is the only thing that says which job this is; the verb says what it is doing. */
private fun AgentClient.MemoryTask.headlineText(): String {
    val verb = when (kind) {
        "rebuild" -> "重新整理"
        "index" -> "建立索引"
        "index-batch" -> "全部建立索引"
        else -> "整理"
    }
    return verb + "，" + memoryChatTitle(chatId)
}

private fun AgentClient.MemoryTask.statusText(): String = when {
    kind == "index" -> "已算 $done / $total 句"
    kind == "index-batch" -> "已完成 $done / $total 个聊天"
    from >= 0 && to >= 0 -> "第 $batch 批，第 ${from + 1}–${to + 1} 层"
    // A rebuild reaches the first range only after its first batch: until then it reports a
        // growing count and no window at all, and the line that stays on the starting label while the bar under
    // it fills reads as a page that has stopped updating.
    total > 0 -> "已整理 $done / $total 层"
    else -> "准备中"
}

private fun AgentClient.MemoryTask.batchLine(): String = when {
    kind == "index" -> "索引：$total 句待算"
    kind == "index-batch" -> "全部：$total 个聊天"
    from >= 0 && to >= 0 -> "第 $batch 批  第 ${from + 1}–${to + 1} 层（$count 条）"
    else -> "开始"
}

private fun observe(logs: MutableMap<String, TaskLog>, task: AgentClient.MemoryTask) {
    val id = task.id()
    val log = logs.getOrPut(id) {
        TaskLog(
            command = task.commandText() + " " + memoryChatTitle(task.chatId),
            headline = task.headlineText(),
            startedAt = task.startedAt,
            chatId = task.chatId,
            characterId = task.characterId,
            kind = task.kind,
        )
    }
    log.update(task)
}
