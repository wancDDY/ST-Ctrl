package com.tavern.app.console.pages

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.components.ConsoleTabRow
import com.tavern.app.console.components.PageScaffold
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.LogColors
import com.tavern.app.log.TavernLog
import com.tavern.app.util.AppToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private fun ansiColorFor(code: String): Color = when (code) {
    TavernLog.C_RED -> LogColors.Error
    TavernLog.C_GREEN -> LogColors.Success
    TavernLog.C_YELLOW -> LogColors.Warning
    TavernLog.C_BLUE -> LogColors.Info
    TavernLog.C_MAGENTA -> LogColors.Trace
    TavernLog.C_CYAN -> LogColors.Debug
    TavernLog.C_GRAY -> LogColors.Muted
    else -> LogColors.Default
}

private val AnsiColourCodes = listOf(
    TavernLog.C_RED, TavernLog.C_GREEN, TavernLog.C_YELLOW, TavernLog.C_BLUE,
    TavernLog.C_MAGENTA, TavernLog.C_CYAN, TavernLog.C_GRAY
)

private val AnsiPattern = Regex("\u001b\\[[0-9;]*m")

// Timestamp of a line, dropped when comparing two lines so a repeat is
// recognised as the same message arriving again. Restored lines carry a date in
// front of the time, live ones only the time.
private val TimestampPattern = Regex("^\\s*(\\d{4}-\\d{2}-\\d{2}\\s+)?\\d{2}:\\d{2}:\\d{2}\\s*")

// Lines kept on screen. The whole log is a single text node, so this is what
// bounds the layout cost every time a line arrives; anything older is still on
// disk in tavern-server.log.
private const val DISPLAY_LINES = 2000

// Distance from the bottom, in pixels, that still counts as reading the tail.
private const val FOLLOW_SLACK_PX = 32

// A selection drag belongs to the text node and consumes the drag, so reaching
// the end of the visible lines would leave the rest of the log unreachable. This
// band of the viewport is what scrolls for as long as the finger stays in it.
private const val EDGE_BAND_PX = 96f
private const val EDGE_STEP_PX = 11f

/**
 * The two things this screen is opened for: what the tavern server itself printed,
 * and what the tavern page reported. The app's own bookkeeping (boot, page loads,
 * service relays) is left out on purpose: those messages repeat the same few lines
 * hundreds of times and bury the server's output.
 */
private enum class LogSource(val label: String) {
    SERVER("服务端"),
    PAGE("页面控制台"),
}

private fun LogSource.accepts(channel: TavernLog.Channel): Boolean = when (this) {
    LogSource.SERVER -> channel == TavernLog.Channel.SERVER
    LogSource.PAGE -> channel == TavernLog.Channel.PAGE
}

// Server chatter that repeats for as long as the tavern page is open. Kept out of
// the view because it arrives several times a second and buries everything else.
private val LogNoise = listOf(
    "ssl_client_socket",
    "handshake failed",
    "Adding missing config",
    "Migrating config values",
)

// Server output arrives with ANSI colour codes; map them to the console palette
// instead of letting the raw escape codes decide the colour.
private fun ansiToAnnotated(raw: String) = buildAnnotatedString {
    var i = 0
    var depth = 0
    while (i < raw.length) {
        when {
            raw.startsWith(TavernLog.C_RESET, i) -> {
                i += TavernLog.C_RESET.length
                if (depth > 0) {
                    depth--
                    try { pop() } catch (_: Exception) { }
                }
            }
            depth < 20 -> {
                val code = AnsiColourCodes.firstOrNull { raw.startsWith(it, i) }
                if (code != null) {
                    i += code.length
                    depth++
                    pushStyle(SpanStyle(color = ansiColorFor(code)))
                } else {
                    append(raw[i])
                    i++
                }
            }
            else -> {
                append(raw[i])
                i++
            }
        }
    }
}

private fun plainText(line: String) = AnsiPattern.replace(line, "")

/**
 * Folds runs of the same message into one line with a repeat count. A single
 * stuck subsystem can emit thousands of identical lines (the tavern page retries
 * a failed settings save every second); without this the one line that explains
 * the failure scrolls away in the flood.
 */
private fun collapseRepeats(lines: List<String>): List<String> {
    if (lines.isEmpty()) return lines
    val out = mutableListOf<String>()
    var key: String? = null
    var text = ""
    var count = 0

    fun flush() {
        out += if (count > 1) "$text   ×$count" else text
    }

    for (line in lines) {
        val current = TimestampPattern.replace(plainText(line), "")
        if (current == key) {
            count += 1
            continue
        }
        if (key != null) flush()
        key = current
        text = line
        count = 1
    }
    if (key != null) flush()
    return out
}

@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by TavernLog.entries.collectAsState()
    var source by remember { mutableStateOf(LogSource.SERVER) }
    var follow by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var edgeStep by remember { mutableFloatStateOf(0f) }
    // The framework's own selection stops growing once the drag leaves the lines
    // that were visible when it started, and a log is read by dragging across more
    // lines than fit on the screen, so both ends of the passage are tracked here.
    var anchor by remember { mutableIntStateOf(-1) }
    var cursor by remember { mutableIntStateOf(-1) }
    // Finger position in the text's own coordinates, which is what turns a scroll
    // into a longer passage: the view moves, the finger does not.
    var dragging by remember { mutableStateOf<Offset?>(null) }
    // Dragging one of the two handles on the passage. The finger position is kept in
    // the text's own coordinates for the same reason as above: the text moves, the
    // finger does not, and the end being dragged has to follow the text with it.
    var handleDrag by remember { mutableStateOf<HandleDrag?>(null) }
    var viewportPx by remember { mutableFloatStateOf(0f) }
    var pinned by remember { mutableStateOf<List<String>?>(null) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val scrollState = rememberScrollState()
    val selectionColor = LocalAppColors.current.accent.copy(alpha = 0.32f)

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) { TavernLog.pollServerLog(context) }
            TavernLog.pollNodeLog()
            delay(3000)
        }
    }

    // Unfolded, which is what the copy action hands over: the screen folds repeats
    // to stay readable, but a copied line should not stand in for the ones it hides.
    val shown = remember(entries, source) {
        entries.filter { source.accepts(it.channel) }
            .map { it.text }
            .filterNot { line -> LogNoise.any { line.contains(it) } }
            .takeLast(DISPLAY_LINES)
    }
    val displayLines = remember(shown) {
        collapseRepeats(shown).ifEmpty { listOf("${TavernLog.C_GRAY}暂无日志${TavernLog.C_RESET}") }
    }
    // Offsets into the text are only meaningful while the text stays as it was:
    // lines arriving under a passage would drag it onto other lines, so the lines
    // a selection was made on are held until it is dropped.
    val lines = pinned ?: displayLines
    // One Text for the whole log: a passage has to be a single text node, where a
    // per-line list would stop the drag at the line it started on.
    val body = remember(lines) {
        buildAnnotatedString {
            lines.forEach {
                append(ansiToAnnotated(it))
                append("\n")
            }
            withStyle(SpanStyle(color = LogColors.Success)) { append("> _") }
        }
    }

    val selStart = if (anchor < 0) -1 else minOf(anchor, cursor)
    val selEnd = if (anchor < 0) -1 else maxOf(anchor, cursor)

    fun dropSelection() {
        anchor = -1
        cursor = -1
        dragging = null
        handleDrag = null
    }

    /**
     * Word edge to snap [offset] to. Landing exactly on [other] would leave a passage
     * with nothing in it, which loses both the highlight and the handles, so the other
     * edge of the word is taken instead: a dragged end always covers the word it is
     * over.
     */
    fun wordEdgeFor(layout: TextLayoutResult, offset: Int, other: Int): Int {
        val word = layout.getWordBoundary(offset.coerceIn(0, body.length))
        val nearest = if (offset - word.start <= word.end - offset) word.start else word.end
        if (nearest != other) return nearest
        return if (nearest == word.start) word.end else word.start
    }

    /**
     * Move one end of the passage to the word under [at]. Word edges keep a passage
     * readable — a character-granular pick leaves the two handles on top of each
     * other — and the end being dragged is never clamped inside the other one, so
     * dragging past it swaps the ends instead of collapsing the passage to nothing.
     */
    fun moveEnd(isStart: Boolean, at: Offset) {
        val layout = textLayout ?: return
        val other = if (isStart) cursor else anchor
        val edge = wordEdgeFor(layout, layout.getOffsetForPosition(at), other)
        if (isStart) anchor = edge else cursor = edge
    }

    fun dragHandle(isStart: Boolean, at: Offset) {
        handleDrag = HandleDrag(isStart, at)
        moveEnd(isStart, at)
        edgeStep = edgeStepFor(at.y - scrollState.value, viewportPx)
    }

    fun releaseHandle() {
        handleDrag = null
        edgeStep = 0f
    }

    fun copy(text: String, label: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("运行日志", text))
        AppToast.show(context, label, Toast.LENGTH_SHORT)
    }

    // The passage is taken from the lines that were on screen when it was started,
    // so pinning them is what keeps stop/start of the drag and the offsets in step.
    LaunchedEffect(anchor) {
        pinned = if (anchor >= 0) pinned ?: displayLines else null
    }

    LaunchedEffect(source) {
        dropSelection()
    }

    // Following the tail is what a log should do by default, but it must yield the
    // moment the reader scrolls back: otherwise every incoming line drags the view
    // down and the older lines can never be read. Only a scroll in progress counts —
    // reacting to the offset alone turned the first layout pass into "the reader
    // scrolled away" and the screen opened at the oldest line instead of the tail.
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.isScrollInProgress to scrollState.value }
            .collect { (scrolling, _) ->
                if (!scrolling) return@collect
                follow = scrollState.maxValue - scrollState.value <= FOLLOW_SLACK_PX
            }
    }

    // A new line moves maxValue, not the offset, and the offset that reaches the
    // new bottom only exists once that pass has been laid out.
    LaunchedEffect(follow) {
        if (!follow) return@LaunchedEffect
        snapshotFlow { scrollState.maxValue }.collect { scrollState.scrollTo(it) }
    }

    // One scroll step per frame while the finger is held in an edge band. A drag
    // in flight is extended by the same step: the text moved up by that much
    // under a finger that stayed put, so the passage grows by it.
    LaunchedEffect(scrollState) {
        snapshotFlow { edgeStep }.collectLatest { step ->
            if (step == 0f) return@collectLatest
            while (true) {
                scrollState.scrollBy(step)
                dragging?.let { at ->
                    val moved = at.copy(y = at.y + step)
                    dragging = moved
                    textLayout?.let { cursor = it.getOffsetForPosition(moved) }
                }
                handleDrag?.let { drag ->
                    val moved = drag.pos.copy(y = drag.pos.y + step)
                    handleDrag = drag.copy(pos = moved)
                    moveEnd(drag.isStart, moved)
                }
                withFrameNanos { }
            }
        }
    }

    PageScaffold(
        title = "运行日志",
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                copy(shown.joinToString("\n", transform = ::plainText), "已复制 ${shown.size} 行")
            }) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppDimens.iconSm)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text(
                    text = "复制",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = {
                TavernLog.clear(context)
                dropSelection()
            }) {
                Icon(
                    imageVector = Icons.Outlined.ClearAll,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(AppDimens.iconSm)
                )
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text(
                    text = "清空",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.page)
                .padding(bottom = AppSpacing.page)
        ) {
            ConsoleTabRow(
                titles = LogSource.entries.map { it.label },
                selectedIndex = source.ordinal,
                onSelect = { source = LogSource.entries[it] }
            )
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Surface(
                shape = RoundedCornerShape(AppRadius.md),
                color = LogColors.Plate,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Zoom stays outside the scroll: a one-finger drag is the scroll's
                    // to take, and the second pointer a pinch needs is the only event
                    // this detector gets to keep.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTransformGestures { _, _, zoom, _ ->
                                    scale = (scale * zoom).coerceIn(0.5f, 3f)
                                }
                            }
                            .pointerInput(Unit) {
                                val viewport = size.height.toFloat()
                                awaitPointerEventScope {
                                    while (true) {
                                        edgeStep = 0f
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        // A long press is what starts a selection. A drag
                                        // that never became one is the reader scrolling,
                                        // which the scroll modifier already handles.
                                        val pressed = awaitLongPressOrCancellation(down.id)
                                        if (pressed == null) {
                                            if (currentEvent.changes.none { it.pressed }) dropSelection()
                                            continue
                                        }
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == pressed.id }
                                            if (change == null || !change.pressed) break
                                            val y = change.position.y
                                            edgeStep = when {
                                                y < EDGE_BAND_PX ->
                                                    -EDGE_STEP_PX * ((EDGE_BAND_PX - y) / EDGE_BAND_PX)
                                                y > viewport - EDGE_BAND_PX ->
                                                    EDGE_STEP_PX * ((y - viewport + EDGE_BAND_PX) / EDGE_BAND_PX)
                                                else -> 0f
                                            }
                                        }
                                        edgeStep = 0f
                                    }
                                }
                            }
                            .verticalScroll(scrollState)
                            .onSizeChanged { viewportPx = it.height.toFloat() }
                            .padding(AppSpacing.md)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Text(
                            text = body,
                            onTextLayout = { textLayout = it },
                            fontSize = (11f * scale).sp,
                            lineHeight = (16f * scale).sp,
                            fontFamily = FontFamily.Monospace,
                            color = LogColors.Default,
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    val layout = textLayout ?: return@drawBehind
                                    if (selStart in 0 until selEnd) {
                                        drawPath(layout.getPathForRange(selStart, selEnd), selectionColor)
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { at ->
                                            val layout = textLayout ?: return@detectDragGesturesAfterLongPress
                                            val word = layout.getWordBoundary(layout.getOffsetForPosition(at))
                                            anchor = word.start
                                            cursor = word.end
                                            dragging = at
                                        },
                                        onDrag = { change, _ ->
                                            change.consume()
                                            dragging = change.position
                                            textLayout?.let {
                                                cursor = wordEdgeFor(it, it.getOffsetForPosition(change.position), anchor)
                                            }
                                        },
                                        onDragEnd = { dragging = null },
                                        onDragCancel = { dragging = null }
                                    )
                                }
                            )
                            val layout = textLayout
                            // Handles hang off the two ends of the passage rather than
                            // sitting on the highlighted characters, so they stay apart
                            // on a one-word pick and never merge into one blob.
                            if (layout != null && anchor >= 0 && cursor >= 0) {
                                val handleColor = LocalAppColors.current.accent
                                SelectionHandleDot(
                                    layout = layout,
                                    offset = anchor,
                                    color = handleColor,
                                    below = false,
                                    onMove = { dragHandle(true, it) },
                                    onRelease = ::releaseHandle,
                                )
                                SelectionHandleDot(
                                    layout = layout,
                                    offset = cursor,
                                    color = handleColor,
                                    below = true,
                                    onMove = { dragHandle(false, it) },
                                    onRelease = ::releaseHandle,
                                )
                            }
                        }
                    }
                    if (selStart in 0 until selEnd) {
                        Surface(
                            shape = RoundedCornerShape(AppRadius.pill),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shadowElevation = 4.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = AppSpacing.md)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    val picked = body.text.substring(selStart, selEnd)
                                    copy(picked, "已复制所选 ${picked.count { it == '\n' } + 1} 行")
                                    dropSelection()
                                }) {
                                    Text("复制")
                                }
                                TextButton(onClick = { dropSelection() }) {
                                    Text("取消")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A grab handle drawn at one end of the passage. Without it a passage could only be
 * extended by starting the gesture again from the text, which leaves the reader with a
 * highlighted block and no way to correct either end.
 *
 * The handle is laid out in the text's own coordinates, inside the scrolling content,
 * so it travels with the lines it belongs to.
 */
@Composable
private fun SelectionHandleDot(
    layout: TextLayoutResult,
    offset: Int,
    color: Color,
    below: Boolean,
    onMove: (Offset) -> Unit,
    onRelease: () -> Unit,
) {
    val textLength = layout.layoutInput.text.length
    val at = offset.coerceIn(0, textLength)
    val line = layout.getLineForOffset(at)
    val x = layout.getHorizontalPosition(at, true)
    // One end hangs above the first line it covers, the other below the last, so the
    // two never overlap however short the passage is.
    val y = if (below) layout.getLineBottom(line) else layout.getLineTop(line)
    val density = LocalDensity.current
    val gap = with(density) { 9.dp.toPx() }
    val touch = with(density) { 30.dp.toPx() }
    val dot = with(density) { 7.dp.toPx() }
    val ring = with(density) { 2.dp.toPx() }
    val centreY = if (below) y + gap else y - gap
    // Read at gesture time: the handle moves as the passage changes, and a key that
    // changes mid-drag would cancel the drag it belongs to.
    val start = rememberUpdatedState(Offset(x, centreY))
    var travelled by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset { IntOffset((x - touch / 2f).roundToInt(), (centreY - touch / 2f).roundToInt()) }
            .size(with(density) { 30.dp })
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        travelled = start.value
                        onMove(travelled)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        travelled += amount
                        onMove(travelled)
                    },
                    onDragEnd = { onRelease() },
                    onDragCancel = { onRelease() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centre = size.center
            drawCircle(color.copy(alpha = 0.35f), radius = dot + ring, center = centre)
            drawCircle(color, radius = dot, center = centre)
        }
    }
}

/**
 * One end of a passage being adjusted by hand: which end it is, and where the finger
 * holding it sits in the text's own coordinates.
 */
private data class HandleDrag(val isStart: Boolean, val pos: Offset)

// Pixels per frame to scroll while an end is held inside an edge band of the viewport,
// scaled by how deep into the band the finger is.
private fun edgeStepFor(viewportY: Float, viewportHeight: Float): Float = when {
    viewportHeight <= 0f -> 0f
    viewportY < EDGE_BAND_PX -> -EDGE_STEP_PX * ((EDGE_BAND_PX - viewportY) / EDGE_BAND_PX)
    viewportY > viewportHeight - EDGE_BAND_PX ->
        EDGE_STEP_PX * ((viewportY - viewportHeight + EDGE_BAND_PX) / EDGE_BAND_PX)
    else -> 0f
}
