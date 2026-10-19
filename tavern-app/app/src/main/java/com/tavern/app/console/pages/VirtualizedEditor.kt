package com.tavern.app.console.pages

import com.tavern.app.util.AppToast
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.LocalAppColors
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.FileItem
import com.tavern.app.console.FileManager
import com.tavern.app.console.components.DialogConfirmButton
import com.tavern.app.console.components.DialogDismissButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Text editor
// Small files use a single multi-line BasicTextField. Large files (>LARGE_LINE_LIMIT)
// use a per-line virtualized LazyColumn so a huge file doesn't blow up memory/layout.

private const val LARGE_LINE_LIMIT = 2000

private data class UndoEntry(
    val lines: List<String>,
    val fields: List<TextFieldValue>,
    val focusedIdx: Int,
    val ids: List<Long>
)

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun VirtualizedEditor(item: FileItem, fm: FileManager, accent: Color, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)

    var txt by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(TextFieldValue("")) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var exitReason by remember { mutableStateOf("close") }

    var fontScale by remember { mutableFloatStateOf(1f) }
    val fontSizeSp = remember(fontScale) { (13f * fontScale).sp }
    val lineHeightSp = remember(fontScale) { (13f * fontScale * 1.54f).sp }
    val lineH = with(density) { lineHeightSp.toDp() }

    var editMode by remember { mutableStateOf(false) }
    // Large-file per-line editor state
    var editLarge by remember { mutableStateOf(false) }
    var lazyLines by remember { mutableStateOf(listOf<String>()) }
    var lazyFields by remember { mutableStateOf(listOf<TextFieldValue>()) }
    var lazyFocusedIdx by remember { mutableIntStateOf(-1) }
    val lazyFocusReq = remember { mutableMapOf<Int, FocusRequester>() }
    var lineIdCounter by remember { mutableIntStateOf(0) }
    var lineIds by remember { mutableStateOf(listOf<Long>()) }
    var lastSnapshottedLine by remember { mutableIntStateOf(-1) }

    val undoStack = remember { mutableStateListOf<UndoEntry>() }
    val redoStack = remember { mutableStateListOf<UndoEntry>() }
    val etUndoStack = remember { mutableStateListOf<TextFieldValue>() }
    val etRedoStack = remember { mutableStateListOf<TextFieldValue>() }
    val highlighter = remember(item.extension) { SyntaxHighlightTransform(item.extension) }

    LaunchedEffect(item) {
        fm.readText(item.file).fold(
            onSuccess = { txt = it; original = it },
            onFailure = { txt = "读取失败：${it.message}" }
        )
        loading = false
    }

    fun newLineId(): Long {
        val id = (System.nanoTime() shl 16) or (lineIdCounter++.toLong() and 0xFFFF)
        return id
    }

    // Populate per-line state when the file is large.
    fun initLargeEditor() {
        val split = txt.split('\n')
        lazyLines = split
        lazyFields = split.map { TextFieldValue(it) }
        lineIdCounter = 0
        lineIds = split.indices.map { newLineId() }
        lazyFocusedIdx = -1
        lastSnapshottedLine = -1
        editLarge = true
    }

    LaunchedEffect(editMode) {
        if (editMode) {
            val large = txt.split('\n').size > LARGE_LINE_LIMIT
            if (large) initLargeEditor()
            else {
                editLarge = false
                editText = TextFieldValue(txt, TextRange(0))
            }
        }
    }

    val hasChanges = if (editMode) {
        if (editLarge) lazyLines.joinToString("\n") != original else editText.text != original
    } else (txt != original)
    BackHandler { if (hasChanges && editMode) { exitReason = "close"; showExitDialog = true } else onClose() }

    fun snapshotUndo() {
        undoStack.add(UndoEntry(lazyLines.toList(), lazyFields.toList(), lazyFocusedIdx, lineIds.toList()))
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    // Per-line editor: handle a single line's text change (split/merge/edit).
    fun handleLine(idx: Int, newVal: TextFieldValue) {
        val t = newVal.text; val nl = t.indexOf('\n')
        if (nl >= 0) {
            snapshotUndo()
            lastSnapshottedLine = idx
            val b = t.substring(0, nl); val a = t.substring(nl + 1)
            val sa = newVal.selection.start - nl - 1
            lazyLines = lazyLines.toMutableList().apply { this[idx] = b; add(idx + 1, a) }
            lazyFields = lazyFields.toMutableList().apply {
                this[idx] = TextFieldValue(b, TextRange(b.length))
                add(idx + 1, TextFieldValue(a, TextRange(sa.coerceIn(0, a.length))))
            }
            lineIds = lineIds.toMutableList().apply { this[idx] = newLineId(); add(idx + 1, newLineId()) }
            lazyFocusedIdx = idx + 1
        } else if (t.isEmpty() && idx > 0) {
            snapshotUndo()
            lastSnapshottedLine = idx - 1
            val prevLen = lazyLines[idx - 1].length
            val merged = lazyLines[idx - 1] + lazyLines.getOrElse(idx) { "" }
            lazyLines = lazyLines.toMutableList().apply { this[idx - 1] = merged; removeAt(idx) }
            lazyFields = lazyFields.toMutableList().apply {
                this[idx - 1] = TextFieldValue(merged, TextRange(prevLen))
                removeAt(idx)
            }
            lineIds = lineIds.toMutableList().apply { this[idx - 1] = newLineId(); removeAt(idx) }
            lazyFocusedIdx = idx - 1
        } else if (t.isEmpty() && idx == 0) {
            if (idx != lastSnapshottedLine) { snapshotUndo(); lastSnapshottedLine = idx }
            lazyLines = lazyLines.toMutableList().apply { this[idx] = t }
            lazyFields = lazyFields.toMutableList().apply { this[idx] = newVal }
        } else {
            if (idx != lastSnapshottedLine) { snapshotUndo(); lastSnapshottedLine = idx }
            lazyLines = lazyLines.toMutableList().apply { this[idx] = t }
            lazyFields = lazyFields.toMutableList().apply { this[idx] = newVal }
        }
    }

    fun doUndo() {
        if (editLarge) {
            if (undoStack.isEmpty()) return
            val cur = UndoEntry(lazyLines.toList(), lazyFields.toList(), lazyFocusedIdx, lineIds.toList())
            redoStack.add(cur)
            if (redoStack.size > 100) redoStack.removeAt(0)
            val prev = undoStack.removeAt(undoStack.lastIndex)
            lazyLines = prev.lines.toMutableList()
            lazyFields = prev.fields.toMutableList()
            lineIds = prev.ids.toMutableList()
            lazyFocusedIdx = prev.focusedIdx.coerceIn(-1, lazyLines.size - 1)
            lastSnapshottedLine = lazyFocusedIdx
        } else {
            if (etUndoStack.isEmpty()) return
            val cur = TextFieldValue(editText.text, editText.selection)
            etRedoStack.add(cur)
            if (etRedoStack.size > 100) etRedoStack.removeAt(0)
            editText = etUndoStack.removeAt(etUndoStack.lastIndex)
        }
    }

    fun doRedo() {
        if (editLarge) {
            if (redoStack.isEmpty()) return
            val cur = UndoEntry(lazyLines.toList(), lazyFields.toList(), lazyFocusedIdx, lineIds.toList())
            undoStack.add(cur)
            if (undoStack.size > 100) undoStack.removeAt(0)
            val next = redoStack.removeAt(redoStack.lastIndex)
            lazyLines = next.lines.toMutableList()
            lazyFields = next.fields.toMutableList()
            lineIds = next.ids.toMutableList()
            lazyFocusedIdx = next.focusedIdx.coerceIn(-1, lazyLines.size - 1)
            lastSnapshottedLine = lazyFocusedIdx
        } else {
            if (etRedoStack.isEmpty()) return
            val cur = TextFieldValue(editText.text, editText.selection)
            etUndoStack.add(cur)
            if (etUndoStack.size > 100) etUndoStack.removeAt(0)
            editText = etRedoStack.removeAt(etRedoStack.lastIndex)
        }
    }

    fun onZoom(z: Float) { fontScale = (fontScale * z).coerceIn(0.5f, 3f) }

    // imePadding here is what makes the editor's own height the space above the keyboard: it
    // adds nothing when an ancestor already padded for it (the inset is consumed by then), so
    // the height the caret logic reads is correct either way.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .imePadding()
        .pointerInput(Unit) {
            awaitEachGesture {
                do {
                    val e = awaitPointerEvent()
                    val p = e.changes.filter { it.pressed }
                    if (p.size >= 2) {
                        val c = p.take(2)
                        val pd = (c[0].previousPosition - c[1].previousPosition).getDistance()
                        val cd = (c[0].position - c[1].position).getDistance()
                        if (pd > 1f) { val z = cd / pd; if (z != 1f) onZoom(z) }
                        e.changes.forEach { it.consume() }
                    }
                } while (e.changes.any { it.pressed })
            }
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (hasChanges && editMode) { exitReason = "close"; showExitDialog = true }
                        else onClose()
                    }, enabled = !saving, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("←", color = accent, style = MaterialTheme.typography.titleLarge)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(item.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (editMode) "UTF-8、${item.extension.uppercase()}、编辑"
                            else "UTF-8、${item.extension.uppercase()}、${txt.count { it == '\n' } + 1}行",
                            color = muted, style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (!editMode) {
                        TextButton(onClick = { editMode = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("编辑", color = accent, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        if (hasChanges) Text("●", color = accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 4.dp))
                        TextButton(onClick = {
                            if (hasChanges) { exitReason = "preview"; showExitDialog = true }
                            else editMode = false
                        }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("预览", color = muted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = {
                            scope.launch {
                                saving = true; saveError = null
                                val j = if (editLarge) lazyLines.joinToString("\n") else editText.text
                                withContext(Dispatchers.IO) { fm.writeText(item.file, j) }
                                    .fold(
                                        onSuccess = { txt = j; original = j; undoStack.clear(); redoStack.clear(); etUndoStack.clear(); etRedoStack.clear(); AppToast.show(ctx, "已保存", Toast.LENGTH_SHORT) },
                                        onFailure = { saveError = it.message }
                                    )
                                saving = false
                            }
                        }, enabled = hasChanges && !saving, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (saving) "…" else "保存", color = if (hasChanges && !saving) accent else muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            saveError?.let { Text(it, color = LocalAppColors.current.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }

            // Editor area
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp)) }
            } else if (editMode) {
                if (editLarge) {
                    // Large file: per-line virtualized editor
                    Row(
                        Modifier.fillMaxWidth().background(muted.copy(alpha = AppAlpha.iconBadge)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${lazyLines.size} 行", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = muted)
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(AppDimens.controlHeight).clickable { doUndo() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, "撤销", tint = if (undoStack.isNotEmpty()) onSurface else muted.copy(alpha = AppAlpha.strong),
                                modifier = Modifier.size(AppDimens.iconSm))
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(AppDimens.controlHeight).clickable { doRedo() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, "重做", tint = if (redoStack.isNotEmpty()) onSurface else muted.copy(alpha = AppAlpha.strong),
                                modifier = Modifier.size(AppDimens.iconSm))
                        }
                    }
                    // A line split or merge moves the caret to another line, and a tap can put
                    // it anywhere else in the file. The list has to focus that line again and
                    // keep it above the keyboard; on its own it does neither, and the caret
                    // ends up under the keyboard.
                    val lazyListState = rememberLazyListState()
                    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                    val lazyViewport = lazyListState.layoutInfo.viewportSize.height
                    LaunchedEffect(lazyFocusedIdx, imeBottom, lazyViewport) {
                        val idx = lazyFocusedIdx
                        if (idx < 0 || idx >= lazyLines.size) return@LaunchedEffect
                        // The list's own height is already the space above the keyboard (this
                        // screen pads for the inset itself), so it is used as is.
                        val viewport = lazyListState.layoutInfo.viewportSize.height
                        val info = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
                        val linePx = with(density) { lineH.toPx() }.toInt()
                        val margin = linePx
                        if (viewport > 0) {
                            val comfortable = info != null &&
                                info.offset >= margin &&
                                info.offset + info.size <= viewport - margin
                            if (!comfortable) {
                                val itemSize = info?.size ?: linePx
                                // Only as far as it takes: a line below the keyboard comes up to
                                // sit above it, a line that ended up above the top edge goes back
                                // under it. Scrolling to a fixed fraction of the screen moved both
                                // far more than needed.
                                val offset = if (info == null || info.offset + itemSize + margin > viewport) {
                                    (viewport - margin - itemSize).coerceAtLeast(margin)
                                } else {
                                    margin
                                }
                                lazyListState.animateScrollToItem(idx, -offset)
                            }
                        }
                        lazyFocusReq.getOrPut(idx) { FocusRequester() }.requestFocus()
                    }
                    LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(lazyLines.size, key = { lineIds.getOrElse(it) { it.toLong() } }) { idx ->
                            val req = lazyFocusReq.getOrPut(idx) { FocusRequester() }
                            BasicTextField(
                                value = lazyFields.getOrElse(idx) { TextFieldValue(lazyLines.getOrElse(idx) { "" }) },
                                onValueChange = { handleLine(idx, it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(lineH)
                                    .onFocusChanged { if (it.isFocused && lazyFocusedIdx != idx) lazyFocusedIdx = idx }
                                    .focusRequester(req)
                                    .padding(horizontal = 8.dp),
                                // Multiline on purpose: a field limited to one line turns the
                                // Enter key into an IME "done" action instead of the newline
                                // that splits this line in two. The stored value never holds
                                // more than one line, because handleLine moves the rest down.
                                singleLine = false,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace, fontSize = fontSizeSp,
                                    color = onSurface, lineHeight = lineHeightSp),
                                cursorBrush = SolidColor(accent),
                                visualTransformation = highlighter
                            )
                        }
                    }
                } else {
                    // Small file: single multi-line editor
                    val cursorLine by remember(editText) { derivedStateOf {
                        val p = editText.selection.start.coerceAtMost(editText.text.length)
                        editText.text.substring(0, p).count { it == '\n' } + 1
                    }}
                    val cursorCol by remember(editText) { derivedStateOf {
                        val p = editText.selection.start.coerceAtMost(editText.text.length)
                        val prevNl = editText.text.lastIndexOf('\n', (p - 1).coerceAtLeast(0))
                        p - prevNl
                    }}
                    val totalLines by remember(editText) { derivedStateOf { editText.text.count { it == '\n' } + 1 }}
                    Row(
                        Modifier.fillMaxWidth().background(muted.copy(alpha = AppAlpha.iconBadge)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$cursorLine : $cursorCol", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = muted)
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.size(AppDimens.controlHeight).clickable { doUndo() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, "撤销", tint = if (etUndoStack.isNotEmpty()) onSurface else muted.copy(alpha = AppAlpha.strong),
                                modifier = Modifier.size(AppDimens.iconSm))
                        }
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(AppDimens.controlHeight).clickable { doRedo() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, "重做", tint = if (etRedoStack.isNotEmpty()) onSurface else muted.copy(alpha = AppAlpha.strong),
                                modifier = Modifier.size(AppDimens.iconSm))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("$totalLines 行", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    // The field grows with the text inside this scroll container instead of
                    // being clipped by it: only then can the caret be scrolled back into view
                    // when the keyboard shrinks the editor, because the field's own scrolling
                    // has no reason to react to a resize.
                    val etScroll = rememberScrollState()
                    var etLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
                    val imeBottomSmall = WindowInsets.ime.getBottom(LocalDensity.current)
                    // The caret's position has to come from the text layout. Long lines wrap, so
                    // "line number x line height" points at a different row than the caret's and
                    // the view scrolls to the wrong offset - either not far enough to keep the
                    // caret above the keyboard, or far enough to push it under it.
                    // Both keys are watched: the viewport height changes whenever the visible
                    // area does (keyboard, window resize), and the inset changes when the
                    // keyboard opens without the layout following. Whichever one moves, the
                    // caret is checked again.
                    LaunchedEffect(etScroll.viewportSize, imeBottomSmall, editText.selection, editText.text, etLayout, fontScale) {
                        val layout = etLayout ?: return@LaunchedEffect
                        val viewport = etScroll.viewportSize
                        if (viewport <= 0) return@LaunchedEffect
                        val offset = editText.selection.start.coerceIn(0, editText.text.length)
                        val caret = layout.getCursorRect(offset)
                        val linePx = with(density) { lineHeightSp.toPx() }.toInt()
                        val padTop = with(density) { 2.dp.toPx() }.toInt()
                        val margin = linePx
                        val caretTop = caret.top.toInt() + padTop
                        val caretBottom = caret.bottom.toInt() + padTop
                        val target = when {
                            caretTop - margin < etScroll.value -> caretTop - margin
                            caretBottom + margin > etScroll.value + viewport -> caretBottom + margin - viewport
                            else -> etScroll.value
                        }.coerceIn(0, etScroll.maxValue)
                        if (target != etScroll.value) etScroll.animateScrollTo(target)
                    }
                    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val minFieldHeight = maxHeight
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(etScroll)) {
                            BasicTextField(
                                value = editText,
                                onValueChange = { nv ->
                                    if (editText.text != nv.text) {
                                        etUndoStack.add(editText)
                                        if (etUndoStack.size > 100) etUndoStack.removeAt(0)
                                        etRedoStack.clear()
                                    }
                                    editText = nv
                                },
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontFamily = FontFamily.Monospace, fontSize = fontSizeSp,
                                    color = onSurface, lineHeight = lineHeightSp),
                                cursorBrush = SolidColor(accent),
                                visualTransformation = highlighter,
                                onTextLayout = { etLayout = it },
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = minFieldHeight)
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            } else {
                // Read-only viewer with syntax highlighting
                val lineStarts = remember(txt) { val off = mutableListOf(0); var i = 0; while (i < txt.length) { if (txt[i] == '\n') off.add(i + 1); i++ }; off }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(lineStarts.size, key = { it }) { idx ->
                        val s = lineStarts[idx]; val e = if (idx + 1 < lineStarts.size) lineStarts[idx + 1] - 1 else txt.length
                        val line = if (e > s) txt.substring(s, e) else ""
                        Row(Modifier.fillMaxWidth().animateItem()) {
                            Text("${idx + 1}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.End,
                                modifier = Modifier.width(36.dp).padding(end = 4.dp, top = 2.dp))
                            Box(Modifier.width(1.dp).height(lineH).background(muted.copy(alpha = AppAlpha.tint)))
                            Spacer(Modifier.width(6.dp))
                            val hl = remember(line) { highlighter.filter(AnnotatedString(line)).text }
                            Text(hl, fontFamily = FontFamily.Monospace, fontSize = fontSizeSp, lineHeight = lineHeightSp, color = onSurface, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    // Exit / preview confirmation dialog
    if (showExitDialog) {
        val isPreview = exitReason == "preview"
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("未保存的修改", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("存在未保存的修改，退出后将丢失这些更改。", color = muted) },
            confirmButton = {
                DialogConfirmButton(text = if (isPreview) "保存并预览" else "保存并退出", color = LocalAppColors.current.success, onClick = {
                    showExitDialog = false
                    scope.launch {
                        saving = true
                        val j = if (editLarge) lazyLines.joinToString("\n") else editText.text
                        withContext(Dispatchers.IO) { fm.writeText(item.file, j) }.fold(
                            onSuccess = {
                                txt = j; original = j; undoStack.clear(); redoStack.clear()
                                etUndoStack.clear(); etRedoStack.clear()
                                if (isPreview) editMode = false else onClose()
                            },
                            onFailure = { saveError = it.message }
                        )
                        saving = false
                    }
                })
            },
            dismissButton = {
                Row {
                    DialogDismissButton(onClick = { showExitDialog = false })
                    DialogDismissButton(text = "不保存", onClick = {
                        showExitDialog = false
                        if (isPreview) editMode = false else onClose()
                    })
                }
            },
            containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)
        )
    }
}
