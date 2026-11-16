package com.tavern.app.console.pages

import com.tavern.app.console.components.ExpandChevron
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import com.tavern.app.console.theme.cardPressSpring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tavern.app.BuildConfig
import com.tavern.app.console.AgentClient
import com.tavern.app.console.MemoryEmbeddingConfig
import com.tavern.app.console.MemoryEmbeddingPreferences
import com.tavern.app.console.MemoryModelConfig
import com.tavern.app.console.MemoryModelPreferences
import com.tavern.app.console.MemoryRetrievalMode
import com.tavern.app.console.MemoryTaskState
import com.tavern.app.console.memoryChatKey
import com.tavern.app.console.SettingsState
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.console.components.CollapsibleSection
import com.tavern.app.console.components.DialogConfirmButton
import com.tavern.app.console.components.SkeletonRow
import com.tavern.app.console.components.DialogDismissButton
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.MemoryGraph3DView
import com.tavern.app.console.components.MemoryGraphView
import com.tavern.app.console.components.PageTopBar
import com.tavern.app.console.components.SectionLabel
import com.tavern.app.console.components.appTextFieldColors
import com.tavern.app.console.memory.EdgeDetail
import com.tavern.app.console.memory.MemoryEdge
import com.tavern.app.console.memory.MemoryEdgeDetails
import com.tavern.app.console.memory.MemoryEmbedding
import com.tavern.app.console.memory.MemoryFocus
import com.tavern.app.console.memory.MemoryGraph
import com.tavern.app.console.memory.MemoryGraphs
import com.tavern.app.console.memory.MemoryModelPack
import com.tavern.app.console.memory.MemoryNode
import com.tavern.app.console.memory.MemoryNodeDetails
import com.tavern.app.console.memory.MemoryTimeline
import com.tavern.app.console.memory.MemoryTimelineGroup
import com.tavern.app.console.memory.NodeDetail
import com.tavern.app.console.components.AppSwitch
import com.tavern.app.console.theme.LocalAppColors
import androidx.compose.foundation.lazy.LazyItemScope
import com.tavern.app.console.theme.FluentMotionSpec
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.console.theme.enterExpand
import com.tavern.app.console.theme.exitShrink
import com.tavern.app.console.memory.NodeDetailRow
import com.tavern.app.console.runMemoryDispatch
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppPanelShape
import com.tavern.app.console.theme.AppCardShape
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.rowPressable
import com.tavern.app.util.AppToast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.tavern.app.util.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-chat memory. One chat, one memory: an offline pass reads the messages added since the last
 * pass and folds them into a rolling digest plus a list of facts and open threads.
 *
 * The list answers "which chats are behind"; the detail page answers "what does this chat
 * remember", which is the part the reader could not see before.
 */
@Composable
fun ChatMemoryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AgentClient.ChatMemoryState?>(null) }
    var loading by remember { mutableStateOf(true) }
    // The pass lives on the shared object, so leaving this page does not cancel it. The island
    // reads the same flow.
    val task by MemoryTaskState.running.collectAsState()
    val finished by MemoryTaskState.finished.collectAsState()
    val busy = task != null
    var enabled by remember { mutableStateOf(SettingsState.chatMemoryEnabled()) }
    var takeover by remember { mutableStateOf(SettingsState.memoryAgentTakeover()) }
    var ghostEnabled by remember { mutableStateOf(SettingsState.chatMemoryGhostEnabled()) }
    var autoThreshold by remember { mutableStateOf(SettingsState.chatMemoryPendingThreshold()) }
    var memoryBudget by remember { mutableStateOf(SettingsState.chatMemoryBudget()) }
    var viewing by remember { mutableStateOf<AgentClient.ChatMemory?>(null) }
    // The pass the runtime is running for this chat, asked of the service: a pass can outlive this
    // page or start from elsewhere.
    var passTask by remember { mutableStateOf<AgentClient.MemoryTask?>(null) }
    var detail by remember { mutableStateOf<AgentClient.MemoryDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AgentClient.MemoryEntry?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    // Deletion is the only ending, and it cannot be undone.
    var confirmOrphans by remember { mutableStateOf(false) }
    // The run a reader pressed while the one-time note about progress was on screen. Null when
    // no note is pending.
    var progressNoteRun by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmTakeover by remember { mutableStateOf(false) }
    var sourceTurns by remember { mutableStateOf<List<AgentClient.SourceTurn>?>(null) }
    var sourceLoading by remember { mutableStateOf(false) }
    // Two counters, not one: a detail refresh must not cancel a source read that is still on its
    // way, and the reverse. Each load remembers the number it started under and drops its answer
    // when the number has moved.
    var detailToken by remember { mutableStateOf(0) }
    var sourceToken by remember { mutableStateOf(0) }
    var graphOpen by remember { mutableStateOf(false) }
    var forgetOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var rebuildOpen by remember { mutableStateOf(false) }
    // How recall finds old turns: the local index or the paid semantic service. It is the one setting
    // with a download and a memory cost behind it, so it sits beside the budget rather than in a card.
    var embedding by remember { mutableStateOf(MemoryEmbeddingPreferences.load(ctx)) }
    var recall by remember { mutableStateOf<AgentClient.RecallStatus?>(null) }
    var retrievalOpen by remember { mutableStateOf(false) }

    // System back walks out one layer at a time: dialog, source turns, the graph, then the chat
    // list, and only then the extensions tab. Without this the key jumped from the middle of the
    // sea straight back to the tab, which read as the page losing its place.
    BackHandler {
        when {
            confirmClear -> confirmClear = false
            confirmTakeover -> confirmTakeover = false
            rebuildOpen -> rebuildOpen = false
            pendingDelete != null -> pendingDelete = null
            sourceTurns != null -> sourceTurns = null
            addOpen -> addOpen = false
            forgetOpen -> forgetOpen = false
            graphOpen -> graphOpen = false
            viewing != null -> viewing = null
            else -> onBack()
        }
    }

    fun refresh(silent: Boolean = false) {
        scope.launch {
            if (!silent) loading = true
            val port = AgentClient.findPort()
            state = if (port == null) {
                null
            } else {
                withContext(Dispatchers.Default) { AgentClient.memoryState(port) }
            }
            // The open chat's row is re-bound to the snapshot the list draws. The detail page reads
            // several numbers straight off it — how many rows, which prompt version wrote them —
            // and a copy taken when the page opened keeps answering with what was true then: a
            // rebuild that has already finished would still read as one that has not started.
            val snapshot = state
            val open = viewing
            if (snapshot != null && open != null) {
                snapshot.chats
                    .firstOrNull { it.chatId == open.chatId && it.characterId == open.characterId }
                    ?.let { viewing = it }
            }
            // Beside the archive, so the two lines on the page agree about the same moment.
            recall = if (port == null) null else AgentClient.recallStatus(port)
            loading = false
        }
    }

    var warm by remember { mutableStateOf<AgentClient.WarmStatus?>(null) }
    var warmPending by remember { mutableStateOf(0) }
    var warmRunning by remember { mutableStateOf(false) }
    // The chat the queue is on and how far into it. A queue of five chats takes twenty minutes and
    // the running label alone is indistinguishable from a frozen screen.
    var warmTitle by remember { mutableStateOf("") }
    var warmFilled by remember { mutableStateOf(0) }
    var warmTotal by remember { mutableStateOf(0) }
    /**
     * True from the tap until the queue is seen running and then done. Without it the card
     * disappears the instant the last chat is queued — pending drops to zero, running has not been
     * observed yet — and the reader reads that as the tap having done nothing.
     */
    var warmStarting by remember { mutableStateOf(false) }

    /** The last recall check of this chat: how many quoted turns the search found again. */
    var probe by remember { mutableStateOf<AgentClient.ProbeResult?>(null) }
    var probing by remember { mutableStateOf(false) }
    /** The last question typed into the memory test, and what the excerpt search made of it. */
    var search by remember { mutableStateOf<AgentClient.MemorySearch?>(null) }
    var searching by remember { mutableStateOf(false) }

    /**
     * How much of this chat is vectorised.
     *
     * Kept out of the detail payload: the total is the number of sentences the chat would put in
     * the index, which is a scan of its own, and every open of the page would pay for it.
     */
    fun loadWarm(chat: AgentClient.ChatMemory) {
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            warm = AgentClient.memoryWarmStatus(port, chat.chatId, chat.characterId)
        }
    }

    /**
     * How many chats have no vectors at all, and where the queue is.
     *
     * Answered by the core with one stat per chat, so it stays cheap enough to ask on every visit
     * and while a queue runs.
     */
    suspend fun refreshWarmOverview() {
        val port = AgentClient.findPort() ?: return
        val overview = AgentClient.memoryWarmOverview(port) ?: return
        warmPending = overview.pending
        warmRunning = overview.running
        warmTitle = overview.currentTitle
        warmFilled = overview.currentFilled
        warmTotal = overview.currentTotal
    }

    fun loadWarmOverview() {
        scope.launch { refreshWarmOverview() }
    }

    fun startSearch(chat: AgentClient.ChatMemory, query: String) {
        if (searching || query.isBlank()) return
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            searching = true
            search = AgentClient.memorySearch(port, chat.chatId, chat.characterId, query)
            searching = false
        }
    }

    fun startProbe(chat: AgentClient.ChatMemory) {
        if (probing) return
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            probing = true
            probe = AgentClient.memoryProbe(port, chat.chatId, chat.characterId)
            probing = false
        }
    }

    fun startAllWarm() {
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            warmStarting = true
            val queued = AgentClient.startMemoryWarmAll(port)
            AppToast.show(
                ctx,
                if (queued > 0) "已排入 " + queued + " 个聊天，逐个建立" else "没有需要建立的聊天",
                Toast.LENGTH_SHORT,
            )
            refreshWarmOverview()
            // Follow the queue to its end. Nothing else tells this page the run finished: the
    // index is built in the background and the page would otherwise sit on the running label forever.
            var idle = 0
            while (isActive) {
                delay(2_000L)
                refreshWarmOverview()
                idle = if (warmRunning) 0 else idle + 1
                if (idle >= 2) break
            }
            warmStarting = false
            refreshWarmOverview()
        }
    }

    // The index is built outside this page, and a finished run would otherwise keep reading as "one
    // chat left"; the overview is polled every 8 seconds.
    LaunchedEffect(Unit) {
        while (true) {
            delay(8_000L)
            refreshWarmOverview()
        }
    }
    // Whether the runtime is reading this chat right now, polled every 3 seconds.
    LaunchedEffect(viewing?.chatId, viewing?.characterId) {
        val open = viewing ?: return@LaunchedEffect
        val key = memoryChatKey(open.characterId, open.chatId)
        while (true) {
            val port = AgentClient.findPort()
            passTask = port?.let { AgentClient.memoryTasks(it) }
                ?.firstOrNull {
                    memoryChatKey(it.characterId, it.chatId) == key &&
                        (it.kind == "organize" || it.kind == "rebuild")
                }
            delay(3_000L)
        }
    }
    fun startWarm() {
        val chat = viewing ?: return
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            // Rebuild recomputes the whole index; continue keeps the vectors that exist and
            // resumes from where the last run stopped.
            val settled = indexSettled(warm)
            val hadIndex = (warm?.filled ?: 0) > 0
            if (AgentClient.startMemoryWarm(port, chat.chatId, chat.characterId, force = settled)) {
                AppToast.show(
                    ctx,
                    when {
                        settled && hadIndex -> "已清空，正在重新建立"
                        hadIndex -> "继续建立，任务在后台进行"
                        else -> "开始建立索引，任务在后台进行"
                    },
                    Toast.LENGTH_SHORT,
                )
                loadWarm(chat)
                // No progress is pushed to this page. Without these reads the numbers stay at their pre-tap value
                // and the tap looks inert.
                repeat(8) {
                    delay(1_500L)
                    loadWarm(chat)
                }
            } else {
                AppToast.show(ctx, "无法开始：另一个聊天正在建立索引", Toast.LENGTH_LONG)
            }
        }
    }
    fun loadDetail(chat: AgentClient.ChatMemory, keepGraph: Boolean = false) {
        viewing = chat
        // The graph is a screen on top of this one; a reload that clears its rows closes it.
        if (!keepGraph) {
            detail = null
            graphOpen = false
        }
        detailLoading = true
        // A late answer for another chat is dropped: `viewing` and `detail` must name the same chat.
        detailToken += 1
        loadWarm(chat)
        val token = detailToken
        scope.launch {
            val port = AgentClient.findPort()
            val loaded = if (port == null) {
                null
            } else {
                AgentClient.memoryDetail(port, chat.chatId, chat.characterId)
            }
            if (token != detailToken || viewing?.chatId != chat.chatId) return@launch
            detail = loaded
            detailLoading = false
        }
    }

    fun reloadDetail() {
        viewing?.let { loadDetail(it) }
    }

    /**
     * Starts a pass, or explains the counters first.
     *
     * A pass reads as much as fits in one dispatch and leaves the newest exchange for the next
     * message, so a run ending at 20 / 23 is the normal shape. The note is shown once per
     * install: it explains the numbers on every chat, not this chat's state.
     */
    fun startWithProgressNote(action: () -> Unit) {
        if (SettingsState.memoryProgressNotePending(ctx)) progressNoteRun = action else action()
    }

    fun startRebuild() {
        val chat = viewing ?: return
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            val outcome = AgentClient.memoryRebuild(port, chat.chatId, chat.characterId)
            when {
                outcome == null ->
                    AppToast.show(ctx, "未收到回应，请稍后重试", Toast.LENGTH_LONG)
                outcome.started -> {
                    AppToast.show(ctx, "开始重新整理，任务在后台进行", Toast.LENGTH_SHORT)
                    loadDetail(chat)
                    refresh(silent = true)
                }
                // The runtime names the reason — an address it could not reach, a pass still
                // running. Replacing that with a guess about "no model" sent the reader looking
                // in the wrong place.
                outcome.detail.isNotBlank() ->
                    AppToast.show(ctx, outcome.detail, Toast.LENGTH_LONG)
                else ->
                    AppToast.show(ctx, "无法开始，请稍后重试", Toast.LENGTH_LONG)
            }
        }
    }

    /**
     * Turns one chat's memory off or on.
     *
     * Off means nothing about this chat is read or carried into a prompt; the archive stays on
     * disk and switching back on continues from the same watermark. The row is flipped before the
     * call returns so the switch does not sit in the old position while the request is in flight,
     * and the refresh afterwards is what makes the page agree with the file.
     */
    fun setDisabled(disabled: Boolean) {
        val chat = viewing ?: return
        viewing = chat.copy(disabled = disabled)
        scope.launch {
            val port = AgentClient.findPort()
            if (port == null) {
                viewing = chat
                AppToast.show(ctx, "记忆服务未运行", Toast.LENGTH_SHORT)
                return@launch
            }
            val applied = AgentClient.memorySetDisabled(port, chat.chatId, chat.characterId, disabled)
            if (applied == null) {
                viewing = chat
                AppToast.show(ctx, "记忆服务未返回结果", Toast.LENGTH_SHORT)
            } else {
                viewing = chat.copy(disabled = applied)
                AppToast.show(
                    ctx,
                    if (applied) "此聊天已停止使用记忆" else "此聊天已恢复使用记忆",
                    Toast.LENGTH_SHORT,
                )
                refresh(silent = true)
                reloadDetail()
            }
        }
    }

    /**
     * The turns behind a stored row. The floor numbers are already on screen; this is what makes
     * them checkable without leaving the page and scrolling the chat.
     */
    fun showSource(from: Int, to: Int) {
        val chat = viewing ?: return
        if (from < 0 || sourceLoading) return
        sourceToken += 1
        val token = sourceToken
        scope.launch {
            sourceLoading = true
            val port = AgentClient.findPort()
            val turns = if (port == null) {
                null
            } else {
                AgentClient.memorySources(port, chat.chatId, chat.characterId, from, to)
            }
            // A newer tap owns the flag and the answer.
            if (token != sourceToken) return@launch
            // The reader left this chat with the request still in flight: the turns are floors of
            // a chat that is no longer on screen, and the toast would name a failure that did not
            // happen. The flag still has to be cleared, or the next source tap is refused forever.
            if (viewing?.chatId != chat.chatId) {
                sourceLoading = false
                return@launch
            }
            sourceLoading = false
            if (turns.isNullOrEmpty()) {
                AppToast.show(ctx, "记忆服务未返回原文", Toast.LENGTH_SHORT)
            } else {
                sourceTurns = turns
            }
        }
    }

    /**
     * Starts one dispatch. The request itself blocks for as long as the model takes and carries no
     * progress, so the batch is read back from the state the engine writes while it waits.
     */
    fun run(chatId: String?, characterId: String, label: String) {
        // Said out loud: the button on this page is enabled, since the chat it belongs to is not
        // the one being read. Returning silently made it look broken.
        if (MemoryTaskState.busy) {
            AppToast.show(ctx, "另一个聊天正在整理，请待其结束后重试", Toast.LENGTH_LONG)
            return
        }
        // Every button that reaches this is already disabled in that case; the guard is here so a
        // future caller cannot spend a model call building an archive nothing will ever read.
        if (!enabled) {
            AppToast.show(ctx, "记忆已关闭，整理内容不再带入生成", Toast.LENGTH_LONG)
            return
        }
        // The service reads the switch per call now, so a mismatch here is a race of a second or
        // two, not a restart. Asking anyway gets a 409 the reader cannot act on.
        val live = state?.modelTakeover == true
        if (!live) {
            AppToast.show(
                ctx,
                if (takeover) {
                    "记忆服务尚未读取这次切换，请稍后重试"
                } else {
                    "整理需要调用模型：先开启「用模型整理」开关"
                },
                Toast.LENGTH_LONG,
            )
            return
        }
        MemoryTaskState.start(
            scopeKey = chatId
                ?.let { memoryChatKey(characterId, it) }
                ?: MemoryTaskState.ALL_CHATS,
            chatTitle = label,
        ) { onProgress ->
            val port = AgentClient.findPort()
            if (port == null) {
                MemoryTaskState.Finished(
                    chatId?.let { memoryChatKey(characterId, it) } ?: MemoryTaskState.ALL_CHATS,
                    label,
                    false,
                    "记忆服务没有在运行",
                )
            } else {
                val report = runMemoryDispatch(
                    port,
                    chatId,
                    characterId = characterId,
                    // Pressing the button is the reader deciding to spend a call now, wait or no
                    // wait; the scheduled dispatch is the one that must respect the cooldown.
                    force = true,
                    onProgress = onProgress,
                )
                val message = when {
                    report == null -> "整理没有开始"
                    report.failed > 0 -> "整理未完成：完成 ${report.processed} 个，${report.failed} 个没有成功"
                    // Said before the finished line: the batch was stepped over, so the run finished but
                    // part of the chat is not in the archive.
                    report.declinedFloors > 0 -> "$label 已整理完可整理的部分，有 ${report.declinedFloors} 条被跳过"
                    report.cuesFilled > 0 -> "$label 已整理完可整理的部分，补了 ${report.cuesFilled} 条检索线索"
                    report.processed == 0 && report.remaining == 0 -> "$label 没有可整理的新消息"
                    report.remaining > 0 -> "整理暂停，还剩 ${report.remaining} 条未整理；再次整理可继续"
                    else -> "$label 已整理完可整理的部分"
                }
                MemoryTaskState.Finished(
                    chatId?.let { memoryChatKey(characterId, it) } ?: MemoryTaskState.ALL_CHATS,
                    label,
                    report != null && report.failed == 0,
                    message,
                )
            }
        }
    }

    /**
     * Holds a row still, or lets it go. The detail is reloaded from the server rather than patched
     * in place: the same row may have been retired by a pass in the meantime, and showing the
     * local guess would be a promise the file does not keep.
     */
    fun pin(entry: AgentClient.MemoryEntry, pinned: Boolean) {
        val chat = viewing ?: return
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            val ok = AgentClient.memoryPin(port, chat.chatId, chat.characterId, entry.id, pinned)
            AppToast.show(
                ctx,
                when {
                    !ok -> "改动没有成功"
                    pinned -> "已固定，不参与后续淘汰"
                    else -> "已取消固定"
                },
                Toast.LENGTH_SHORT,
            )
            if (ok) {
                loadDetail(chat)
                refresh(silent = true)
            }
        }
    }

    /**
     * Stores a sentence the reader wrote. The server refuses an empty one and answers with the
     * existing id for a sentence already in the archive, so the page has two things to say beyond
     * "stored" — and saying nothing at all would read as the archive ignoring the row.
     */
    fun addEntry(text: String, cues: List<String>) {
        val chat = viewing ?: return
        if (text.isBlank()) return
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            val id = AgentClient.memoryAddEntry(port, chat.chatId, chat.characterId, text.trim(), cues)
            addOpen = false
            AppToast.show(
                ctx,
                if (id == null) "没有存进去" else "已添加，之后的生成会带上它",
                Toast.LENGTH_SHORT,
            )
            if (id != null) {
                loadDetail(chat)
                refresh(silent = true)
            }
        }
    }

    /** Takes the last change back, whatever it was. */
    fun undo() {
        val chat = viewing ?: return
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            val undone = AgentClient.memoryUndo(port, chat.chatId, chat.characterId)
            AppToast.show(ctx, undoText(undone), Toast.LENGTH_LONG)
            loadDetail(chat)
            refresh(silent = true)
        }
    }

    fun forget(topic: String) {
        val chat = viewing ?: return
        forgetOpen = false
        scope.launch {
            val port = AgentClient.findPort() ?: return@launch
            val removed = AgentClient.memoryForget(port, chat.chatId, chat.characterId, topic)
            AppToast.show(
                ctx,
                if (removed == null) {
                    "没有记忆提到「$topic」，没有删除任何内容"
                } else {
                    "已忘掉「$topic」：删除 $removed 处，后续整理不会写入"
                },
                Toast.LENGTH_LONG,
            )
            loadDetail(chat)
            refresh(silent = true)
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(Unit) { loadWarmOverview() }
    // Shown once, before the first pass rather than after a reply that missed something: the
    // default is words-only, and a reader who never opened this screen never chose it.
    LaunchedEffect(Unit) {
        if (!MemoryEmbeddingPreferences.load(ctx).onboarded) retrievalOpen = true
    }

    // While the queue works, the count on this page is what tells the reader it is moving.
    LaunchedEffect(warmRunning) {
        while (warmRunning) {
            delay(3000)
            loadWarmOverview()
        }
    }
    // The index runs for minutes. The page follows it while it runs and stops asking the moment it
    // ends; a progress line that only moves when the page is reopened is the same as none.
    LaunchedEffect(warm?.running) {
        while (warm?.running == true) {
            delay(2000)
            val open = viewing ?: break
            val port = AgentClient.findPort() ?: break
            warm = AgentClient.memoryWarmStatus(port, open.chatId, open.characterId)
        }
    }

    // While a pass runs, the list and the open detail refresh on their own: the counts and the
    // batch line move with the pass, and a page that only updates after the run ends is the
    // reason it looked frozen.
    LaunchedEffect(busy) {
        while (busy) {
            delay(1500)
            refresh(silent = true)
            val open = viewing
            val port = AgentClient.findPort()
            if (open != null && port != null) {
                detail = AgentClient.memoryDetail(port, open.chatId, open.characterId)
            }
        }
    }

    LaunchedEffect(finished) {
        val done = finished ?: return@LaunchedEffect
        // A pass that ended while this page was closed has already been reported by the island;
        // replaying it here would read as a result that just arrived.
        if (System.currentTimeMillis() - done.at > 60_000L) {
            MemoryTaskState.clearFinished()
            return@LaunchedEffect
        }
        AppToast.show(ctx, done.text, Toast.LENGTH_LONG)
        MemoryTaskState.clearFinished()
        refresh()
        if (viewing != null) reloadDetail()
    }

    val viewer = viewing
    // A number measured on one chat says nothing about another: the card would keep the previous
    // result on screen until the reader pressed it again.
    LaunchedEffect(viewer?.chatId, viewer?.characterId) {
        probe = null
        search = null
    }
    val openDetail = detail
    if (viewer != null && graphOpen && openDetail != null) {
        val chatForMap = viewer
        MemoryGraphScreen(
            chat = chatForMap,
            detail = openDetail,
            onBack = { graphOpen = false },
            onSource = { from, to -> showSource(from, to) },
            loadFloor = { floor ->
                val port = AgentClient.findPort()
                port?.let { AgentClient.memorySources(it, chatForMap.chatId, chatForMap.characterId, floor, floor) }
            },
            onRebuilt = { viewing?.let { loadDetail(it, keepGraph = true) } },
        )
    } else if (viewer != null) {
        DetailPage(
            chat = viewer,
            detail = detail,
            loading = detailLoading,
            // Only this chat's pass. A task running for another chat made this page's organise
            // menu claim the chat was being read, then answer nothing when pressed.
            running = task?.scopeKey == memoryChatKey(viewer.characterId, viewer.chatId) ||
                passTask != null,
            progressText = task
                ?.takeIf { it.scopeKey == memoryChatKey(viewer.characterId, viewer.chatId) }
                ?.detail,
            enabled = enabled && takeover,
            takeoverReady = state?.modelTakeover == true,
            onBack = { viewing = null },
            // The island names the chat it is consolidating; a generic label told the reader
            // nothing once they had left the page.
            onRun = { run(viewer.chatId, viewer.characterId, memoryChatTitle(viewer.chatId)) },
            requestRun = { action -> startWithProgressNote(action) },
            onExplain = { AppToast.show(ctx, it, Toast.LENGTH_LONG) },
            // A pass started from anywhere — the task page, another screen, a previous launch of
            // the app — is still this chat's pass. A label driven only by this page's own button
            // would report nothing while another screen's pass is running.
            onDeleteEntry = { pendingDelete = it },
            onPin = { entry, pinned -> pin(entry, pinned) },
            onAddEntry = { addOpen = true },
            onUndo = { undo() },
            onForget = { forgetOpen = true },
            onClear = { confirmClear = true },
            onSource = { from, to -> showSource(from, to) },
            onOpenGraph = { graphOpen = true },
            onToggleDisabled = { setDisabled(it) },
            embedding = embedding,
            warm = warm,
            onWarm = { startWarm() },
            probe = probe,
            probing = probing,
            onProbe = { startProbe(viewer) },
            search = search,
            searching = searching,
            onSearch = { startSearch(viewer, it) },
            currentPromptVersion = state?.promptVersion ?: 0,
            onRebuild = { rebuildOpen = true },
            request = state,
        )
    } else {
        ChatListPage(
            // Zero when vectors are off: a reader who chose words-only has nothing to build, and a
            // line counting what they are not using reads as a fault.
            warmPending = if (embedding.retrieval) warmPending else 0,
            warmRunning = warmRunning,
            warmTitle = warmTitle,
            warmFilled = warmFilled,
            warmTotal = warmTotal,
            warmStarting = warmStarting,
            onWarmAll = { startAllWarm() },
            // Counted from the rows the page already has: no extra request, and it is the same
            // number the detail page compares when it decides to offer the button.
            staleChats = state?.let { snapshot ->
                snapshot.chats.filter { it.staleAgainst(snapshot.promptVersion) }
            }.orEmpty(),
            state = state,
            loading = loading,
            task = task,
            enabled = enabled,
            takeover = takeover,
            takeoverReady = state?.modelTakeover == true,
            onToggle = { on ->
                enabled = on
                SettingsState.setChatMemory(ctx, on)
            },
            onToggleTakeover = { on ->
                if (on && !takeover) {
                    confirmTakeover = true
                } else {
                    takeover = on
                    SettingsState.setMemoryAgentTakeover(ctx, on)
                    // The service reads the switch per call now, so what it reports is the truth
                    // about whether a pass can run; re-read it instead of trusting the tap.
                    refresh(silent = true)
                }
            },
            ghostEnabled = ghostEnabled,
            onToggleGhost = { on ->
                ghostEnabled = on
                SettingsState.setChatMemoryGhost(ctx, on)
            },
            autoThreshold = autoThreshold,
            onPickAutoThreshold = { floors ->
                autoThreshold = floors
                SettingsState.setChatMemoryPendingThreshold(ctx, floors)
            },
            memoryBudget = memoryBudget,
            onPickMemoryBudget = { chars ->
                memoryBudget = chars
                SettingsState.setChatMemoryBudget(ctx, chars)
            },
            onRunAll = { run(null, "", "全部") },
            requestRun = { action -> startWithProgressNote(action) },
            onOpen = { loadDetail(it) },
            onBack = onBack,
            embedding = embedding,
            recall = recall,
            onOpenRetrieval = { retrievalOpen = true },
            onCleanOrphans = { confirmOrphans = true },
        )
    }

    if (retrievalOpen) {
        RetrievalDialog(
            initial = embedding,
            firstRun = !embedding.onboarded,
            onDismiss = {
                retrievalOpen = false
                // Dismissing is an answer too: the reader has seen the three ways and chose to
                // keep the default, and the screen should not open again next time.
                if (!embedding.onboarded) {
                    val marked = embedding.copy(onboarded = true)
                    embedding = marked
                    MemoryEmbeddingPreferences.save(ctx, marked)
                }
            },
            // The model went from absent to present, so the card's line and the engine's probe
            // are both stale. Re-read rather than assume: the engine answers for the files it
            // can see, and it also has to clear its failed-probe window before the next
            // generation is allowed to send a batch.
            onModelInstalled = {
                scope.launch {
                    val port = AgentClient.findPort()
                    recall = if (port == null) null else AgentClient.recallStatus(port)
                }
            },
            onSave = { config ->
                val saved = config.copy(onboarded = true)
                embedding = saved
                retrievalOpen = false
                MemoryEmbeddingPreferences.save(ctx, saved)
                // The bundled model is warmed once at launch, and only when the switch was already
                // on. Turning vectors on later in the session misses that pass, and the model then
                // sits at "not-loaded" until the next start — which reads as a fault on the very
                // screen that just asked for it. Load it here, then read the status back rather
                // than assume: the engine answers for the file it just got, and a mapping that
                // failed to reach it must not be shown as applied.
                val needsLocalModel = config.retrieval && config.endpoint.isBlank()
                scope.launch {
                    if (needsLocalModel) {
                        withContext(Dispatchers.IO) { MemoryEmbedding.prepare(ctx) }
                    }
                    val port = AgentClient.findPort()
                    recall = if (port == null) null else AgentClient.recallStatus(port)
                }
            },
        )
    }

    if (confirmTakeover) {
        ConfirmDialog(
            title = "开启模型整理？",
            message = "整理会调用核心已配置的模型生成摘要、条目与场景；" +
                "每次整理至少一次调用，产生费用。关闭时只按原文归类。",
            confirmText = "开启",
            onConfirm = {
                confirmTakeover = false
                takeover = true
                SettingsState.setMemoryAgentTakeover(ctx, true)
                // The service reads the switch per call, so the very next state it reports is the
                // truth about whether a pass can run. Without this the page kept the state it had
                // loaded before the switch, and the run button stayed grey next to a green switch.
                refresh(silent = true)
                AppToast.show(ctx, "已开启：整理会调用模型，产生费用", Toast.LENGTH_LONG)
            },
            onDismiss = { confirmTakeover = false },
        )
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = "删除这条记忆",
            message = "只删除这一条，删除后无法恢复。",
            confirmText = "删除",
            onConfirm = {
                pendingDelete = null
                val chat = viewing ?: return@ConfirmDialog
                scope.launch {
                    val port = AgentClient.findPort()
                    val ok = port != null && AgentClient.memoryDeleteEntry(port, chat.chatId, chat.characterId, entry.id)
                    AppToast.show(ctx, if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT)
                    if (ok) {
                        reloadDetail()
                        refresh()
                    }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "清空这个聊天的记忆",
            message = "删除全部摘要与条目，聊天记录保留。\n\n" +
                "之后整理会重新读取聊天记录并重建记忆。若只需要排除某个话题，" +
                "使用「忘掉一个话题」。",
            confirmText = "清空",
            onConfirm = {
                confirmClear = false
                val chat = viewing ?: return@ConfirmDialog
                scope.launch {
                    val port = AgentClient.findPort()
                    val ok = port != null && AgentClient.memoryClearChat(port, chat.chatId, chat.characterId)
                    AppToast.show(ctx, if (ok) "已清空" else "清空失败", Toast.LENGTH_SHORT)
                    if (ok) {
                        reloadDetail()
                        refresh()
                    }
                }
            },
            onDismiss = { confirmClear = false },
        )
    }

    if (rebuildOpen) {
        ConfirmDialog(
            title = "重新整理这个聊天",
            // The price differs by an order of magnitude between the two modes, so the sentence
            // says which one this press will run: the rows the current rules already wrote are
            // kept, and only the head before them is read again.
            message = run {
                val from = detail?.rebuildFrom ?: 0
                val kept = detail?.currentRows ?: 0
                val layers = viewing?.messageCount ?: 0
                if (from > 0) {
                    "将删除前 $from 层中由模型生成的摘要、条目、场景和关系，并从第 1 层重读到第 $from 层。" +
                        "第 ${from + 1} 层之后的 $kept 条记忆保留。\n\n" +
                        "手写记忆、固定记忆和已忘掉的话题不受影响。\n" +
                        "费用约为整本重读的 ${(from * 100 / layers.coerceAtLeast(1))}%。"
                } else {
                    "将删除由模型生成的摘要、条目、场景和关系，再从第 1 层重读整个聊天并重新整理" +
                        "（共 $layers 层）。\n\n" +
                        "手写记忆、固定记忆和已忘掉的话题保留。重建完成前，这部分记忆为空。\n\n" +
                        "费用与首次整理相同。若只有少量新消息，使用菜单中的「整理」从上次位置继续。"
                }
            },
            confirmText = "重新整理",
            onConfirm = {
                rebuildOpen = false
                startRebuild()
            },
            onDismiss = { rebuildOpen = false },
        )
    }

    progressNoteRun?.let { run ->
        ConfirmDialog(
            title = "整理进度说明",
            message = "聊天末尾的两层留待有新消息时整理。每次运行按字数与调用次数分批，" +
                "未读部分下次继续。\n20 / 23、395 / 400 属正常进度，不是未完成。",
            confirmText = "知道了",
            dismissText = null,
            onConfirm = {
                SettingsState.markMemoryProgressNoteShown(ctx)
                progressNoteRun = null
                run()
            },
            onDismiss = {
                // Dismissing counts as read: the note explains a number, it is not a step the
                // reader has to accept before the pass may run.
                SettingsState.markMemoryProgressNoteShown(ctx)
                progressNoteRun = null
            },
        )
    }

    if (confirmOrphans) {
        ConfirmDialog(
            title = "删除已删除聊天的记忆",
            message = "有 ${state?.orphanCount ?: 0} 份记忆对应的聊天已删除，只占空间；" +
                "同名聊天可能接上旧记忆。\n\n删除后无法恢复。",
            confirmText = "永久删除",
            onConfirm = {
                confirmOrphans = false
                scope.launch {
                    val port = AgentClient.findPort()
                    val result = if (port != null) AgentClient.memoryCleanOrphans(port, purge = true) else null
                    val removed = result?.first
                    AppToast.show(
                        ctx,
                        when {
                            removed == null -> "清理没有完成"
                            removed == 0 -> "没有需要清理的"
                            else -> "已永久删除 $removed 个文件"
                        },
                        Toast.LENGTH_SHORT,
                    )
                    if (removed != null) refresh()
                }
            },
            onDismiss = { confirmOrphans = false },
        )
    }

    if (addOpen) {
        AddEntryDialog(
            onDismiss = { addOpen = false },
            onConfirm = { text, cues -> addEntry(text, cues) },
        )
    }

    if (forgetOpen) {
        ForgetDialog(
            forgotten = detail?.forgotten.orEmpty(),
            onDismiss = { forgetOpen = false },
            onConfirm = { topic -> forget(topic) },
        )
    }

    sourceTurns?.let { turns ->
        SourceDialog(turns = turns, onDismiss = { sourceTurns = null })
    }
}

/**
 * The one line this page carries about indexing: how many chats have none, and a button that queues
 * them all. Not a dialog — that would be one prompt per chat after a full archive pass.
 */
/**
 * How many archives were built to an older standard. No button on purpose: rebuilding one chat
 * costs a model pass over the whole transcript, and a single press that starts ten of them is not
 * a decision this row can make for the reader.
 */
/**
 * What this page is asking the reader to do, in one card.
 *
 * Two cards stacked here read as one broken card: their corner radii land a few dp apart while the
 * gap between them is tighter than the gap to every other card, so the eye sees a dent rather than
 * two separate things. One surface, one radius, a rule between the halves.
 */
@Composable
private fun MemoryTodoCard(
    staleCount: Int,
    warmPending: Int,
    warmRunning: Boolean,
    warmStarting: Boolean,
    warmTitle: String,
    filled: Int,
    total: Int,
    onWarmAll: () -> Unit,
    onOpenStale: () -> Unit,
) {
    val showWarm = warmPending > 0 || warmRunning || warmStarting
    val percent = if (total > 0) filled * 100 / total else 0
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            if (staleCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppRadius.sm))
                        .rowPressable(onClick = onOpenStale)
                        .padding(vertical = AppSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            staleCount.toString() + " 个聊天的记忆是早前整理的",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            // No version numbers here: the reader never saw a prompt, so the only
                            // thing worth saying is what changes if they act, and what it costs.
                            "只整理新增消息。" +
                                if (staleCount == 1) "进入该聊天可重新整理。"
                                else "选择一条查看。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AppDimens.iconSm),
                    )
                }
            }
            if (staleCount > 0 && showWarm) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = AppSpacing.sm),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            if (showWarm) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                warmRunning && total > 0 ->
                                    "正在建立 " + warmTitle + "：" + filled + " / " + total + " 句"
                                warmRunning && warmTitle.isNotBlank() -> "正在建立 " + warmTitle + "…"
                                warmRunning || warmStarting -> "正在建立…"
                                else -> warmPending.toString() + " 个聊天尚未建立检索索引"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (warmRunning || warmStarting) "建立任务在后台继续。"
                            else "建立后可按意思匹配；未建立时按字面匹配。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(AppSpacing.sm))
                    DialogConfirmButton(
                        // The bar is the thing being watched, so the running state carries the
                        // number itself rather than the word "working".
                        text = when {
                            warmRunning && total > 0 -> percent.toString() + "%"
                            warmRunning || warmStarting -> "建立中…"
                            else -> "全部建立"
                        },
                        enabled = !warmRunning && !warmStarting,
                        onClick = onWarmAll,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListPage(
    state: AgentClient.ChatMemoryState?,
    warmPending: Int,
    warmRunning: Boolean,
    /** The chat the index queue is on, and how many of its sentences are done. */
    warmTitle: String,
    warmFilled: Int,
    warmTotal: Int,
    warmStarting: Boolean,
    onWarmAll: () -> Unit,
    /** Archives built to an older standard than the running build writes. */
    staleChats: List<AgentClient.ChatMemory>,
    loading: Boolean,
    task: MemoryTaskState.Running?,
    enabled: Boolean,
    takeover: Boolean,
    takeoverReady: Boolean,
    onToggle: (Boolean) -> Unit,
    onToggleTakeover: (Boolean) -> Unit,
    ghostEnabled: Boolean,
    onToggleGhost: (Boolean) -> Unit,
    autoThreshold: Int,
    onPickAutoThreshold: (Int) -> Unit,
    memoryBudget: Int,
    onPickMemoryBudget: (Int) -> Unit,
    onRunAll: () -> Unit,
    /** Runs the action, or explains the progress counters first. */
    requestRun: (() -> Unit) -> Unit,
    onOpen: (AgentClient.ChatMemory) -> Unit,
    onBack: () -> Unit,
    embedding: MemoryEmbeddingConfig,
    recall: AgentClient.RecallStatus?,
    onOpenRetrieval: () -> Unit,
    onCleanOrphans: () -> Unit,
) {
    // Every pass the runtime is running, asked of it rather than remembered here. A pass started
    // from the task page, or before this screen was opened, is invisible to a flag held in this
    // page — and the list's whole job is answering "which chat needs a pass, which is having one".
    var passes by remember { mutableStateOf<Map<String, AgentClient.MemoryTask>>(emptyMap()) }
    LaunchedEffect(Unit) {
        while (true) {
            val port = AgentClient.findPort()
            passes = port?.let { AgentClient.memoryTasks(it) }
                ?.filter { it.kind == "organize" || it.kind == "rebuild" }
                // Keyed by both halves. By chat name alone, a pass on one character's chat
                // marked every other character's chat of that name as being read.
                ?.associateBy { memoryChatKey(it.characterId, it.chatId) }
                ?: emptyMap()
            delay(3_000L)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // The page has one job: which chat needs a pass. The switches and the index controls sit
        // one tap away behind the menu, so the list starts at the top of the screen.
        var menuOpen by remember { mutableStateOf(false) }
        var settingsOpen by remember { mutableStateOf(false) }
        var stalePickerOpen by remember { mutableStateOf(false) }
        // A page opened by a flag has to close by the same flag. Without this the system back key
        // left the whole memory screen from the settings page, so the list was gone and the reader
        // landed on the extensions tab with no way back except the button they had just pressed.
        BackHandler(enabled = settingsOpen) { settingsOpen = false }
        BackHandler(enabled = menuOpen) { menuOpen = false }
        if (settingsOpen) {
            MemorySettingsPage(
                enabled = enabled,
                onToggle = onToggle,
                takeover = takeover,
                onToggleTakeover = onToggleTakeover,
                ghostEnabled = ghostEnabled,
                onToggleGhost = onToggleGhost,
                autoThreshold = autoThreshold,
                onPickAutoThreshold = onPickAutoThreshold,
                memoryBudget = memoryBudget,
                onPickMemoryBudget = onPickMemoryBudget,
                state = state,
                embedding = embedding,
                recall = recall,
                onOpenRetrieval = onOpenRetrieval,
                onCleanOrphans = onCleanOrphans,
                onBack = { settingsOpen = false },
            )
            return@Column
        }

        PageTopBar(
            title = "记忆",
            onBack = onBack,
            actions = {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(if (task != null || passes.isNotEmpty()) "整理中…" else "全部整理")
                            },
                            enabled = task == null && passes.isEmpty() && enabled && takeover && takeoverReady,
                            onClick = {
                                menuOpen = false
                                requestRun(onRunAll)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("设置") },
                            onClick = {
                                menuOpen = false
                                settingsOpen = true
                            },
                        )
                    }
                }
            },
        )


        val listMotion = LocalFluentMotion.current
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = AppSpacing.xxl),
        ) {
            if (!enabled) {
                item {
                    // The switch itself lives in settings; a second copy here was two controls for
                    // one setting. What the list still owes the reader is why nothing is moving.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "记忆已关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { settingsOpen = true }) {
                            Text("前往设置开启", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            // No banner here: each row already says what is happening to that chat, and the
            // banner repeated the same sentence at the top of the list — two places claiming one
            // state, which reads as two states.
            if (state != null) {
                if (staleChats.isNotEmpty() || warmPending > 0 || warmRunning || warmStarting) {
                    item {
                        MemoryTodoCard(
                            staleCount = staleChats.size,
                            warmPending = warmPending,
                            warmRunning = warmRunning,
                            warmStarting = warmStarting,
                            warmTitle = warmTitle,
                            filled = warmFilled,
                            total = warmTotal,
                            onWarmAll = onWarmAll,
                            // A single stale chat opens straight into its archive. A list of them
                            // would otherwise open one at random while the card speaks in plural.
                            onOpenStale = {
                                if (staleChats.size == 1) onOpen(staleChats.first())
                                else stalePickerOpen = true
                            },
                        )
                    }
                }
            }
            item {
                SectionLabel("聊天")
            }

            when {
                loading -> items(8) { SkeletonRow(withSubtitle = true) }
                state == null -> item {
                    CenteredHint("记忆服务未运行，无法读取记忆状态")
                }
                state.chats.isEmpty() -> item {
                    CenteredHint("暂无聊天记录")
                }
                else -> items(
                    state.chats.sortedWith(
                        // Chats that are behind come first: the list exists to answer "which one
                        // needs a pass", and scan order is the order of the files on disk.
                        compareByDescending<AgentClient.ChatMemory> { it.pending }
                            .thenByDescending { it.updatedAt }
                    ),
                    key = { it.characterId + "/" + it.chatId }
                ) { chat ->
                    val pass = passes[memoryChatKey(chat.characterId, chat.chatId)]
                    ListRow(
                        icon = Icons.Outlined.StickyNote2,
                        modifier = rowShift(listMotion),
                        title = memoryChatTitle(chat.chatId),
                        // The row says what is happening to it right now; a pending count
                        // under a chat that is being read this second reads as though nothing is.
                        subtitle = if (pass != null) "整理中，" + pass.passLabel() else describe(chat),
                        onClick = { onOpen(chat) },
                        trailing = { ChatStatus(chat) },
                    )
                }
            }
        }

        if (stalePickerOpen) {
            ModalBottomSheet(onDismissRequest = { stalePickerOpen = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.xxl),
                ) {
                    Text("早前整理的聊天", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "这些记忆是按旧版本整理的。选择一条进入它的记忆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    staleChats.forEach { chat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.sm))
                                .rowPressable {
                                    stalePickerOpen = false
                                    onOpen(chat)
                                }
                                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                memoryChatTitle(chat.chatId),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(AppDimens.iconSm),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The three questions this screen answers: what it remembers, what it holds true, is it working. */
private enum class MemoryPane(val label: String) {
    Memory("记忆"),
    Context("上下文"),
    Diagnosis("诊断"),
}

/**
 * The numbers in one line, and the three panes under them.
 *
 * Three numbers and three words, in place of the four cards that each explained one of them.
 */
@Composable
private fun MemoryPaneHeader(
    detail: AgentClient.MemoryDetail,
    pending: Int,
    /** Floors the model stepped over. On the detail page they come from the row, not the detail. */
    skipped: Int,
    selected: MemoryPane,
    onSelect: (MemoryPane) -> Unit,
) {
    // Opaque page colour behind the whole strip, not just behind each tab: the three boxes are
    // drawn with four points between them, and with a transparent band the rows scrolling up showed
    // through those gaps — the page looked like it was cutting into its own header.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = AppSpacing.lg)
            .padding(bottom = AppSpacing.xs),
    ) {
        // The one line that states how far the archive has got. Which parts of it are news gets a
        // colour: work waiting is the accent, work the model stepped over is the warning tone, and
        // the rest is the plain count. One grey sentence made all three read the same.
        val colours = LocalAppColors.current
        Text(
            buildAnnotatedString {
                val plain = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
                val waiting = SpanStyle(color = colours.accent)
                val stepped = SpanStyle(color = colours.warning)
                fun part(text: String, style: SpanStyle = plain) {
                    withStyle(style) { append(text) }
                }
                part("${detail.messageCount} 条")
                if (detail.lastIndex < 0) {
                    part("，尚未整理")
                } else {
                    part("，已整理 ${detail.lastIndex + 1} 条")
                }
                if (pending > 0) part("，还有 $pending 条待整理", waiting)
                // What the pass holds back is not waiting work: the last exchange is still being
                // written. Saying "pending" there made a finished chat look two messages behind.
                val tail = detail.messageCount - detail.lastIndex - 1 - pending
                if (detail.lastIndex >= 0 && tail in 1..4) {
                    part("，末尾 $tail 条等新消息")
                }
                if (skipped > 0) {
                    part("，$skipped 条被跳过", stepped)
                }
            },
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
            MemoryPane.values().forEach { pane ->
                val active = pane == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(AppCardShape)
                        .background(
                            if (active) {
                                LocalAppColors.current.accentWash
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        )
                        .clickable { onSelect(pane) }
                        .padding(vertical = AppSpacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        pane.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) {
                            LocalAppColors.current.accent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/**
 * The switches and index controls, moved off the list page.
 *
 * A page rather than a sheet: the model and budget pickers open dialogs of their own, and stacking
 * a dialog on a sheet on a list is where taps get lost.
 */
@Composable
private fun MemorySettingsPage(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    takeover: Boolean,
    onToggleTakeover: (Boolean) -> Unit,
    ghostEnabled: Boolean,
    onToggleGhost: (Boolean) -> Unit,
    autoThreshold: Int,
    onPickAutoThreshold: (Int) -> Unit,
    memoryBudget: Int,
    onPickMemoryBudget: (Int) -> Unit,
    state: AgentClient.ChatMemoryState?,
    embedding: MemoryEmbeddingConfig,
    recall: AgentClient.RecallStatus?,
    onOpenRetrieval: () -> Unit,
    onCleanOrphans: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PageTopBar(title = "记忆设置", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = AppSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            item {
                SwitchCard(
                    enabled = enabled,
                    onToggle = onToggle,
                    takeover = takeover,
                    onToggleTakeover = onToggleTakeover,
                    ghostEnabled = ghostEnabled,
                    onToggleGhost = onToggleGhost,
                    autoThreshold = autoThreshold,
                    onPickAutoThreshold = onPickAutoThreshold,
                    memoryBudget = memoryBudget,
                    onPickMemoryBudget = onPickMemoryBudget,
                    state = state,
                )
            }
            item {
                RetrievalCard(
                    config = embedding,
                    recall = recall,
                    onOpen = onOpenRetrieval,
                )
            }
            state?.let { current ->
                item { SummaryLine(current.chats) }
                if (current.orphanCount > 0) {
                    item {
                        OrphanRow(
                            count = current.orphanCount,
                            bytes = current.orphanBytes,
                            onClean = onCleanOrphans,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    takeover: Boolean,
    onToggleTakeover: (Boolean) -> Unit,
    ghostEnabled: Boolean,
    onToggleGhost: (Boolean) -> Unit,
    autoThreshold: Int,
    onPickAutoThreshold: (Int) -> Unit,
    memoryBudget: Int,
    onPickMemoryBudget: (Int) -> Unit,
    state: AgentClient.ChatMemoryState?,
) {
    val ctx = LocalContext.current
    var thresholdPickerOpen by remember { mutableStateOf(false) }
    var budgetPickerOpen by remember { mutableStateOf(false) }
    var modelPickerOpen by remember { mutableStateOf(false) }
    // Read once per entry and after a save. The file is the source of truth for the pass, so the
    // row shows what that file says rather than a second copy of it in memory.
    var modelSummary by remember { mutableStateOf(MemoryModelPreferences.summary(ctx)) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.md)) {
                    Text("记忆", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 2.dp))
                    Text(
                        if (enabled) {
                            "每个聊天单独保存记忆；生成只带入本聊天的内容，整理也只读取本聊天。"
                        } else {
                            "关闭后不再带入记忆，整理同时停止。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                }
                AppSwitch(checked = enabled, onCheckedChange = onToggle)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.45f).padding(end = AppSpacing.md)) {
                    Text("用模型整理", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 2.dp))
                    Text(
                        if (takeover) {
                            "调用模型生成摘要、条目与场景；每次整理至少一次调用，产生费用。"
                        } else {
                            "只按原文归类。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                }
                AppSwitch(
                    checked = takeover,
                    enabled = enabled,
                    onCheckedChange = onToggleTakeover,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(modifier = Modifier.weight(1f).alpha(if (enabled && takeover) 1f else 0.45f).padding(end = AppSpacing.md)) {
                    Text("旧消息换成记忆", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 2.dp))
                    Text(
                        ghostHint(enabled = enabled, takeover = takeover),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                }
                AppSwitch(
                    checked = ghostEnabled && enabled && takeover,
                    enabled = enabled && takeover,
                    onCheckedChange = onToggleGhost,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(modifier = Modifier.weight(1f).alpha(if (enabled && takeover) 1f else 0.45f)) {
                    Text("整理用的模型", style = MaterialTheme.typography.bodySmall)
                    Text(
                        modelSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                }
                TextButton(
                    onClick = { modelPickerOpen = true },
                    enabled = enabled && takeover,
                ) { Text("设置") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.45f)) {
                    Text("每轮记忆上限", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (memoryBudget > 0) {
                            "每次生成最多带入 $memoryBudget 字。"
                        } else {
                            "按模型窗口的 6% 计算，范围 600–4000 字。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                }
                TextButton(
                    onClick = { budgetPickerOpen = true },
                    enabled = enabled,
                ) {
                    // The automatic ceiling is a share of a prompt window that only the last turn
                    // could measure, so it is named from that turn instead of being described.
                    // Without the number the two choices cannot be compared.
                    val automatic = state?.lastInjectedBudget ?: 0
                    Text(
                        when {
                            memoryBudget > 0 -> "$memoryBudget 字"
                            automatic > 0 -> "自动（$automatic 字）"
                            else -> "自动"
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Column(modifier = Modifier.weight(1f).alpha(if (enabled && takeover) 1f else 0.45f)) {
                    // The state lives on the button to the right, so putting it in the title as
                        // well read as "automatic (off) ... off" — the same word twice on one line.
                    Text("自动整理", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (autoThreshold > 0) {
                            "待整理达到 $autoThreshold 条且控制台空闲时启动，" +
                                "只处理最近半小时内生成过的聊天；其他聊天需手动整理。"
                        } else {
                            "仅手动整理。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                }
                TextButton(
                    onClick = { thresholdPickerOpen = true },
                    enabled = enabled && takeover,
                ) {
                    Text(if (autoThreshold > 0) "$autoThreshold 条" else "关闭")
                }
            }
            // The count in front of the reader: the switch decides when a pass may start, and that
            // decision is otherwise invisible until it happens. "Ten rows" read as "one pass per
            // ten rows" only because nothing on the page showed the backlog against it.
            if (autoThreshold > 0 && state != null) {
                val live = state.lastInjectedChat.removeSuffix(".jsonl")
                val liveCharacter = state.lastInjectedCharacter
                val backlog = if (live.isBlank()) null
                else state.chats.firstOrNull {
                    it.chatId == live && (liveCharacter.isBlank() || it.characterId == liveCharacter)
                }?.pending
                if (backlog != null) {
                    Text(
                        when {
                            backlog >= autoThreshold ->
                                "当前聊天待整理 $backlog 条，控制台空闲时启动。"
                            backlog > 0 ->
                                "当前聊天待整理 $backlog 条，还差 ${autoThreshold - backlog} 条启动自动整理。"
                            else -> "当前聊天没有待整理的消息。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (backlog >= autoThreshold) {
                            LocalAppColors.current.accent
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                state?.lastInjectedChars?.let {
                    // One bracket pair, not two in a row: the excerpt count belongs to the memory
                    // being described, and the time to the event, so they read as two clauses
                    // rather than as a bracket inside a bracket.
                    "上次生成带了 $it 字记忆" +
                        (if (state.lastInjectedExcerpts > 0) "（含 ${state.lastInjectedExcerpts} 段原文）" else "") +
                        "，" + formatTime(state.lastInjectedAt)
                } ?: if (enabled) "尚未带入记忆；开启后生成会带入本聊天的记忆。" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Cue coverage is the number that decides whether a reworded question can still reach
            // an older row. An archive can look complete and fail every paraphrase, and nothing
            // else on this page would show it.
            state?.takeIf { it.cueEntries > 0 }?.let { current ->
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    if (current.cuedEntries >= current.cueEntries) {
                        "换一种问法也能找到的记忆已覆盖 ${current.cueEntries} 条"
                    } else {
                        "换一种问法也能找到的记忆 ${current.cuedEntries} / ${current.cueEntries} 条"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // An empty archive has several causes and one next step. Naming the cause would be a
            // history lesson; the reader standing on an empty page only needs the step. The step
            // costs a model call, so it is only offered while the switch above allows one.
            if (state?.emptyArchive == true && takeover) {
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    "尚未整理。可从右上角菜单「全部整理」开始。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (thresholdPickerOpen) {
        AlertDialog(
            shape = AppPanelShape,
            onDismissRequest = { thresholdPickerOpen = false },
            title = { Text("自动整理的启动条数") },
            text = {
                Column {
                    Text(
                        "待整理达到此条数且控制台空闲时启动，每次启动至少一次模型调用。" +
                            "分批大小与提示条数无关。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    listOf(0, 10, 20, 30, 50, 100).forEach { floors ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.sm))
                                .rowPressable {
                                    onPickAutoThreshold(floors)
                                    thresholdPickerOpen = false
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = autoThreshold == floors,
                                onClick = {
                                    onPickAutoThreshold(floors)
                                    thresholdPickerOpen = false
                                },
                            )
                            Text(
                                if (floors == 0) "关闭，只手动整理" else "$floors 条",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { thresholdPickerOpen = false }) { Text("取消") }
            },
        )
    }
    if (budgetPickerOpen) {
        AlertDialog(
            shape = AppPanelShape,
            onDismissRequest = { budgetPickerOpen = false },
            title = { Text("记忆的字数上限") },
            text = {
                Column {
                    Text(
                        "每次生成最多带入的字数。数值越大，带入的条目越多，聊天原文可用空间越少。" +
                            "「自动」按模型窗口的 6% 计算（600–4000 字）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    SettingsState.MEMORY_BUDGET_CHOICES.forEach { chars ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(AppRadius.sm))
                                .rowPressable {
                                    onPickMemoryBudget(chars)
                                    budgetPickerOpen = false
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = memoryBudget == chars,
                                onClick = {
                                    onPickMemoryBudget(chars)
                                    budgetPickerOpen = false
                                },
                            )
                            Text(
                                if (chars == 0) "自动" else "$chars 字",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { budgetPickerOpen = false }) { Text("取消") }
            },
        )
    }
    if (modelPickerOpen) {
        MemoryModelDialog(
            initial = MemoryModelPreferences.load(ctx),
            onSave = { config ->
                MemoryModelPreferences.save(ctx, config)
                modelSummary = MemoryModelPreferences.summary(ctx)
                modelPickerOpen = false
            },
            onDismiss = { modelPickerOpen = false },
        )
    }
}

/**
 * Which connection the consolidation pass uses.
 *
 * Two choices, because there are two honest answers: the model already configured in the core,
 * or one picked for this job. The second is worth offering — consolidation reads whole chats and
 * is the most expensive thing the feature does — but it is not the default, since a reader who
 * configured one connection expects the console to use it.
 */
@Composable
private fun MemoryModelDialog(
    initial: MemoryModelConfig,
    onSave: (MemoryModelConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var reuse by remember { mutableStateOf(initial.reuse) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    val ready = reuse || (endpoint.isNotBlank() && model.isNotBlank())

    AlertDialog(
        shape = AppPanelShape,
        onDismissRequest = onDismiss,
        title = { Text("整理用的模型") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                RetrievalModeRow(
                    label = "复用核心里的模型",
                    note = "与核心使用同一个模型。",
                    selected = reuse,
                    onPick = { reuse = true },
                )
                RetrievalModeRow(
                    label = "单独指定",
                    note = "整理需读取整段聊天；可指定成本更低的模型，聊天页仍使用核心模型。",
                    selected = !reuse,
                    onPick = { reuse = false },
                )
                if (!reuse) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it.trim() },
                        label = { Text("接口地址") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        colors = appTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it.trim() },
                        label = { Text("模型名") },
                        placeholder = { Text("my-model-7b") },
                        singleLine = true,
                        colors = appTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim() },
                        label = { Text("密钥") },
                        singleLine = true,
                        colors = appTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    Text(
                        "地址与模型名填写完整后启用此连接；本机服务通常不需要密钥。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    // The fields are kept when switching back to reuse: the mode, not an empty
                    // box, is what decides, so nothing stale can be used by accident — and the
                    // alternative is retyping a key to compare two models.
                    onSave(MemoryModelConfig(reuse = reuse, endpoint = endpoint, model = model, apiKey = apiKey))
                },
            ) { Text("保存") }
        },
        dismissButton = {
            DialogDismissButton(onClick = onDismiss)
        },
    )
}

/**
 * A token count the way this page writes it.
 *
 * From ten thousand up the count is written in units of ten thousand: what it is compared
 * against is a window of that size, and five digits beside five digits cannot be compared at a
 * glance. The last digit is dropped when it is zero, so one figure does not change width between
 * two lines of the same block.
 */
internal fun tokenSize(value: Int): String {
    if (value < 10_000) return value.toString()
    val tenths = (value + 500) / 1000
    return if (tenths % 10 == 0) "${tenths / 10} 万" else "${tenths / 10}.${tenths % 10} 万"
}

private fun requestRoleName(role: String): String = when (role) {
    "system" -> "系统提示"
    "user" -> "用户"
    "assistant" -> "角色回复"
    else -> "其他"
}

/**
 * What the provider's refusal means, in the reader's terms.
 *
 * The provider answers with its own English sentence ("This model's maximum context length is
 * 163840 tokens"), and the chat page keeps none of it. A status alone is almost as useless, so
 * each case that has a next step is named: the key, the rate limit, the window, and a number
 * when the provider gave one.
 */
internal fun providerErrorText(status: Int, detail: String): String {
    val limit = Regex("\\b(\\d{4,})\\b").find(detail)?.groupValues?.get(1)?.toIntOrNull()
    val aboutLength = Regex("(?i)context|token|length|too long|prompt").containsMatchIn(detail)
    return when {
        status == 0 -> "没连上提供方"
        status == 401 || status == 403 -> "提供方拒绝了密钥（$status），检查 API 设置里的密钥"
        status == 429 -> "提供方限流（429）"
        status >= 500 -> "提供方出错（$status）"
        status == 400 && aboutLength && limit != null ->
            "超过提供方的上下文上限 ${tokenSize(limit)} token（400）"
        status == 400 && aboutLength -> "超过提供方的上下文上限（400）"
        status == 400 -> "提供方拒绝了请求（400）"
        else -> "提供方返回 $status"
    }
}

/**
 * The last request broken down by message.
 *
 * Memory is only one contributor and usually a small one; the window is mostly spent by the card,
 * the lore book and the history, none of which is ever shown as a size anywhere. The six largest
 * messages with their opening words are enough for the reader to name the part that has to go.
 */
@Composable
private fun RequestShapeBlock(state: AgentClient.ChatMemoryState) {
    val over = state.lastWindow > 0 && state.lastSpent > state.lastWindow
    Column {
        Text("上一楼请求", style = MaterialTheme.typography.bodySmall)
        Text(
            buildString {
                append("用了 ${tokenSize(state.lastSpent)} token")
                if (state.lastWindow > 0) append("，窗口 ${tokenSize(state.lastWindow)}")
                if (over) append("，超出 ${tokenSize(state.lastSpent - state.lastWindow)}")
                append("。")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (over) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (state.lastProviderStatus > 0) {
            Text(
                "这一楼没有生成：" + providerErrorText(state.lastProviderStatus, state.lastProviderDetail),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        when (state.lastSkipped) {
            "prompt-full" -> Text(
                "历史已经占满窗口，这一楼没有带记忆。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            "no-room" -> Text(
                "窗口剩下的空间不够放记忆，这一楼没有带记忆。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.lastParts.forEach { part ->
            Text(
                "${requestRoleName(part.role)} ${tokenSize(part.tokens)}：${part.head}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/**
 * How the excerpt half of the memory finds an old turn.
 *
 * Two ways to miss: the reader asked in other words, or the reader asked about something the
 * archive never recorded. Only the first is a retrieval problem, and this card exists to make the
 * difference visible — plus to say plainly when the local model is not loaded, because a silent
 * fallback to word matching is indistinguishable from a memory that simply forgot.
 */
@Composable
private fun RetrievalCard(
    config: MemoryEmbeddingConfig,
    recall: AgentClient.RecallStatus?,
    onOpen: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("原文检索", style = MaterialTheme.typography.bodySmall)
                    Text(
                        when (config.mode) {
                            MemoryRetrievalMode.KEYWORD ->
                                "按字面比对，仅带入含有相同文字的消息。"
                            MemoryRetrievalMode.LOCAL ->
                                "在本机计算向量并比对相似度；占用常驻内存。"
                            MemoryRetrievalMode.SERVICE ->
                                "向填写的地址发送句子以计算向量；每次生成发送一次请求。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                }
                TextButton(onClick = onOpen) { Text("设置") }
            }
            recallLine(config, recall)?.let { line ->
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One honest line about the source, or nothing when there is nothing useful to say. */
/**
 * What the recall check found, in one line. The percentage is the whole point, so it goes last
 * and carries the sample size beside it: "85%" of nothing would be a promise.
 */
private fun probeNote(probe: AgentClient.ProbeResult?): String = when {
    probe == null -> "用聊天原句检索，检查能否找回原楼层。"
    probe.reason == "too_short" -> "消息不足，整理后再测。"
    probe.checked == 0 -> "暂无可测内容。"
    // The limit is the search's result window, not the first six samples: each sampled turn is
    // searched on its own, and a hit means it landed inside that window.
    else -> "抽 ${probe.checked} 段原句检索，${probe.hits} 段出现在前 ${probe.limit} 条结果内（${probe.rate}%）。"
}

/**
 * How much of the archive a reworded question can still reach, and the one action that raises it.
 *
 * One call repairs a batch of twenty rows, so the count is shown beside the button: a bare number
 * reads as a free tidy-up, and this is a paid pass.
 */
@Composable
private fun CueCard(
    chat: AgentClient.ChatMemory,
    running: Boolean,
    onRun: () -> Unit,
) {
    val missing = (chat.liveEntries - chat.cuedEntries).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.md)) {
            Text("按意思匹配", style = MaterialTheme.typography.bodySmall)
            Text(
                "有 $missing 条只能按字面匹配。补齐后可按意思检索；" +
                    "每次 20 条，共约 ${(missing + 19) / 20} 次模型调用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (missing > 0) {
            DialogConfirmButton(
                text = if (running) "整理中…" else "补齐",
                enabled = !running,
                onClick = onRun,
            )
        }
    }
}

/**
 * The excerpt search, asked directly. One tap runs the same ranking the prompt uses — people on
 * stage, place, recency — so the lines shown are the lines the model would have been given.
 */
@Composable
private fun MemoryTestCard(
    search: AgentClient.MemorySearch?,
    searching: Boolean,
    onSearch: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    Column {
        Text("记忆测试", style = MaterialTheme.typography.bodySmall)
        Text(
            "输入一句话，查看会带入哪些原文。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("问题") },
            placeholder = { Text("输入一句话") },
            singleLine = true,
            colors = appTextFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sm),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                sceneLine(search),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = AppSpacing.md),
            )
            DialogConfirmButton(
                text = if (searching) "正在检索…" else "检索",
                enabled = !searching && text.isNotBlank(),
                onClick = { onSearch(text) },
                // Wide enough for the running label, so the field beside it keeps its width.
                modifier = Modifier.widthIn(min = 96.dp),
            )
        }
        val found = search ?: return@Column
        if (found.lines.isEmpty()) {
            Text(
                "未找到相关原文。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        found.lines.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/**
 * What the ranking was told before it looked, not what it found.
 *
 * Phrased as weighted-by rather than a bare list: a list of names above an empty result reads as
 * the matches, and an empty answer then looks self-contradictory to whoever asked.
 */
private fun sceneLine(search: AgentClient.MemorySearch?): String {
    if (search == null) return ""
    val parts = mutableListOf<String>()
    if (search.people.isNotEmpty()) parts += "在场 " + search.people.joinToString("、")
    if (search.place.isNotBlank()) parts += "地点 " + search.place
    return if (parts.isEmpty()) {
        "排序时没有在场人物和地点可用"
    } else {
        "排序时按" + parts.joinToString("、") + "加权"
    }
}

private fun recallLine(config: MemoryEmbeddingConfig, recall: AgentClient.RecallStatus?): String? {
    if (!config.retrieval) return null
    if (recall == null) return null
    if (config.mode == MemoryRetrievalMode.SERVICE) {
        return "接口：" + config.model.trim() + "（" + config.endpoint.trim() + "）"
    }
    return when {
        // "Ready" answers whether the model can respond, not what it costs to use. Turning this on
        // starts a pass over every sentence of the chat, and on a long role-play that pass runs for
        // tens of minutes on the device — so the line says the price as well as the state, instead
        // of reporting readiness and leaving the fan noise to explain the rest.
        recall.ok -> "本地模型已就绪。首次使用需为这个聊天建立索引，耗时随篇幅增加，期间占用处理器。"
        // The model is a separate download now. "Not loaded" would read as a fault when the
        // answer is a button one screen away.
        // The model ships inside the app, so "not downloaded" would send the reader looking for a
        // download that does not exist: what is wrong is the copy on disk, and reinstalling is
        // the one action that puts it back.
        recall.detail == "missing" -> "本地模型文件缺失，检索先按字面进行。"
        // A load was started within the last few seconds. Saying "not loaded" there reads as a
        // failure at the exact moment it is succeeding.
        recall.detail == "loading" -> "本地模型正在加载，期间按字面检索。"
        recall.detail.isNotBlank() && recall.detail != "ready" ->
            "本地模型未加载（${recall.detail}），检索按字面进行。"
        else -> "本地模型未加载，检索按字面进行。"
    }
}

/** State of the model download; owned by the dialog that starts it. */
private data class ModelInstallState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val speed: String = "",
    val message: String = "",
)

@Composable
private fun RetrievalDialog(
    initial: MemoryEmbeddingConfig,
    onDismiss: () -> Unit,
    onModelInstalled: () -> Unit,
    onSave: (MemoryEmbeddingConfig) -> Unit,
    /** First visit: the three ways are explained before they are chosen. */
    firstRun: Boolean = false,
) {
    var mode by remember { mutableStateOf(initial.mode) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var model by remember { mutableStateOf(initial.model) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var throttle by remember { mutableStateOf(initial.throttle) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Asked once, not per recomposition: the answer only changes through the button below, and a
    // file check on every keystroke in the service fields is work nobody asked for.
    var modelReady by remember { mutableStateOf(MemoryModelPack.installed(ctx)) }
    var install by remember { mutableStateOf(ModelInstallState()) }
    val ready = mode != MemoryRetrievalMode.SERVICE || (endpoint.isNotBlank() && model.isNotBlank())

    // Shared by both ways in: the outcome is "a directory appeared", and what to tell the page
    // afterwards does not depend on how the archive arrived.
    val settle: (String?) -> Unit = { failure ->
        if (failure != null) {
            install = ModelInstallState(message = failure)
        } else {
            modelReady = true
            install = ModelInstallState()
        }
    }

    // Asked after the session is open, not when the files land. The card reads its line from the
    // engine, and an engine that has not loaded yet answers "not-loaded" — so telling the page
    // first means it asks, gets "not loaded", and keeps showing that until the next refresh.
    val announce = suspend {
        withContext(Dispatchers.IO) { MemoryEmbedding.retry(ctx) }
        onModelInstalled()
    }

    val download: () -> Unit = {
        if (!install.running) {
            install = ModelInstallState(running = true)
            scope.launch {
                val archive = MemoryModelPack.archiveFile(ctx)
                try {
                    withContext(Dispatchers.IO) { MemoryModelPack.clearStaging(ctx) }
                    DownloadTask(BuildConfig.MEMORY_MODEL_URL, archive) { progress, _, _, speed ->
                        install = install.copy(progress = progress, speed = speed)
                    }.start()
                    val failure = withContext(Dispatchers.IO) {
                        MemoryModelPack.installInto(archive, MemoryModelPack.dir(ctx))
                    }
                    archive.delete()
                    settle(failure)
                    if (failure == null) announce()
                } catch (error: Exception) {
                    archive.delete()
                    settle(error.message ?: "下载没有完成")
                }
            }
        }
    }

    // The same archive, handed over from wherever the reader already has it. A download that
    // cannot start — no network, a mirror that moved — is not a dead end, and neither is a phone
    // that reaches the internet only through another machine.
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            install = ModelInstallState(running = true)
            scope.launch {
                val copy = MemoryModelPack.archiveFile(ctx)
                val failure = withContext(Dispatchers.IO) {
                    runCatching {
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            copy.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                        } ?: error("读不到这个文件")
                        MemoryModelPack.installInto(copy, MemoryModelPack.dir(ctx))
                    }.getOrElse { it.message ?: "读不到这个文件" }
                }
                copy.delete()
                settle(failure)
                if (failure == null) announce()
            }
        }
    }

    AlertDialog(
        shape = AppPanelShape,
        onDismissRequest = onDismiss,
        title = { Text(if (firstRun) "选择检索方式" else "检索方式") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (firstRun) {
                    Text(
                        "决定如何从较早消息中检索内容，可在记忆设置中修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                }
                RetrievalModeRow(
                    label = "按字面匹配",
                    note = "仅带入含有相同文字的消息。",
                    selected = mode == MemoryRetrievalMode.KEYWORD,
                    onPick = { mode = MemoryRetrievalMode.KEYWORD },
                )
                RetrievalModeRow(
                    label = "按意思匹配（本机模型）",
                    note = "在本机计算向量并比对相似度；占用常驻内存，首次使用较慢。",
                    selected = mode == MemoryRetrievalMode.LOCAL,
                    onPick = { mode = MemoryRetrievalMode.LOCAL },
                    // Inside the option it configures. Standing between this option and the
                    // next one it read as belonging to neither, and a switch the reader cannot
                    // place is a switch they will not touch.
                    body = if (mode == MemoryRetrievalMode.LOCAL) {
                        {
                            Spacer(Modifier.height(AppSpacing.sm))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.md)) {
                                    Text("建立索引时降低占用", style = MaterialTheme.typography.bodySmall)
                                    // A title and its explanation were set flush against each other, and
                                    // the paragraph then ran into the switch: the pair read as one dense
                                    // block at the moment the reader has to decide something.
                                    Spacer(Modifier.height(AppSpacing.xs))
                                    Text(
                                        "开启后分批计算，发热与卡顿减少，索引耗时增加；" +
                                            "关闭后连续计算，耗时较短，占用较高。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                AppSwitch(checked = throttle, onCheckedChange = { throttle = it })
                            }
                        }
                    } else {
                        null
                    },
                )
                if (mode == MemoryRetrievalMode.LOCAL && !modelReady) {
                    ModelDownloadBlock(
                        state = install,
                        onDownload = download,
                        onPickFile = { pickArchive.launch(arrayOf("application/zip", "application/octet-stream")) },
                    )
                }
                RetrievalModeRow(
                    label = "外部向量接口",
                    note = "兼容 /embeddings 的接口；每次生成发送一次请求，按服务额度计费。",
                    selected = mode == MemoryRetrievalMode.SERVICE,
                    onPick = { mode = MemoryRetrievalMode.SERVICE },
                )
                if (mode == MemoryRetrievalMode.SERVICE) {
                    Spacer(Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it.trim() },
                        label = { Text("接口地址") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        colors = appTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it.trim() },
                        label = { Text("模型名") },
                        placeholder = { Text("my-embed-model") },
                        singleLine = true,
                        colors = appTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim() },
                        label = { Text("密钥") },
                        singleLine = true,
                        colors = appTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    "地址与模型名填写完整后启用；否则使用本机模型。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = {
                    onSave(
                        MemoryEmbeddingConfig(
                            retrieval = mode != MemoryRetrievalMode.KEYWORD,
                            // Leaving the address in place while picking the local model would keep
                            // the service alive; the switch is what the engine reads.
                            endpoint = if (mode == MemoryRetrievalMode.SERVICE) endpoint else "",
                            model = if (mode == MemoryRetrievalMode.SERVICE) model else "",
                            apiKey = if (mode == MemoryRetrievalMode.SERVICE) apiKey else "",
                            autoIndex = initial.autoIndex,
                            throttle = throttle,
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            DialogDismissButton(onClick = onDismiss)
        },
    )
}

/**
 * The download prompt that sits under the local model option.
 *
 * It is part of the option rather than a separate page because the model has no meaning on its
 * own: it is what "local model" needs in order to work, and the cost of saying yes is one tap and
 * a wait, shown here rather than discovered later by a memory that quietly kept using words.
 */
@Composable
private fun ModelDownloadBlock(
    state: ModelInstallState,
    onDownload: () -> Unit,
    onPickFile: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.xxl, end = AppSpacing.xs, bottom = AppSpacing.xs),
        shape = RoundedCornerShape(AppRadius.sm),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)) {
            when {
                state.running -> {
                    Text(
                        "正在下载模型" + if (state.speed.isBlank()) "" else "（${state.speed}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(AppSpacing.xs))
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        if (state.progress > 0f) "${(state.progress * 100).toInt()}%" else "正在连接",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.message.isNotBlank() -> {
                    Text(
                        "下载没有完成：${state.message}",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.error,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDownload) { Text("重试") }
                        TextButton(onClick = onPickFile) { Text("从文件安装") }
                    }
                }
                else -> {
                    Text(
                        "模型随应用提供，在本机计算向量。文件缺失时可重新下载或从文件安装。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Both ways in, from the start: a reader whose network cannot reach the
                    // download host should not have to fail once to discover the other door.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDownload) { Text("下载") }
                        TextButton(onClick = onPickFile) { Text("从文件安装") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RetrievalModeRow(
    label: String,
    note: String,
    selected: Boolean,
    onPick: () -> Unit,
    body: (@Composable () -> Unit)? = null,
) {
    Row(
        // Centred against a one-line option, but pinned to the top once the option carries a
        // block of its own: a radio halfway down three paragraphs is no longer marking the
        // first line it sits beside.
        verticalAlignment = if (body == null) Alignment.CenterVertically else Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            // This option opens a settings block, so the row is taller after it expands than
            // when the press starts; a size-derived ripple radius would then leave the corners
            // uncovered. A fixed radius large enough for the expanded row keeps the disc filling
            // the whole rounded plate at every height.
            .clip(RoundedCornerShape(AppRadius.sm))
            .rowPressable(radius = 480.dp, onClick = onPick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onPick)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
            )
            body?.invoke()
        }
    }
}

/**
 * The memory of chats that no longer exist.
 *
 * It is one line and one button: there is nothing to open, and the only ending worth offering is
 * deletion. Keeping a second move-to-trash path doubled the controls for a file the reader cannot
 * open from this page anyway.
 */
@Composable
private fun OrphanRow(count: Int, bytes: Long, onClean: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("有 $count 份记忆的聊天已删除", style = MaterialTheme.typography.bodySmall)
            Text(
                formatBytes(bytes) + "，不在列表里。删除后无法恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onClean,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("删除") }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
private fun SummaryLine(chats: List<AgentClient.ChatMemory>) {
    // Live rows only. `entries` counts the retired ones too, and the detail page they open does
    // not show those — so the total and the sum of the pages disagreed by however many rows a
    // pass had retired, which reads as lost memory.
    val entries = chats.sumOf { it.liveEntries }
    val pending = chats.sumOf { it.pending }
    val colours = LocalAppColors.current
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                append("${chats.size} 个聊天，$entries 条记忆，")
            }
            if (pending > 0) {
                withStyle(SpanStyle(color = colours.accent)) { append("$pending 条待整理") }
            } else {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append("没有待整理")
                }
            }
        },
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
    )
}

/**
 * The archive was written under an older set of rules, and only a re-read applies the current one.
 *
 * A pass appends: it reads floors the archive has never seen and leaves the rows already there
 * alone. So a chat organised months ago keeps that build's idea of what was worth writing, for as
 * long as the chat exists. The page says what the reader can act on — that the rules changed, and
 * that the chat would be read again — rather than the version numbers that decide it, which are
 * an implementation detail nobody chose and nobody can compare.
 */
@Composable
private fun StaleArchiveCard(
    floorCount: Int,
    running: Boolean,
    /** A model has to be callable: the rewind is only worth doing when a reading follows it. */
    canRebuild: Boolean,
    /**
     * Rows that do not say who they are about. They are the one gap the pass behind the button in
     * the bar can close without reading the chat again, so the card names it and points there
     * instead of leaving the rewind as the only visible way out.
     */
    untagged: Int,
    onRebuild: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("记忆是早前整理的", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                DialogConfirmButton(
                    text = "重新整理",
                    enabled = canRebuild && !running,
                    onClick = onRebuild,
                )
            }
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                buildString {
    // Written for someone who never saw a prompt and cannot edit one. "earlier rules"
                    // and version numbers mean nothing here: the only fact the reader needs is
                    // that the app got better at this after this chat was last done.
                    append("App 更新后整理方式有变化，需按当前方式重做。")
                    append("重做从第 1 层读起（")
                    append(floorCount)
                    append(" 层），耗时随篇幅增加。手写记忆、固定记忆和已忘掉的话题保留。")
                    if (untagged > 0) {
                        append("也可只补一部分：其中 ")
                        append(untagged)
                        append(" 条缺少人物标注。点上方「整理」只补这一块，不重读聊天。")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Whether what is missing from the index is genuinely uncomputable rather than work left over.
 *
 * A finished pass leaves a handful of sentences the model returns nothing for - very short
 * fragments, markup carried in from an imported page - and no number of retries changes that.
 * Those are a few lines out of however many thousand, never a share of the chat: 9 of 3225 reads
 * normal, while 15211 of 15224 is a run that never got going, and calling that "normal" would tell
 * the reader to stop while almost everything is still unindexed.
 */
private fun indexSettled(status: AgentClient.WarmStatus?): Boolean {
    val total = status?.total ?: 0
    val filled = status?.filled ?: 0
    if (total <= 0 || filled <= 0) return false
    return total - filled <= leftoverTolerance(total)
}

/**
 * The line under a running pass: which batch, or how far it has read.
 *
 * A pass reports a floor range only once its first batch lands; before that the counter is the
 * only number that moves, and a page that waits for the range says nothing for minutes.
 */
private fun AgentClient.MemoryTask.passLabel(): String = when {
    from >= 0 && to >= 0 -> "第 $batch 批，第 ${from + 1}–${to + 1} 层"
    total > 0 -> "已整理 $done / $total 层"
    else -> "正在读取聊天"
}

/**
 * How many sentences a finished pass may leave behind and still count as done.
 *
 * Counted in lines, not in percent: a short chat that loses three of twenty-three is fine, while a
 * long one short of a hundred is not, even though the second is the smaller fraction. Two percent
 * caps it so a big chat cannot write off a large share, and five is the floor because that many
 * fragments is ordinary in any chat.
 */
private fun leftoverTolerance(total: Int): Int =
    maxOf(5, minOf(SETTLED_LEFTOVER, total / 50))

/** The most sentences a finished pass may leave behind, however long the chat is. */
private const val SETTLED_LEFTOVER = 20

/**
 * The state of this chat's vector index.
 *
 * Reports the size of the job before it starts — "13,430 sentences" is the only honest way to say
 * what a run costs on this device — and then the progress, which continues while the reader is
 * elsewhere on the app.
 */
@Composable
private fun WarmCard(status: AgentClient.WarmStatus?, onWarm: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        // Tighter at the top than at the sides: the card starts right under the tab strip, and an
        // even padding left the label floating a further twelve points below its own border.
        Column(
            modifier = Modifier.padding(
                start = AppSpacing.md,
                end = AppSpacing.md,
                top = AppSpacing.xs,
                bottom = AppSpacing.md,
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("检索索引", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                // The button keeps its place while the index runs. Replacing it with a percentage
                // collapsed the card's right side the moment the reader pressed it; only the label
                // changes now, inside a width that fits the longest of them.
                val running = status?.running == true
                DialogConfirmButton(
                    text = when {
                        running -> "建立中…"
                        (status?.filled ?: 0) <= 0 -> "建立索引"
                        indexSettled(status) -> "重新建立"
                        else -> "继续建立"
                    },
                    enabled = !running && status != null,
                    onClick = onWarm,
                    modifier = Modifier.widthIn(min = 88.dp),
                )
            }
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                when {
                    status == null -> "正在读取索引状态"
                    status.running -> "正在建立：" + status.filled + " / " + status.total +
                        " 句，任务在后台继续。"
                    status.total == 0 -> "暂无可建立索引的内容。"
                    status.filled >= status.total -> "已建立 " + status.total + " 句。"
                    // Two different things wear the same shape here, and saying the wrong one
                    // wastes an hour: a few sentences that no pass can ever convert, or a run that
                    // stopped early with most of the chat still to do.
                    indexSettled(status) -> "已建立 " + status.filled + " / " + status.total +
                        " 句；其余 " + (status.total - status.filled) + " 句无法计算向量。"
                    else -> "已建立 " + status.filled + " / " + status.total +
                        " 句；其余 " + (status.total - status.filled) + " 句将在后台补齐。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Why the number stops where it does. Without this the short count reads as work still
            // owed, and it never moves no matter how many times the button is pressed: the
            // sentences left out are ones the model can make no vector of, and a retry asks the
            // same question again. They are not lost - the literal path still finds them.
            if (status != null && !status.running && status.filled > 0 && indexSettled(status) &&
                status.filled < status.total
            ) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "极短片段和网页代码无法计算有效向量，建立索引时跳过；按字面检索仍可命中。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
        }
    }
}

@Composable
private fun DetailPage(
    chat: AgentClient.ChatMemory,
    detail: AgentClient.MemoryDetail?,
    loading: Boolean,
    running: Boolean,
    progressText: String?,
    enabled: Boolean,
    /** Whether the running service already allows a model call; the switch alone is not enough. */
    takeoverReady: Boolean,
    onBack: () -> Unit,
    onRun: () -> Unit,
    /** Runs the action, or explains the progress counters first. */
    requestRun: (() -> Unit) -> Unit,
    onDeleteEntry: (AgentClient.MemoryEntry) -> Unit,
    onPin: (AgentClient.MemoryEntry, Boolean) -> Unit,
    onAddEntry: () -> Unit,
    onUndo: () -> Unit,
    onForget: () -> Unit,
    onClear: () -> Unit,
    onSource: (Int, Int) -> Unit,
    onOpenGraph: () -> Unit,
    onToggleDisabled: (Boolean) -> Unit,
    embedding: MemoryEmbeddingConfig,
    warm: AgentClient.WarmStatus?,
    onWarm: () -> Unit,
    probe: AgentClient.ProbeResult?,
    probing: Boolean,
    onProbe: () -> Unit,
    search: AgentClient.MemorySearch?,
    searching: Boolean,
    onSearch: (String) -> Unit,
    /** The version the running build writes, to compare this archive against. */
    currentPromptVersion: Int,
    onRebuild: () -> Unit,
    /** Set while the runtime is reading this chat: which batch, or how far it has got. */
    passLabel: String? = null,
    /** Shown as a toast when the button is pressed with nothing to do. */
    onExplain: (String) -> Unit = {},
    /**
     * The last generation's own numbers. The trace belongs to whichever chat generated last, so
     * the page shows it only for that chat: a request measured on another chat says nothing here.
     */
    request: AgentClient.ChatMemoryState? = null,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // The two actions that change or remove what this page is showing. They sat at the end of
        // a timeline that runs to a hundred-odd rows, so reaching them meant scrolling past
        // everything the reader had already read.
        var menuOpen by remember { mutableStateOf(false) }
        // A press that re-reads the chat from the top replaces every row the archive holds, so it is
        // named for what it does and asks first. Under the label for an incremental pass the same
        // press rewrote a 178-row archive without a word and left the reader watching an empty
        // timeline.
        var rewriteOpen by remember { mutableStateOf(false) }
        PageTopBar(
            title = memoryChatTitle(chat.chatId),
            onBack = onBack,
            actions = {
                // Three controls in a title bar, two of them rarely used: the bar had no room left
                // for the title itself. Everything that acts on this chat lives behind one menu.
                val busyNow = running || passLabel != null
                // Only the states in which a pass cannot start at all. Whether there is work is
                // the service's answer to give: predicting it here meant a reader who pressed the
                // button was told to wait for five more messages, when the floors that were left
                // out were the two the pass holds back on purpose.
                val blocked = when {
                    busyNow -> "正在整理这个聊天"
                    !enabled -> "记忆总开关关着"
                    !takeoverReady -> "模型接管是关的"
                    detail == null -> "还没读到这个聊天的记忆"
                    else -> ""
                }
                // Starting at the first floor is not a resume: the archive has nothing to compare
                // the chat against, so the pass reads it whole and rewrites what it holds.
                val rewritesAll = detail?.let { it.restarted && it.rebuildFrom <= 0 } == true
                if (rewriteOpen) {
                    ConfirmDialog(
                        title = "重新整理这个聊天",
                        message = "档案缺少可用的楼层指纹，将从第 1 层重读并重写全部记忆。" +
                            (detail?.entries?.size?.takeIf { it > 0 }?.let { "现有 $it 条会被替换。" } ?: ""),
                        confirmText = "重新整理",
                        onConfirm = {
                            rewriteOpen = false
                            onRun()
                        },
                        onDismiss = { rewriteOpen = false },
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = MaterialTheme.colorScheme.surface,
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when {
                                        busyNow -> "整理中…"
                                        rewritesAll -> "重新整理"
                                        else -> "整理"
                                    }
                                )
                            },
                            enabled = !busyNow,
                            onClick = {
                                menuOpen = false
                                when {
                                    blocked.isNotEmpty() -> onExplain(blocked)
                                    rewritesAll -> rewriteOpen = true
                                    else -> requestRun(onRun)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("关系图") },
                            enabled = detail != null,
                            onClick = {
                                menuOpen = false
                                onOpenGraph()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("忘掉一个话题") },
                            onClick = {
                                menuOpen = false
                                onForget()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清空此聊天的记忆") },
                            onClick = {
                                menuOpen = false
                                onClear()
                            },
                        )
                    }
                }
            },
        )
        when {
            loading -> CenteredHint("正在读取记忆…", progress = true)
            detail == null -> CenteredHint("无法读取记忆，记忆服务可能未运行")
            else -> {
                // Three pages, because one page meant scrolling past ten blocks of running
                // information to reach the rows — the thing this screen exists for. Each pane
                // answers one question: what does it remember, what does it think is true now,
                // and is it working.
                var pane by remember(detail.chatId) { mutableStateOf(MemoryPane.Memory) }
                MemoryPaneHeader(
                    detail = detail,
                    pending = detail.pending,
                    skipped = chat.skippedFloors,
                    selected = pane,
                    onSelect = { pane = it },
                )
                val groups = remember(detail.entries, detail.scenes) {
                    MemoryTimeline.group(detail.entries, detail.scenes)
                }
                val motion = LocalFluentMotion.current
                // The three panes swap their rows in place. Without a transition the old rows
                // vanish and the new ones appear between two frames, which reads as the page
                // being redrawn rather than turned. A short slide in the direction of travel is
                // enough: the reader already knows which way they tapped.
                AnimatedContent(
                    targetState = pane,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val shift = { full: Int -> if (forward) full / 10 else -full / 10 }
                        (fadeIn(tween(motion.normalMs, easing = motion.easing)) +
                            slideInHorizontally(
                                animationSpec = tween(motion.normalMs, easing = motion.easing),
                                initialOffsetX = shift,
                            )).togetherWith(
                            fadeOut(tween(motion.quickMs, easing = motion.easingOut)),
                        )
                    },
                    label = "memoryPane",
                ) { current ->
                    // Inner rows keep their original indentation; rebasing three hundred lines
                    // to save four spaces would bury the edit in whitespace.
                    val pane = current
                LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AppSpacing.lg,
                    end = AppSpacing.lg,
                    // The tab strip already carries four points below it; another eight put the
                    // first card far enough from the strip to read as a separate section.
                    top = AppSpacing.xs,
                    bottom = AppSpacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            ) {
                // First, because it answers the question the page raises before anything else:
                // why the numbers below are about to change on their own.
                if (passLabel != null) {
                    item {
                        // Card colour with a bar down the left, not the wash the selected options
                        // use: a pass is something the app is doing, not something the reader
                        // picked, and the tint made it read as a highlighted choice.
                        val accent = LocalAppColors.current.accent
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = AppCardShape,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = AppSpacing.md,
                                    vertical = AppSpacing.sm,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(28.dp)
                                        .background(accent, RoundedCornerShape(2.dp)),
                                )
                                Spacer(Modifier.width(AppSpacing.sm))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(AppSpacing.sm))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "正在整理这个聊天",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        passLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                // When the task island has no entry for this chat, the pass still has a progress
                // line. It sits with the status rather than under the tools, for the same reason
                // the failure does: a chat being read is the first thing the page should say.
                if (passLabel == null && progressText != null) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(AppSpacing.sm))
                            Text(
                                "正在整理，$progressText",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                // What went wrong comes first: this tab is opened to find out why the numbers
                // stopped, and a red line below six cards of tooling is the one thing a reader
                // scrolls past. The same order holds for the two other states that leave part of
                // the chat unread and for the archive that predates the current prompt.
                if (pane == MemoryPane.Diagnosis && detail.lastError.isNotEmpty()) {
                    item {
                        val wait = detail.retryAt - System.currentTimeMillis()
                        Column {
                            Text(
                                "上次整理未完成：" + memoryErrorText(detail.lastError),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            if (wait > 0) {
                                // Whose wait this is, and that the button is not affected. The old
                                // line named no subject, so it read as the app holding the feature
                                // back rather than the automatic pass backing off.
                                Text(
                                    "自动整理将在 " + retryInText(wait) + "后继续；期间可手动整理。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (pane == MemoryPane.Diagnosis && chat.skippedFloors > 0) {
                    item {
                        Column {
                            Text(
                                "有 ${chat.skippedFloors} 条被跳过",
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalAppColors.current.warning,
                            )
                            Text(
                                // The range, the reason and the fact that it will not be retried:
                                // without all three the row looks like a chat that is fully
                                // organised, and the reader keeps waiting for a gap that will not
                                // fill itself.
                                "第 ${chat.skippedFrom + 1}–${chat.skippedTo + 1} 层模型返回内容不符合格式，" +
                                    "已跳过。这些楼层未记录，也不会自动重试。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (pane == MemoryPane.Diagnosis && !enabled) {
                    item {
                        Text(
                            "当前只按原文归类。生成摘要与条目需开启「用模型整理」。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Above the cards that show what the archive holds: the reader is looking at rows
                // written to an older standard, and until they know that, the rows read as the
                // best this chat can do.
                if (pane == MemoryPane.Diagnosis && chat.staleAgainst(currentPromptVersion)) {
                    item {
                        StaleArchiveCard(
                            floorCount = chat.messageCount,
                            running = running,
                            canRebuild = enabled,
                            untagged = chat.untagged,
                            onRebuild = onRebuild,
                        )
                    }
                }
                // Above the switch: whether this chat can be found by meaning is the first
                // thing the page should answer, and it is the one item here that costs minutes.
                if (pane == MemoryPane.Diagnosis && embedding.retrieval) {
                    item { WarmCard(status = warm, onWarm = onWarm) }
                }
                // What the last request was made of. A request the provider refused for length
                // could not be explained from inside either app: the window is spent by a card
                // and a lore book that never appear as sizes, so the total looked arbitrary.
                if (pane == MemoryPane.Diagnosis && request != null && request.lastSpent > 0 &&
                    request.lastInjectedChat.removeSuffix(".jsonl") == chat.chatId &&
                    (request.lastInjectedCharacter.isBlank() || request.lastInjectedCharacter == chat.characterId)
                ) {
                    item { RequestShapeBlock(request) }
                }
                // Beside the check that costs minutes, one that costs nothing: quote turns of this
                // chat and see whether the excerpt search finds them again. No model call, so the
                // answer is there before the reader lifts their finger off the button.
                if (pane == MemoryPane.Diagnosis) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.md)) {
                            Text("原文自检", style = MaterialTheme.typography.bodySmall)
                            Text(
                                probeNote(probe),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        DialogConfirmButton(
                            text = if (probing) "正在检测…" else "检测",
                            enabled = !probing,
                            onClick = onProbe,
                            modifier = Modifier.widthIn(min = 96.dp),
                        )
                    }
                }
                // A row without cue words can only be reached by the words it happens to share. The
                // ratio was already on the settings page; the way to close it was not.
                // Only when there is a gap to close. A card that reports "all covered" is a block
                // of noise on a page the reader opens to find what is wrong.
                if (pane == MemoryPane.Diagnosis && chat.cuedEntries < chat.liveEntries) item {
                    CueCard(chat = chat, running = running, onRun = onRun)
                }
                // The same search the prompt runs, asked by hand: typing a question here returns the
                // lines the block would have carried, with the people and the place it ranked them by.
                if (pane == MemoryPane.Diagnosis) item {
                    MemoryTestCard(search = search, searching = searching, onSearch = onSearch)
                }
                if (pane == MemoryPane.Diagnosis) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f).padding(end = AppSpacing.md)) {
                            Text("此聊天使用记忆", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 2.dp))
                            Text(
                                if (chat.disabled) {
                                    "已关闭：整理与带入均已停止。打开后从上次位置继续。"
                                } else {
                                    "关闭后停止整理与带入；重新打开时从原位置继续。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = !chat.disabled,
                            onCheckedChange = { onToggleDisabled(!it) },
                        )
                    }
                }
                val pinned = detail.entries.filter { it.pinned }
                // Always shown, even with nothing pinned: this card carries the only way to write a
                // row the pass never noticed, and a card that appears only once something is pinned
                // hides that way from the reader who needs it.
                if (pane == MemoryPane.Memory) item {
                    PinnedCard(
                        entries = pinned,
                        onUnpin = { onPin(it, false) },
                        onSource = onSource,
                        onAdd = onAddEntry,
                    )
                }
                if (pane == MemoryPane.Context) {
                    item { DigestCard(detail) }
                }
                if (pane == MemoryPane.Context) detail.state?.let { view ->
                    item { StateCard(view, onSource) }
                }
                if (pane == MemoryPane.Context && detail.entities.isNotEmpty()) {
                    item { CastCard(detail.entities) }
                }
                if (pane == MemoryPane.Context && detail.attributes.isNotEmpty()) {
                    item { AttributeCard(detail.attributes) }
                }
                if (pane == MemoryPane.Context && detail.events.isNotEmpty()) {
                    item { EventsCard(detail.events) }
                }
                if (pane == MemoryPane.Diagnosis && (detail.journal.isNotEmpty() || detail.forgotten.isNotEmpty())) {
                    item {
                        EditsCard(
                            journal = detail.journal,
                            forgotten = detail.forgotten,
                            onUndo = onUndo,
                        )
                    }
                }
                if (pane == MemoryPane.Memory) item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "记忆时间线",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            timelineCount(detail),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (pane == MemoryPane.Memory && detail.entries.isEmpty() && detail.scenes.isEmpty()) {
                    item {
                        Text(
                            "尚未整理。可从右上角菜单「整理」开始。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (pane == MemoryPane.Memory) {
                    itemsIndexed(
                        items = groups,
                        key = { _, group -> "${group.from}-${group.to}" },
                    ) { _, group ->
                        TimelineGroupCard(
                            group = group,
                            onDelete = onDeleteEntry,
                            onPin = onPin,
                            onSource = onSource,
                            modifier = rowShift(motion),
                        )
                    }
                }
            }
                }
            }
        }
    }
}

/**
 * The state the model is told about the present, slot by slot. A value is only useful if the
 * reader can tell when it became true, so every row carries the floor it started at.
 */
@Composable
private fun StateCard(state: AgentClient.MemoryState, onSource: (Int, Int) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("当前状态", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.source == "ledger") "按聊天内容推断" else "按整理结果",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(AppSpacing.xs))
            if (state.time.isNotEmpty() || state.location.isNotEmpty()) {
                Row {
                    StateField("时间", state.time, Modifier.weight(1f))
                    Spacer(Modifier.width(AppSpacing.sm))
                    StateField("地点", state.location, Modifier.weight(1f))
                }
                Spacer(Modifier.height(AppSpacing.xs))
            }
            val cast = castOnly(state.present)
            if (cast.isNotEmpty()) {
                StateHeading("在场")
                Text(cast.joinToString("、"), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(AppSpacing.xs))
            }
            StateFacts("人物", state.people, onSource)
            StateFacts("物品", state.items, onSource)
            StateFacts("着装", state.wear, onSource)
            StateFacts("约定", state.promises, onSource)
            StateFacts("关系", state.relations, onSource)
        }
    }
}

/**
 * The cast list as the model wrote it carries the room and descriptions of people as well as
 * names. A phrase of five characters or more is a description, and one ending in a place word
 * names where the scene stands, so neither belongs beside the people standing in it.
 */
private fun castOnly(names: List<String>): List<String> = names.map { it.trim() }.filter { name ->
    name.length in 2..4 && "内里外中上下边口".indexOf(name.last()) < 0
}

@Composable
private fun StateField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "未记录" }, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StateHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StateFacts(title: String, facts: List<AgentClient.MemoryFact>, onSource: (Int, Int) -> Unit) {
    if (facts.isEmpty()) return
    StateHeading(title)
    facts.forEach { fact ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .then(
                    if (fact.from >= 0) {
                        Modifier.clickable { onSource(fact.from, fact.to) }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(fact.title, style = MaterialTheme.typography.bodyMedium)
                if (fact.note.isNotEmpty()) {
                    Text(
                        fact.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (fact.from >= 0) {
                Text(
                    floorLabel(fact.from, fact.to, fact.seen, fact.conflict),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (fact.conflict) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(AppSpacing.xs))
}

/**
 * Floors are stored zero-based; the reader counts messages from one. `seen` is the floor the value
 * was last confirmed at, so a slot nobody has restated for a long stretch reads as older than the
 * scene; `conflict` marks two writers that disagreed at the same floor and both versions were kept.
 */
private fun floorLabel(from: Int, to: Int, seen: Int, conflict: Boolean): String {
    val range = if (to >= 0) "第 ${from + 1}–${to + 1} 层" else "第 ${from + 1} 层起"
    val parts = mutableListOf(range)
    if (seen > from) parts += "第 ${seen + 1} 层确认"
    if (conflict) parts += "并存"
    return parts.joinToString("，")
}

/** The floors a digested row covers, as a prefix the eye can scan past. */
private fun floorHead(from: Int, to: Int): String = when {
    from < 0 -> ""
    to >= from -> "第 ${from + 1}–${to + 1} 层｜"
    else -> "第 ${from + 1} 层起｜"
}

@Composable
private fun DigestCard(detail: AgentClient.MemoryDetail) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            if (detail.arcs.isNotEmpty()) {
                // Reference material, read once at most. Open by default it pushed the timeline
                // — the thing this page exists for — below the fold on every visit.
                CollapsibleSection(
                    label = "更早的经过（${detail.arcs.size} 段）",
                    content = detail.arcs.joinToString("\n\n") { arc -> floorHead(arc.from, arc.to) + arc.text },
                )
                Spacer(Modifier.height(AppSpacing.sm))
            }
            Text("滚动摘要", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                detail.digest.ifBlank { "尚无摘要。整理后显示聊天经过。" },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (detail.summaries.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.sm))
                CollapsibleSection(
                    label = "分段存档（${detail.summaries.size} 段）",
                    content = detail.summaries.takeLast(12)
                        .joinToString("\n\n") { row -> floorHead(row.from, row.to) + row.text },
                )
            }
            val stats = buildList {
                add("${detail.entries.size} 条记忆")
                if (detail.scenes.isNotEmpty()) add("${detail.scenes.size} 段")
                if (detail.attributes.isNotEmpty()) add("${detail.attributes.size} 条属性")
                detail.entities.count { it.kind == "person" }.takeIf { it > 0 }?.let { add("$it 个人物") }
                detail.entities.count { it.kind == "place" }.takeIf { it > 0 }?.let { add("$it 个地点") }
            }.joinToString("｜")
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                stats,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(AppSpacing.sm))
            val progress = when {
                detail.lastIndex < 0 -> "尚未整理"
                detail.pending > 0 -> "已整理到第 ${detail.lastIndex + 1} 条，还有 ${detail.pending} 条待整理"
                else -> "已整理到第 ${detail.lastIndex + 1} 条"
            }
            Text(
                "共 ${detail.messageCount} 条消息，$progress" +
                    if (detail.updatedAt > 0) "，更新于 " + formatTime(detail.updatedAt) else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // What the pass holds back, counted apart from what is pending. The last exchange is
            // still being written, so it is not work waiting for a button; it joins the next read
            // once a new message arrives.
            val tail = detail.messageCount - detail.lastIndex - 1 - detail.pending
            if (tail > 0) {
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    "末尾 $tail 条等新消息",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Counts shown next to the timeline heading: the page should say how much it is holding. */
private fun timelineCount(detail: AgentClient.MemoryDetail): String {
    val parts = mutableListOf("${detail.entries.size} 条记忆")
    if (detail.scenes.isNotEmpty()) parts += "${detail.scenes.size} 段"
    return parts.joinToString("，")
}

/**
 * The rows the reader pinned. They are also in the timeline below, but a pinned row is the one
 * thing that must not be missed, and a reader who pinned it should not have to scroll to find out
 * whether it is still there.
 */
@Composable
private fun PinnedCard(
    entries: List<AgentClient.MemoryEntry>,
    onUnpin: (AgentClient.MemoryEntry) -> Unit,
    onSource: (Int, Int) -> Unit,
    onAdd: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("固定的记忆", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onAdd,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("添加一条", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                "固定记忆不参与淘汰；整理未记录的内容可在此手动补充。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entries.isEmpty()) {
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    "暂无固定记忆。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(AppSpacing.xs))
            entries.forEach { entry ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (entry.from >= 0) {
                            Text(
                                if (entry.to > entry.from) {
                                    "第 ${entry.from + 1}–${entry.to + 1} 层"
                                } else {
                                    "第 ${entry.from + 1} 层"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { onSource(entry.from, entry.to) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = { onUnpin(entry) },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                        ) {
                            Text("取消固定", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

/**
 * What changed recently and what the reader asked to forget. A pass retires rows on its own and a
 * reader deletes them by hand; before this card neither event left a trace on the page, so a row
 * that vanished looked like a bug rather than a decision.
 */
@Composable
private fun EditsCard(
    journal: List<AgentClient.MemoryJournalRow>,
    forgotten: List<String>,
    onUndo: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近的改动", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                if (journal.isNotEmpty()) {
                    TextButton(onClick = onUndo, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("撤销上一步", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (journal.isNotEmpty()) {
                Text(
                    "每次撤销最近一步。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            journal.take(5).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        formatTime(row.at),
                        modifier = Modifier.width(72.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        row.summary,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (forgotten.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    "已忘掉：" + forgotten.joinToString("、") + "。后续整理不会写入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Asks for the one word to forget. A topic is a name, a thing or a place: everything that mentions
 * it goes, including the sentences inside the summary and the scene that name it.
 */
/**
 * Writes one row by hand. A pass files only what the model noticed, and a detail it passed over had
 * no way in from anywhere on this page.
 */
@Composable
private fun AddEntryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var cues by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppPanelShape,
        title = { Text("添加一条记忆", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(400) },
                    label = { Text("这件事") },
                    minLines = 3,
                    maxLines = 6,
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AppSpacing.sm))
                OutlinedTextField(
                    value = cues,
                    onValueChange = { cues = it.take(60) },
                    label = { Text("检索线索") },
                    singleLine = true,
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    "检索线索是提问该事时可能出现的说法，3–5 个，用逗号分隔。该条固定，不参与淘汰。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            DialogConfirmButton(
                text = "添加",
                enabled = text.isNotBlank(),
                onClick = {
                    onConfirm(
                        text.trim(),
                        cues.split('，', ',', '、').map { it.trim() }.filter { it.isNotEmpty() }.take(6),
                    )
                },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ForgetDialog(
    forgotten: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = AppPanelShape,
        title = { Text("忘掉一个话题", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(24) },
                    label = { Text("话题、人名或物品") },
                    singleLine = true,
                    colors = appTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    "删除与该话题相关的记忆、属性、事件，以及摘要、场景和状态中的对应内容。" +
                        "后续整理不会写入；聊天原文保留，按字面检索仍可能命中。" +
                        "可在「最近的改动」中撤销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (forgotten.isNotEmpty()) {
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        "已经忘掉：" + forgotten.joinToString("、"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            DialogConfirmButton(
                text = "忘掉",
                enabled = input.isNotBlank(),
                onClick = { onConfirm(input.trim()) },
            )
        },
        dismissButton = {
            DialogDismissButton(text = "取消", onClick = onDismiss)
        },
    )
}

/**
 * One stretch of the timeline: the scene header when one was recorded, then its rows. Grouping is
 * what puts a date, a place and a cast above the facts that came from that stretch; a flat list
 * could only show a floor number.
 */
/**
 * Movement for a row that sits below content changing height.
 *
 * A lazy list re-lays out in one frame, so opening one card made every card under it jump down by
 * the height of the block that appeared. The open was animated and the jump was not, and the jump
 * is the part the eye registers.
 */
private fun LazyItemScope.rowShift(motion: FluentMotionSpec): Modifier =
    Modifier.animateItem(
        fadeInSpec = null,
        placementSpec = tween(motion.expandMs, easing = motion.easingMove),
        fadeOutSpec = null,
    )

@Composable
private fun TimelineGroupCard(
    group: MemoryTimelineGroup,
    onDelete: (AgentClient.MemoryEntry) -> Unit,
    onPin: (AgentClient.MemoryEntry, Boolean) -> Unit,
    onSource: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalFluentMotion.current
    // A stretch can carry twenty rows and every row is a sentence. Folded, the card answers what
    // a reader scanning the timeline is asking — which stretch, how much is in it, how much of it
    // is pinned — and leaves the sentences for the one they open.
    var expanded by remember(group.from, group.to) { mutableStateOf(false) }
    // Routine rows — a price, a colour, a brand — answer a lookup, not "what happened here".
    // Listed beside the rows that changed the story they bury them, so they sit behind their own
    // fold. Open threads, pinned rows and anything the slice raised to salience 2 stay in view.
    var detailOpen by remember(group.from, group.to) { mutableStateOf(false) }
    val minor = group.entries.filter { !it.pinned && it.kind != "open" && it.salience <= 1 }
    val minorIds = minor.map { it.id }.toSet()
    val major = group.entries.filterNot { it.id in minorIds }
    val pinned = group.entries.count { it.pinned }
    // A stretch with no scene row still has to say something while folded, and the first row is
    // the closest thing to a headline it owns.
    val summary = group.scene?.summary.orEmpty().trim()
        .ifEmpty { group.entries.firstOrNull()?.text.orEmpty().trim() }
    // The whole card opens it, not the heading alone: a header strip that answers to a tap inside
    // a card that does not reads as a dead area.
    //
    // The feedback is the card going down under the finger, not a tint: this surface grows from
    // three lines to a screenful while the press is still held, so anything drawn for the old
    // shape — a ripple disc, a fill — either stays behind or darkens a screenful of text. Scale
    // survives the resize because it is applied to whatever the card turns into.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = motion.cardPressSpring(),
        label = "memoryCardPress",
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(AppCardShape)
            .clickable(
                interactionSource = press,
                indication = null,
            ) { expanded = !expanded },
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppDimens.controlHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    groupHeadline(group),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(AppSpacing.sm))
                ExpandChevron(
                    expanded = expanded,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppDimens.iconInline),
                )
            }
            // The counts stay visible while folded, and they sit under the heading rather than
            // beside it: a place name is what makes this line long, and a number pushed against
            // it ends up centred between two wrapped lines.
            val accent = LocalAppColors.current.accent
            val plain = MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = plain)) { append("${group.entries.size} 条") }
                    if (pinned > 0) {
                        withStyle(SpanStyle(color = plain)) { append("，") }
                        withStyle(SpanStyle(color = accent)) { append("$pinned 条固定") }
                    }
                    if (minor.isNotEmpty()) {
                        withStyle(SpanStyle(color = plain)) { append("，") }
                        withStyle(SpanStyle(color = plain)) { append("${minor.size} 条细节") }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
            )
            // The folded preview and the opened block cross-fade on the same pair. Letting one
            // Text change its maxLines instead drops the row by the preview's height before the
            // block has grown, so opening reads as a jump followed by a grow.
            AnimatedVisibility(
                visible = !expanded,
                enter = motion.enterExpand(),
                exit = motion.exitShrink(),
            ) {
                Column {
                    if (summary.isNotEmpty()) {
                        Spacer(Modifier.height(AppSpacing.xs))
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = motion.enterExpand(),
                exit = motion.exitShrink(),
            ) {
                Column {
                    if (summary.isNotEmpty()) {
                        Spacer(Modifier.height(AppSpacing.xs))
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                    group.scene?.let { scene ->
                        if (scene.present.isNotEmpty()) {
                            Spacer(Modifier.height(AppSpacing.xs))
                            Text(
                                "在场：" + scene.present.joinToString("、"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (scene.event.isNotEmpty()) {
                            Text(
                                "变化：" + scene.event,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (major.isNotEmpty()) {
                        Spacer(Modifier.height(AppSpacing.sm))
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                            major.forEach { entry ->
                                TimelineEntryRow(
                                    entry = entry,
                                    onDelete = { onDelete(entry) },
                                    onPin = { onPin(entry, !entry.pinned) },
                                    onSource = onSource,
                                )
                            }
                        }
                    }
                    if (minor.isNotEmpty()) {
                        Spacer(Modifier.height(AppSpacing.sm))
                        Text(
                            if (detailOpen) "收起细节" else "${minor.size} 条细节",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(AppRadius.sm))
                                .clickable { detailOpen = !detailOpen }
                                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
                        )
                        AnimatedVisibility(
                            visible = detailOpen,
                            enter = motion.enterExpand(),
                            exit = motion.exitShrink(),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                                minor.forEach { entry ->
                                    TimelineEntryRow(
                                        entry = entry,
                                        onDelete = { onDelete(entry) },
                                        onPin = { onPin(entry, !entry.pinned) },
                                        onSource = onSource,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One fact inside a group: tags, the sentence, and the floors it came from. */
@Composable
private fun TimelineEntryRow(
    entry: AgentClient.MemoryEntry,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onSource: (Int, Int) -> Unit,
) {
    // One fact, one plate. A dozen rows stacked as bare paragraphs read as a wall; the update page
    // already separates a version's notes from the row that opened them, and the same break is
    // what lets a reader stop at the fact they came for.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.tags.isNotEmpty()) {
                    Text(
                        entry.tags.take(3).joinToString("，"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (entry.from >= 0) Spacer(Modifier.width(AppSpacing.xs))
                Text(
                    if (entry.from >= 0) {
                        if (entry.to > entry.from) {
                            "第 ${entry.from + 1}–${entry.to + 1} 层"
                        } else {
                            "第 ${entry.from + 1} 层"
                        }
                    } else {
                        ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (entry.from >= 0) {
                        Modifier.clickable { onSource(entry.from, entry.to) }
                    } else {
                        Modifier
                    },
                )
                // Plain labels rather than TextButtons: a button carries a 58dp minimum width, and
                // two of them beside the floor range pushed the last one onto a second line inside
                // a plate this narrow.
                Text(
                    if (entry.pinned) "取消固定" else "固定",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.pinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.sm))
                        .clickable(onClick = onPin)
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                )
                Text(
                    "删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(AppRadius.sm))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                )
            }
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Floor range, in-story date and time, place; a scene's first line. */
private fun sceneHeadline(scene: AgentClient.MemoryScene): String {
    val parts = mutableListOf<String>()
    if (scene.from >= 0) {
        parts += if (scene.to >= 0) "第 ${scene.from + 1}–${scene.to + 1} 层" else "第 ${scene.from + 1} 层"
    }
    val whenText = listOf(scene.date, scene.time).filter { it.isNotEmpty() }.joinToString(" ")
    if (whenText.isNotEmpty()) parts += whenText
    if (scene.location.isNotEmpty()) parts += scene.location
    return parts.joinToString("｜")
}

/** A window with no scene row still gets its floor range as its heading. */
private fun groupHeadline(group: MemoryTimelineGroup): String {
    val scene = group.scene
    if (scene != null) return sceneHeadline(scene)
    if (group.from < 0) return "未标楼层"
    return "第 ${group.from + 1}–${group.to + 1} 层"
}

/**
 * People and places the chat keeps coming back to, with how often. Derived from the rows above, so
 * the counts are a read and never a second extraction.
 */
@Composable
private fun CastCard(entities: List<AgentClient.MemoryEntity>) {
    val people = entities.filter { it.kind == "person" && it.name.isNotEmpty() }.take(8)
    val places = entities.filter { it.kind == "place" && it.name.isNotEmpty() }.take(6)
    if (people.isEmpty() && places.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            if (people.isNotEmpty()) {
                Text("人物", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(AppSpacing.xs))
                people.forEach { person ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(
                            person.name + if (person.role.isNotEmpty()) "——" + person.role else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val meta = buildList {
                            if (person.status.isNotEmpty()) add(person.status)
                            if (person.scenes > 0) add("出场 ${person.scenes} 段")
                            if (person.facts > 0) add("属性 ${person.facts} 条")
                            if (person.lastFrom >= 0) add("最近第 ${person.lastFrom + 1} 层")
                            if (person.aliases.isNotEmpty()) add("又称 " + person.aliases.joinToString("、"))
                        }.joinToString("，")
                        if (meta.isNotEmpty()) {
                            Text(
                                meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (places.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.xs))
                Text("地点", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(AppSpacing.xs))
                places.forEach { place ->
                    Text(
                        place.name + if (place.scenes > 0) "，出场 ${place.scenes} 段" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One current value per subject and predicate. The floor is the one the value started at, and a
 * later confirmation floor is shown when the value has been restated since.
 */
@Composable
private fun AttributeCard(attributes: List<AgentClient.MemoryAttribute>) {
    val grouped = attributes.filter { it.subject.isNotEmpty() && it.value.isNotEmpty() }
        .groupBy { it.subject }
        .entries
        .take(10)
    if (grouped.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Text("属性", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(AppSpacing.xs))
            grouped.forEach { (subject, rows) ->
                Text(subject, style = MaterialTheme.typography.bodyMedium)
                rows.take(6).forEach { row ->
                    val floor = buildList {
                        if (row.from >= 0) {
                            add(
                                if (row.to >= 0) {
                                    "第 ${row.from + 1}–${row.to + 1} 层"
                                } else {
                                    "第 ${row.from + 1} 层起"
                                },
                            )
                        }
                        if (row.seen > row.from) add("第 ${row.seen + 1} 层确认")
                    }.joinToString("，")
                    Text(
                        row.predicate + "：" + row.value + if (floor.isNotEmpty()) "（$floor）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * The turning points, newest first. The digest says how the story got here; this is the short list
 * of changes with the reason the digest drops, which is what a "why" turn is asking about.
 */
@Composable
private fun EventsCard(events: List<AgentClient.MemoryEvent>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Text("转折", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(AppSpacing.xs))
            events.takeLast(4).reversed().forEach { event ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(event.text, style = MaterialTheme.typography.bodyMedium)
                    val detail = buildList {
                        if (event.whenText.isNotEmpty()) add(event.whenText)
                        if (event.cause.isNotEmpty()) add("起因：" + event.cause)
                        if (event.effect.isNotEmpty()) add("结果：" + event.effect)
                    }.joinToString("，")
                    if (detail.isNotEmpty()) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** The chat turns a stored row was read from, so a value that looks wrong can be checked. */
@Composable
private fun SourceDialog(turns: List<AgentClient.SourceTurn>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
        shape = AppPanelShape,
        title = { Text("这段记忆的来源", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                turns.forEach { turn ->
                    Text(
                        "第 ${turn.index} 条，${turn.speaker}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(turn.text, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(AppSpacing.sm))
                }
            }
        },
        confirmButton = { DialogConfirmButton(text = "知道了", onClick = onDismiss) },
    )
}

/** The depth chip's label in Chinese numerals; past ten it falls back to the digit. */
private fun layerLabel(layer: Int): String =
    listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
        .getOrNull(layer - 1)?.plus("层") ?: "${layer}层"

@Composable
private fun LayerSelector(
    layers: List<Int>,
    counts: Map<Int, Int>,
    total: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        // A chip selects everything up to its depth, so the number printed on it is
        // cumulative. The plain label read as "this ring holds that many", which made the
        // widest chip look identical to the total chip when no deeper ring exists.
        val choices = buildList {
            add(Int.MAX_VALUE to "全部")
            layers.forEach { layer -> add(layer to layerLabel(layer) + "内") }
        }
        choices.forEach { (limit, label) ->
            val count = if (limit == Int.MAX_VALUE) total else counts[limit] ?: 0
            val active = selected == limit
            // Clip before the click modifier: the press ripple then follows the rounded shape
            // instead of spilling into a rectangle wider than the chip.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(AppRadius.sm))
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                    .clickable { onSelect(limit) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (count > 0) "$label $count" else label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                )
            }
        }
    }
}

/**
 * Full-screen map of one chat's memory. The list page has to stay scrollable, and the layout
 * needs the whole screen, so the map is its own page instead of a tab inside the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryGraphScreen(
    chat: AgentClient.ChatMemory,
    detail: AgentClient.MemoryDetail,
    onBack: () -> Unit,
    onSource: (Int, Int) -> Unit,
    loadFloor: suspend (Int) -> List<AgentClient.SourceTurn>?,
    onRebuilt: () -> Unit,
) {
    var showEntries by remember { mutableStateOf(false) }
    var showLive by remember { mutableStateOf(false) }
    var layerLimit by remember { mutableStateOf(Int.MAX_VALUE) }
    var showStretches by remember { mutableStateOf(false) }
    var graph by remember { mutableStateOf<MemoryGraph?>(null) }
    var selected by remember { mutableStateOf<MemoryNode?>(null) }
    var selectedEdge by remember { mutableStateOf<MemoryEdge?>(null) }
    var focusAnchor by remember { mutableStateOf<String?>(null) }
    var use3D by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerFloor by remember { mutableStateOf(0f) }
    var pickerTurns by remember { mutableStateOf<List<AgentClient.SourceTurn>?>(null) }
    var pickerLoading by remember { mutableStateOf(false) }
    var pickerHint by remember { mutableStateOf("") }
    var sentenceFloor by remember { mutableStateOf<Int?>(null) }
    var sentenceAnchors by remember { mutableStateOf<Set<String>?>(null) }
    var rebuilding by remember { mutableStateOf(false) }

    // Back peels one layer at a time — the sentence picker, a node or edge sheet, the stretch
    // list, the live view, focus — before it leaves the sea entirely. Sheets handle their own
    // dismissal, so these branches are the fallback that keeps the key from skipping out.
    BackHandler {
        when {
            pickerOpen -> pickerOpen = false
            selected != null -> selected = null
            selectedEdge != null -> selectedEdge = null
            showStretches -> showStretches = false
            showLive -> showLive = false
            focusAnchor != null -> focusAnchor = null
            else -> onBack()
        }
    }
    val mapScope = rememberCoroutineScope()
    // The live view is built on the same map with the entry dust left in: the network the last
    // reply was written from is mostly entries, and a map without them collapsed to one node.
    val withEntries = showEntries || showLive
    val liveHotIds = remember(detail.hot) { detail.hot.keys.toSet() }
    // Groups as the core wrote them, turned into "display name to group index" for the layout. The
    // sum is empty on an archive that has not been consolidated since circles existed.
    val circles = remember(detail.communities) {
        val out = HashMap<String, Int>()
        detail.communities?.groups?.forEachIndexed { index, group ->
            group.members.forEach { name -> out[name] = index }
        }
        out
    }
    LaunchedEffect(detail, withEntries, showLive) {
        graph = withContext(Dispatchers.Default) {
            MemoryGraphs.build(
                detail.state, detail.events, detail.entries, withEntries, detail.scenes, detail.entities,
                onlyIds = if (showLive) liveHotIds else null,
                circles = circles,
            )
        }
    }
    // The focused subnetwork is computed once here, above the column: the banner, the view and
    // the reason row on a node sheet all read the same spread. "This sentence" and a tapped
    // node feed the same engine; only the banner and the reason wording differ.
    val currentGraph = graph
    // The temporary network the last reply was written from: the server keeps it as heat per
    // node id, and the map can draw exactly that web instead of guessing.
    val liveIds = remember(detail.hot) {
        detail.hot.keys.mapNotNull { serverGraphId(it) }.toSet()
    }
    // Entries rarely touch each other: the web runs through the people, places and props they
    // share. So the live view keeps the hot nodes plus the hubs that link at least two of them —
    // without those bridges the network drew as scattered dots with no lines at all.
    val liveKeep = remember(currentGraph, liveIds) {
        val g = currentGraph
        if (g == null || liveIds.isEmpty()) {
            emptySet()
        } else {
            val bridges = g.edges
                .filter { it.from in liveIds || it.to in liveIds }
                .flatMap { listOf(it.from, it.to) }
                .filter { it !in liveIds }
                .groupingBy { it }
                .eachCount()
                .filterValues { it >= 2 }
                .keys
            liveIds + bridges
        }
    }
    val liveGraph = if (showLive && currentGraph != null && liveKeep.isNotEmpty()) {
        MemoryGraph(
            currentGraph.nodes.filter { it.id in liveKeep },
            currentGraph.edges.filter { it.from in liveKeep && it.to in liveKeep },
        )
    } else {
        null
    }
    val focusIds: Set<String>? = focusAnchor?.let { setOf(it) } ?: sentenceAnchors
    val focused = focusIds?.let { ids ->
        currentGraph?.let { g ->
            val budget = ((detail.messageCount + 49) / 50).coerceIn(30, 120)
            MemoryFocus.spread(g, ids, budget)
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        PageTopBar(title = "关系图", onBack = onBack)
        // An archive consolidated before circles existed has none, and the map then draws in plain
        // id order with nothing to explain why. The grouping is arithmetic over rows already on
        // disk, so the reader can get it here instead of waiting for the next pass.
        if (circles.isEmpty() && detail.entries.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
            ) {
                val computed = detail.communities != null
                val peopleSeen = detail.communities?.people ?: 0
                Text(
                    when {
                        rebuilding -> "正在按场面和事件分组…"
                        // Computed and empty is a different sentence from never computed: one is a
                        // fact about the chat, the other is a button the reader has not pressed.
                        computed && peopleSeen == 0 -> "记录中没有同时出现的人物，按记录顺序排列。"
                        computed -> "未找到确定关系（共 " + peopleSeen + " 个人物），按记录顺序排列。"
                        else -> "尚未计算关系，暂按记录顺序排列。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !rebuilding,
                    onClick = {
                        rebuilding = true
                        mapScope.launch {
                            val port = AgentClient.findPort()
                            if (port != null) {
                                AgentClient.memoryRebuildCircles(port, chat.chatId, chat.characterId)
                            }
                            rebuilding = false
                            onRebuilt()
                        }
                    },
                ) { Text("重建圈子") }
            }
        }
        val current = graph
        when {
            current == null -> CenteredHint("正在排布记忆…", progress = true)
            current.nodes.isEmpty() -> CenteredHint("暂无可用记忆，整理后生成关系图。")
            else -> {
                val shownGraph = focused?.let { MemoryGraph(it.nodes, it.edges) } ?: liveGraph ?: current
                if (showLive && liveGraph != null) {
                    val anchors = detail.hot.values.count { it >= 0.8f }
                    val linked = detail.hot.values.count { it < 0.8f }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "此刻的网络：${liveGraph.nodes.size} 个节点（锚点 $anchors，联想 $linked）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showLive = false }) {
                            Text("退出此刻", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (focused != null) {
                    val headline = when {
                        focusAnchor != null ->
                            "聚焦：" + (current.node(focusAnchor!!)?.label.orEmpty())
                        sentenceFloor != null -> "已定位到第 ${sentenceFloor!! + 1} 层"
                        else -> "聚焦"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$headline，${focused.nodes.size} 个节点" +
                                if (focused.capped) "（已按距离截断）" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            focusAnchor = null
                            sentenceFloor = null
                            sentenceAnchors = null
                        }) {
                            Text("退出聚焦", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                val depthNodes = current.nodes.filter { it.layer > 0 }
                val depths = depthNodes.map { it.layer }.distinct().sorted()
                if (depths.size >= 2 && focused == null && !showLive) {
                    // One chip per depth that actually exists, all first: an empty depth is a
                    // button that does nothing, and a chat that grew five rings needs five chips.
                    LayerSelector(
                        layers = depths,
                        counts = depths.associateWith { depth -> depthNodes.count { it.layer <= depth } },
                        total = current.nodes.size - 1,
                        selected = layerLimit,
                        onSelect = { layerLimit = it },
                    )
                }
                if (focused == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!showLive && liveIds.isNotEmpty()) {
                            TextButton(onClick = { showLive = true }) {
                                Text("此刻", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (!showLive) {
                            // The old label named the payload instead of the action, so a
                            // reader could not tell what pressing it would do.
                            TextButton(onClick = { pickerOpen = true }) {
                                Text("按楼层定位", style = MaterialTheme.typography.labelMedium)
                            }
                            if (detail.arcs.isNotEmpty() || detail.summaries.isNotEmpty()) {
                                TextButton(onClick = { showStretches = true }) {
                                    Text("阶段与摘要", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                if (use3D) {
                    MemoryGraph3DView(
                        graph = shownGraph,
                        showEntries = withEntries,
                        onToggleEntries = { showEntries = it },
                        onNode = { selected = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        selectedNode = selected,
                        selectedEdge = selectedEdge,
                        onEdge = { selectedEdge = it },
                        use3D = true,
                        onToggle3D = { use3D = it },
                        layerLimit = layerLimit,
                    )
                } else {
                    MemoryGraphView(
                        graph = shownGraph,
                        showEntries = withEntries,
                        onToggleEntries = { showEntries = it },
                        onNode = { selected = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        layerLimit = layerLimit,
                        selectedNode = selected,
                        selectedEdge = selectedEdge,
                        onEdge = { selectedEdge = it },
                        use3D = false,
                        onToggle3D = { use3D = true },
                    )
                }
            }
        }
    }

    if (showStretches) {
        ModalBottomSheet(onDismissRequest = { showStretches = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.xxl),
            ) {
                if (detail.arcs.isNotEmpty()) {
                    Text("剧情阶段", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(AppSpacing.xs))
                    detail.arcs.forEach { arc ->
                        Text(
                            floorHead(arc.from, arc.to) + arc.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(AppSpacing.md))
                }
                if (detail.summaries.isNotEmpty()) {
                    Text("分段摘要", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(AppSpacing.xs))
                    detail.summaries.forEach { row ->
                        Text(
                            floorHead(row.from, row.to) + row.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (detail.arcs.isEmpty() && detail.summaries.isEmpty()) {
                    Text("暂无阶段与摘要。整理后生成。")
                }
            }
        }
    }

    LaunchedEffect(pickerOpen, pickerFloor.toInt()) {
        if (pickerOpen) {
            pickerLoading = true
            pickerTurns = loadFloor(pickerFloor.toInt())
            pickerLoading = false
        }
    }

    if (pickerOpen) {
        ModalBottomSheet(onDismissRequest = { pickerOpen = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.xxl),
            ) {
                Text("定位到某一层", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "选择一层查看原文；确认后图中只保留与该层相关的记忆。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(AppSpacing.sm))
                val maxFloor = (detail.messageCount - 1).coerceAtLeast(0)
                Slider(
                    value = pickerFloor,
                    onValueChange = { pickerFloor = it.coerceIn(0f, maxFloor.toFloat()) },
                    valueRange = 0f..maxFloor.toFloat().coerceAtLeast(1f),
                )
                Text(
                    "第 ${pickerFloor.toInt() + 1} 层",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(AppSpacing.sm))
                when {
                    pickerLoading -> Text(
                        "正在读取该层…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    pickerTurns.isNullOrEmpty() -> Text(
                        "该层没有原文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> pickerTurns!!.forEach { turn ->
                        Text(
                            "第 ${turn.index + 1} 层｜${turn.speaker}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            turn.text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (pickerHint.isNotEmpty()) {
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        pickerHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(AppSpacing.sm))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { pickerOpen = false }) {
                        Text("取消")
                    }
                    TextButton(onClick = {
                        val floor = pickerFloor.toInt()
                        val built = graph
                        if (built == null) {
                            pickerHint = "关系图尚未就绪，请稍后重试。"
                        } else {
                            val anchors = MemoryFocus.anchorsForFloor(built, detail, floor)
                            if (anchors.isEmpty()) {
                                pickerHint = "该层未对应任何节点，可尝试相邻楼层。"
                            } else {
                                sentenceFloor = floor
                                sentenceAnchors = anchors
                                focusAnchor = null
                                pickerHint = ""
                                pickerOpen = false
                            }
                        }
                    }) {
                        Text("以此为锚")
                    }
                }
            }
        }
    }

    selected?.let { node ->
        val sheet = remember(node, graph) {
            val built = graph ?: return@remember null
            detail?.let { NodeDetailSheetData(MemoryNodeDetails.of(node, built, it)) }
        }
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            // A capped sheet keeps the map's upper half on screen: the focus dimming and the
            // highlight ring stay readable while the details are read.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.xxl),
            ) {
                if (sheet == null) {
                    Text(node.label, style = MaterialTheme.typography.bodyLarge)
                    return@Column
                }
                Text(
                    sheet.detail.kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    sheet.detail.title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (sheet.detail.subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sheet.detail.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (sheet.detail.floors.isNotEmpty() || node.conflict) {
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        listOf(sheet.detail.floors, if (node.conflict) "存在并存的版本" else "")
                            .filter { it.isNotEmpty() }
                            .joinToString("，"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (focused != null) {
                    val why = when {
                        sentenceAnchors?.contains(node.id) == true -> "本层提到或发生"
                        focusAnchor == node.id -> "锚点"
                        else -> focused.reasons[node.id]?.let { "经「${it.viaLabel}」线，${it.distance} 步" }
                            ?: "与锚点相关"
                    }
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        "为何在图中：$why",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (sheet.detail.facts.isNotEmpty()) {
                    Spacer(Modifier.height(AppSpacing.md))
                    sheet.detail.facts.forEach { row ->
                        NodeDetailFact(row)
                    }
                }

                sheet.detail.sections.forEach { section ->
                    Spacer(Modifier.height(AppSpacing.md))
                    Text(
                        section.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(AppSpacing.xs))
                    section.rows.forEach { row ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            if (row.label.isNotEmpty()) {
                                Text(
                                    row.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                row.value,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                val source = sheet.detail.source
                if (source != null) {
                    Spacer(Modifier.height(AppSpacing.md))
                    TextButton(onClick = {
                        selected = null
                        onSource(source.first, source.second)
                    }) {
                        Text("查看原文")
                    }
                }
                Spacer(Modifier.height(AppSpacing.xs))
                TextButton(onClick = {
                    focusAnchor = node.id
                    selected = null
                    selectedEdge = null
                }) {
                    Text("以此为中心")
                }
            }
        }
    }
    selectedEdge?.let { edge ->
        val sheet = remember(edge, graph) {
            val built = graph ?: return@remember null
            EdgeSheetData(MemoryEdgeDetails.of(edge, built, detail))
        }
        ModalBottomSheet(onDismissRequest = { selectedEdge = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.xxl),
            ) {
                if (sheet == null) {
                    Text("关联", style = MaterialTheme.typography.bodyLarge)
                    return@Column
                }
                Text(
                    sheet.detail.kindLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(AppSpacing.xs))
                Text(
                    sheet.detail.title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (sheet.detail.subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sheet.detail.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                sheet.detail.facts.forEach { row ->
                    Spacer(Modifier.height(AppSpacing.xs))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            row.label,
                            modifier = Modifier.width(72.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            row.value,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                sheet.detail.sections.forEach { section ->
                    Spacer(Modifier.height(AppSpacing.md))
                    Text(
                        section.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(AppSpacing.xs))
                    section.rows.forEach { row ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            if (row.label.isNotEmpty()) {
                                Text(
                                    row.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                row.value,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                val source = sheet.detail.source
                if (source != null) {
                    Spacer(Modifier.height(AppSpacing.md))
                    TextButton(onClick = {
                        selectedEdge = null
                        onSource(source.first, source.second)
                    }) {
                        Text("查看原文")
                    }
                }
            }
        }
    }
}

/** Server node ids to the ids the map uses; an attribute has no node of its own. */
private fun serverGraphId(serverId: String): String? = when {
    serverId.startsWith("entry:") -> serverId
    serverId.startsWith("event:") -> serverId
    serverId.startsWith("scene:") -> "scene"
    else -> null
}

/** Wrapper so the edge sheet body stays a plain render of one immutable model. */
private data class EdgeSheetData(val detail: EdgeDetail)

/** Wrapper so the sheet body stays a plain render of one immutable model. */
private data class NodeDetailSheetData(val detail: NodeDetail)

@Composable
private fun NodeDetailFact(row: NodeDetailRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            row.label,
            modifier = Modifier.width(72.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            row.value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CenteredHint(text: String, progress: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (progress) {
            // A spinner without a label reads as a page with nothing to say when the read is
            // slow.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private fun formatTime(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(at))

/** A wait stated the way a reader would say it, rounded up so it never reads as zero minutes. */
internal fun retryInText(ms: Long): String = when {
    ms <= 0L -> ""
    ms < 60_000L -> "不到 1 分钟"
    ms < 3_600_000L -> "${(ms + 59_999L) / 60_000L} 分钟"
    else -> "${(ms + 3_599_999L) / 3_600_000L} 小时"
}

/**
 * Subtitle: when the chat is from, how much it holds and the last failure. The state belongs to
 * the trailing label instead; repeating it here made each row say the same thing twice.
 */
private fun describe(chat: AgentClient.ChatMemory): String {
    val parts = mutableListOf<String>()
    memoryChatBranch(chat.chatId).takeIf { it.isNotEmpty() }?.let { parts += "分支「$it」" }
    memoryChatStamp(chat.chatId).takeIf { it.isNotEmpty() }?.let { parts += it }
    parts += "${chat.messageCount} 条消息"
    // The row carries when and how much. The entry count belongs to the chat's own page, and a
    // failure belongs in the status chip, where it is the one thing that reads as news.
    return parts.joinToString("，")
}

/** The single status a row carries, so the pending count is stated once. */
@Composable
private fun ChatStatus(chat: AgentClient.ChatMemory) {
    val progress = chat.progress
    when {
        // The batch is what tells "moving" from "stuck"; a bare spinner said neither.
        progress != null -> Text(
            if (progress.restarted) "重读中" else "第 ${progress.batch} 批",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        chat.busy -> CircularProgressIndicator(
            modifier = Modifier.size(AppDimens.iconMd),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        // A switched-off chat is not lagging behind: its backlog is deliberately not being read,
        // and a pending count on that row would read as work waiting to happen.
        chat.disabled -> Text(
            "已关闭记忆",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // A chat sitting out a backoff is waiting, not paused: the wait has an end, so the row
        // says when. Which pass is waiting is part of the sentence — a bare duration alone reads as a
        // restriction on the feature.
        chat.retryAt > System.currentTimeMillis() -> Text(
            "自动整理 " + retryInText(chat.retryAt - System.currentTimeMillis()) + "后继续",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        chat.pending > 0 -> Text(
            "${chat.pending} 条待整理",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        // A failed attempt is the row's one piece of news: the archive may still be up to date, but
        // the next pass is what the reader has to know about.
        chat.lastError.isNotEmpty() -> Text(
            "未完成：" + memoryErrorShort(chat.lastError),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        // A stepped-over stretch is not pending and not organised, so the row must not claim the
        // chat is finished.
        chat.skippedFloors > 0 -> Text(
            "${chat.skippedFloors} 条被跳过",
            style = MaterialTheme.typography.labelSmall,
            color = LocalAppColors.current.warning,
        )
        chat.lastIndex < 0 -> Text(
            "尚未整理",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Text(
            "已整理完成",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What to say after pressing undo.
 *
 * The three answers are different facts: nothing was ever recorded, the record is about an archive
 * that has been rebuilt since, or a change was taken back. Collapsing the middle one into the first
 * tells the reader their undo did not exist when in fact it became impossible.
 */
internal fun undoText(result: AgentClient.MemoryUndo?): String = when {
    result == null -> "记忆服务未返回结果"
    result.stale -> "该改动之后聊天被改写，无法撤销"
    result.summary.isBlank() -> "无可撤销的改动"
    else -> "已撤销：" + result.summary
}

/**
 * The line under the "hide what memory already covers" switch.
 *
 * It names the switch this one depends on only while that one is off. A reader who has already
 * turned it on is being told to do something they did — at the exact spot that is supposed to
 * explain what the switch would do.
 */
internal fun ghostHint(enabled: Boolean, takeover: Boolean): String =
    "较早消息由记忆转述，不再原样发送；最近对话保留原文，未记录的内容不会发送。" +
        if (enabled && takeover) "" else "需先开启「用模型整理」。"

/**
 * A chat id is "<character> - <yyyy-MM-dd>@<HH>h<mm>m<ss>s<ms>ms", and a branched chat keeps
 * going after that (" - Branch #1"). The list shows the character on the title line and the rest
 * on the next one; the raw id repeated all of it and wrapped over three lines.
 */
internal fun memoryChatTitle(chatId: String): String {
    val match = CHAT_ID.matchEntire(chatId.trim()) ?: return chatId
    return match.groupValues[1].trim().ifEmpty { chatId }
}

/** The chat's own timestamp, formatted for the subtitle; empty when the id carries none. */
internal fun memoryChatStamp(chatId: String): String {
    val match = CHAT_ID.matchEntire(chatId.trim()) ?: return ""
    return "${match.groupValues[2]}-${match.groupValues[3]}-${match.groupValues[4]} " +
        "${match.groupValues[5]}:${match.groupValues[6]}"
}

/** The branch a chat belongs to, when the character was branched; empty otherwise. */
internal fun memoryChatBranch(chatId: String): String {
    val match = CHAT_ID.matchEntire(chatId.trim()) ?: return ""
    return match.groupValues[9].trim()
}

/**
 * Engine failures are English sentences written for the log. The page translates the causes a
 * reader can act on and shows anything else as it is rather than guessing one.
 */
/**
 * The same failure in a handful of characters.
 *
 * The list row has room for about eight of them, and the full sentence is written for the diagnosis
 * page where there is room to explain. A row that only says "failed" makes the reader open the page
 * to find out whether the archive is broken or the provider was busy for a minute.
 */
internal fun memoryErrorShort(raw: String): String {
    val text = raw.trim()
    return when {
        text.isEmpty() -> ""
        text.startsWith("empty completion") -> "返回内容为空"
        text.startsWith("reply truncated") -> "返回内容被截断"
        text.startsWith("declined") -> "返回格式不符"
        text.startsWith("unparsable reply") -> "返回内容无法解析"
        text.contains("timeout", ignoreCase = true) ||
            text.contains("timed out", ignoreCase = true) -> "响应超时"
        else -> httpShort(text)
    }
}

/** Which side failed and whether waiting helps, in one word where possible. */
private fun httpShort(raw: String): String {
    val code = Regex("""HTTP (\d{3})""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        ?: return "原因见「诊断」页"
    return when {
        code == 401 || code == 403 -> "密钥无效"
        code == 429 -> "服务限流"
        code in 500..599 -> "服务过载"
        code == 404 -> "接口地址无效"
        code in 400..499 -> "服务拒绝请求"
        else -> "原因见「诊断」页"
    }
}

internal fun memoryErrorText(raw: String): String {
    val text = raw.trim()
    return when {
        text.isEmpty() -> ""
        text.startsWith("empty completion") -> "模型返回空内容"
        // The one failure the reader can actually fix by choosing differently: a model that stops at
        // its output ceiling answers with nothing, and retrying the same batch buys the same nothing.
        text.startsWith("reply truncated") ->
            "输出额度在整理中途耗尽，本批未完成。可改用非推理模型，或调小每批字数。"
        // A reply with no archive in it is not a broken object: the model answered with a
        // sentence instead. Calling that a format error sends the reader to retry a batch that
        // will answer the same way.
        text.startsWith("declined") -> "模型返回说明性文字，未按整理格式输出"
        text.startsWith("unparsable reply") -> "模型返回内容无法按格式解析"
        text.contains("timeout", ignoreCase = true) ||
            text.contains("timed out", ignoreCase = true) -> "模型响应超时"
        text.contains("401") || text.contains("403") -> "模型拒绝请求，检查 API 设置"
        else -> httpVerdict(text) ?: text
    }
}

/**
 * The verdict behind an "HTTP 503 {...}" line.
 *
 * The provider's body is an object with a request id in it; what the reader needs is which side
 * failed and whether waiting helps. Null when the line carries no status at all, so the caller
 * can fall back to the raw text.
 */
private fun httpVerdict(raw: String): String? {
    val code = Regex("""HTTP (\d{3})""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    return when {
        code == 429 -> "模型服务限流（HTTP 429），稍后重试"
        code in 500..599 -> "模型服务过载（HTTP $code），稍后重试"
        code in 400..499 -> "模型拒绝了这次请求（HTTP $code），检查模型名与 API 设置"
        else -> "模型服务返回 HTTP $code"
    }
}

private val CHAT_ID = Regex(
    """^(.*?) - (\d{4})-(\d{2})-(\d{2})@(\d{2})h(\d{2})m(\d{2})s(\d{3})ms(?: - (.*))?$"""
)
