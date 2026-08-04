package com.tavern.app.console.pages

import android.graphics.Rect
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.EditorInfo
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.tavern.app.console.FileItem
import com.tavern.app.console.FileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── LazyColumn-based text editor: viewer → edit → save, works for any file size ──

private data class UndoEntry(
    val lines: List<String>,
    val fields: List<TextFieldValue>,
    val focusedIdx: Int,
    val ids: List<Long>
)

@Composable
fun VirtualizedEditor(item: FileItem, fm: FileManager, accent: Color, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val onSurface = MaterialTheme.colorScheme.onBackground
    val muted = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)

    var txt by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var editMultiline by remember { mutableStateOf(true) }
    var editText by remember { mutableStateOf(TextFieldValue("")) }
    var etRef by remember { mutableStateOf<android.widget.EditText?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }
    var exitReason by remember { mutableStateOf("close") }

    var fontScale by remember { mutableFloatStateOf(1f) }
    val fontSizeSp = remember(fontScale) { (13f * fontScale).sp }
    val lineHeightSp = remember(fontScale) { (13f * fontScale * 1.54f).sp }
    val lineH = with(density) { lineHeightSp.toDp() }
    val lineHPx = with(density) { lineHeightSp.toPx() }.toInt()

    var editMode by remember { mutableStateOf(false) }
    var lazyLines by remember { mutableStateOf(listOf<String>()) }
    var lazyFields by remember { mutableStateOf(listOf<TextFieldValue>()) }
    var lazyFocusedIdx by remember { mutableIntStateOf(-1) }
    val lazyFocusReq = remember { mutableMapOf<Int, FocusRequester>() }
    // Cursor tracking for status bar
    var cursorLine by remember { mutableIntStateOf(1) }
    var cursorCol by remember { mutableIntStateOf(1) }
    val lazyListState = rememberLazyListState()
    // Stable IDs for LazyColumn keys — incremented on each structural change
    var lineIdCounter by remember { mutableIntStateOf(0) }
    var lineIds by remember { mutableStateOf(listOf<Long>()) }

    val undoStack = remember { mutableStateListOf<UndoEntry>() }
    val redoStack = remember { mutableStateListOf<UndoEntry>() }
    // EditText (small file) undo — store full TextFieldValue to preserve cursor position
    val etUndoStack = remember { mutableStateListOf<TextFieldValue>() }
    val etRedoStack = remember { mutableStateListOf<TextFieldValue>() }
    val highlighter = remember(item.extension) { SyntaxHighlightTransform(item.extension) }

    // Track last line touched for undo batching
    var lastSnapshottedLine by remember { mutableIntStateOf(-1) }

    LaunchedEffect(item) {
        fm.readText(item.file).fold(
            onSuccess = { txt = it; original = it },
            onFailure = { txt = "读取失败: ${it.message}" }
        )
        loading = false
    }

    fun newLineId(): Long {
        val id = (System.nanoTime() shl 16) or (lineIdCounter++.toLong() and 0xFFFF)
        return id
    }

    LaunchedEffect(editMode) {
        if (editMode) {
            val split = txt.split('\n')
            editMultiline = split.size <= 2000
            if (editMultiline) {
                editText = TextFieldValue(txt, TextRange(txt.length))
            }
            lazyLines = split
            lazyFields = split.map { TextFieldValue(it) }
            lineIdCounter = 0
            lineIds = split.indices.map { newLineId() }
            lastSnapshottedLine = -1
        }
    }

    // Assign stable ID to a line at the given index
    fun assignLineId(idx: Int) {
        if (idx < 0 || idx >= lazyLines.size) return
        lineIds = lineIds.toMutableList().apply { this[idx] = newLineId() }
    }

    // ── Listen for keyboard height via window visible frame ──
    val rootView = LocalView.current
    var keyboardHeight by remember { mutableIntStateOf(0) }
    var baselineGap by remember { mutableIntStateOf(0) }
    var baselineFrames by remember { mutableIntStateOf(0) }
    DisposableEffect(rootView) {
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val rootH = rootView.rootView.height
            val visibleH = rect.bottom
            val gap = rootH - visibleH
            // Establish baseline over first 3 frames (system nav bar, not keyboard)
            if (baselineFrames < 3) {
                if (gap > baselineGap) baselineGap = gap
                baselineFrames++
                return@OnGlobalLayoutListener
            }
            val kbH = gap - baselineGap
            keyboardHeight = if (kbH > 50) kbH else 0
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { rootView.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }

    LaunchedEffect(lazyFocusedIdx) {
        if (lazyFocusedIdx >= 0 && lazyFocusedIdx < lazyLines.size) {
            lazyListState.animateScrollToItem(lazyFocusedIdx, scrollOffset = -lineHPx * 8)
            lazyFocusReq[lazyFocusedIdx]?.requestFocus()
        }
    }

    val hasChanges = if (editMode) {
        if (editMultiline) editText.text != original else lazyLines.joinToString("\n") != original
    } else (txt != original)
    BackHandler { if (hasChanges && editMode) { exitReason = "close"; showExitDialog = true } else onClose() }

    fun snapshotUndo() {
        undoStack.add(UndoEntry(lazyLines.toList(), lazyFields.toList(), lazyFocusedIdx, lineIds.toList()))
        if (undoStack.size > 100) undoStack.removeAt(0)
        redoStack.clear()
    }

    fun doUndo() {
        if (editMultiline) {
            if (etUndoStack.isEmpty()) return
            val cur = TextFieldValue(editText.text, editText.selection)
            etRedoStack.add(cur)
            if (etRedoStack.size > 100) etRedoStack.removeAt(0)
            editText = etUndoStack.removeAt(etUndoStack.lastIndex)
            return
        }
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
    }

    fun doRedo() {
        if (editMultiline) {
            if (etRedoStack.isEmpty()) return
            val cur = TextFieldValue(editText.text, editText.selection)
            etUndoStack.add(cur)
            if (etUndoStack.size > 100) etUndoStack.removeAt(0)
            editText = etRedoStack.removeAt(etRedoStack.lastIndex)
            return
        }
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
    }

    // ── handleLine: called on every text change in a single-line BasicTextField ──
    // Only snapshots undo on structural changes (newline / merge / line change),
    // not on every keystroke within the same line.
    fun handleLine(idx: Int, newVal: TextFieldValue) {
        val t = newVal.text; val nl = t.indexOf('\n')
        if (nl >= 0) {
            // ── Enter pressed: split line ──
            snapshotUndo()
            lastSnapshottedLine = idx
            val b = t.substring(0, nl); val a = t.substring(nl + 1)
            val sa = newVal.selection.start - nl - 1
            lazyLines = lazyLines.toMutableList().apply { this[idx] = b; add(idx + 1, a) }
            lazyFields = lazyFields.toMutableList().apply {
                this[idx] = TextFieldValue(b, TextRange(b.length))
                add(idx + 1, TextFieldValue(a, TextRange(sa.coerceIn(0, a.length))))
            }
            lineIds = lineIds.toMutableList().apply {
                this[idx] = newLineId(); add(idx + 1, newLineId())
            }
            lazyFocusedIdx = idx + 1
        } else if (t.isEmpty() && idx > 0) {
            // ── Line emptied → merge with previous line ──
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
            // ── First line emptied → just clear it ──
            if (idx != lastSnapshottedLine) { snapshotUndo(); lastSnapshottedLine = idx }
            lazyLines = lazyLines.toMutableList().apply { this[idx] = t }
            lazyFields = lazyFields.toMutableList().apply { this[idx] = newVal }
        } else {
            // ── Normal edit within line ──
            if (idx != lastSnapshottedLine) { snapshotUndo(); lastSnapshottedLine = idx }
            lazyLines = lazyLines.toMutableList().apply { this[idx] = t }
            lazyFields = lazyFields.toMutableList().apply { this[idx] = newVal }
        }
    }

    fun onZoom(z: Float) { fontScale = (fontScale * z).coerceIn(0.5f, 3f) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
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
            // ── Top bar ──
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (hasChanges && editMode) { exitReason = "close"; showExitDialog = true }
                        else onClose()
                    }, enabled = !saving, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text("←", color = accent, fontSize = 18.sp)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text(item.name, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (editMode) "UTF-8 · ${item.extension.uppercase()} · 编辑"
                            else "UTF-8 · ${item.extension.uppercase()} · ${txt.count { it == '\n' } + 1}行",
                            color = muted, fontSize = 10.sp
                        )
                    }
                    if (!editMode) {
                        TextButton(onClick = { editMode = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text("编辑", color = accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        if (hasChanges) Text("●", color = accent, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
                        TextButton(onClick = {
                            if (hasChanges) { exitReason = "preview"; showExitDialog = true }
                            else editMode = false
                        }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text("预览", color = muted, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            scope.launch {
                                saving = true; saveError = null
                                val j = if (editMultiline) editText.text else lazyLines.joinToString("\n")
                                withContext(Dispatchers.IO) { fm.writeText(item.file, j) }
                                    .fold(
                                        onSuccess = { txt = j; original = j; undoStack.clear(); redoStack.clear(); etUndoStack.clear(); etRedoStack.clear(); Toast.makeText(ctx, "已保存", Toast.LENGTH_SHORT).show() },
                                        onFailure = { saveError = it.message }
                                    )
                                saving = false
                            }
                        }, enabled = hasChanges && !saving, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (saving) "…" else "保存", color = if (hasChanges && !saving) Color(0xFF2E7D32) else muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            saveError?.let { Text(it, color = Color(0xFFCC4455), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }

            // ── Editor area ──
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = accent, modifier = Modifier.size(28.dp)) }
            } else if (editMode && editMultiline) {
                // ── Small file: single EditText (AndroidView), native keyboard scroll ──
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
                // Status bar
                Row(Modifier.fillMaxWidth().background(muted.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("$cursorLine : $cursorCol", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = muted)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.Undo, "撤销", tint = if (etUndoStack.isNotEmpty()) onSurface else muted.copy(alpha = 0.25f),
                        modifier = Modifier.size(18.dp).clickable { doUndo() })
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Redo, "重做", tint = if (etRedoStack.isNotEmpty()) onSurface else muted.copy(alpha = 0.25f),
                        modifier = Modifier.size(18.dp).clickable { doRedo() })
                    Spacer(Modifier.width(8.dp))
                    Text("$totalLines 行", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = muted)
                }
                // Editor with native EditText (AndroidView), proper keyboard cursor scrolling
                var syncing by remember { mutableStateOf(false) }
                AndroidView(
                    factory = { ctx ->
                        object : EditText(ctx) {
                            override fun onSelectionChanged(selStart: Int, selEnd: Int) {
                                super.onSelectionChanged(selStart, selEnd)
                                if (!syncing) {
                                    editText = editText.copy(selection = TextRange(
                                        selStart.coerceAtLeast(0), selEnd.coerceAtLeast(0)))
                                }
                            }
                        }.apply {
                            etRef = this
                            val et = this
                            typeface = Typeface.MONOSPACE
                            setTextIsSelectable(true)
                            isVerticalScrollBarEnabled = true
                            imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setIncludeFontPadding(false)
                            setLineSpacing(6f, 1f)
                            setPadding(0, 0, 0, 0)
                            gravity = android.view.Gravity.TOP
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                val accentInt = android.graphics.Color.argb(
                                    (accent.alpha * 255).toInt(), (accent.red * 255).toInt(),
                                    (accent.green * 255).toInt(), (accent.blue * 255).toInt())
                                val cursorD = android.graphics.drawable.GradientDrawable().apply {
                                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                                    setSize((4 * density.density).toInt(), 1)
                                    setColor(accentInt)
                                    setCornerRadius(0f)
                                }
                                setTextCursorDrawable(cursorD)
                            }
                            val tc = onSurface
                            setTextColor(android.graphics.Color.argb(
                                (tc.alpha * 255).toInt(), (tc.red * 255).toInt(),
                                (tc.green * 255).toInt(), (tc.blue * 255).toInt()))
                            highlightColor = android.graphics.Color.argb(45,
                                (accent.red * 255).toInt(), (accent.green * 255).toInt(),
                                (accent.blue * 255).toInt())
                            setTextSize(13f)
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                override fun afterTextChanged(s: Editable?) {
                                    if (!syncing && s != null) {
                                        syncing = true
                                        // Store previous TextFieldValue with cursor for undo
                                        if (editText.text != s.toString()) {
                                            etUndoStack.add(editText)
                                            if (etUndoStack.size > 100) etUndoStack.removeAt(0)
                                            etRedoStack.clear()
                                        }
                                        editText = TextFieldValue(s.toString(), TextRange(
                                            et.selectionStart.coerceAtLeast(0), et.selectionEnd.coerceAtLeast(0)))
                                        syncing = false
                                    }
                                }
                            })
                        }
                    },
                    update = { et ->
                        et.setTextSize(13f * fontScale)
                        val composeText = editText.text
                        val etText = et.text?.toString() ?: ""
                        if (etText != composeText) {
                            syncing = true
                            val t = highlighter.filter(AnnotatedString(composeText))
                            val spannable = SpannableString(composeText)
                            for (range in t.text.spanStyles) {
                                val c = range.item.color
                                if (c != Color.Unspecified) {
                                    spannable.setSpan(ForegroundColorSpan(
                                        android.graphics.Color.argb(
                                            (c.alpha * 255).toInt(), (c.red * 255).toInt(),
                                            (c.green * 255).toInt(), (c.blue * 255).toInt())),
                                        range.start, range.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                            et.setText(spannable, TextView.BufferType.SPANNABLE)
                            val sel = editText.selection
                            if (sel.start in 0..composeText.length) {
                                et.setSelection(sel.start, sel.end)
                            }
                            syncing = false
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .padding(bottom = with(density) { keyboardHeight.toDp() })
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            } else if (editMode) {
                // ── Status bar ──
                Row(Modifier.fillMaxWidth().background(muted.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text("$cursorLine : $cursorCol", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = muted)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Outlined.Undo, "撤销", tint = if (undoStack.isNotEmpty()) onSurface else muted.copy(alpha = 0.25f),
                        modifier = Modifier.size(18.dp).clickable { doUndo() })
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Redo, "重做", tint = if (redoStack.isNotEmpty()) onSurface else muted.copy(alpha = 0.25f),
                        modifier = Modifier.size(18.dp).clickable { doRedo() })
                    Spacer(Modifier.width(8.dp))
                    Text("${lazyLines.size} 行", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = muted)
                }
                // ── Per-line editor ──
                LazyColumn(state = lazyListState, modifier = Modifier.fillMaxWidth().weight(1f).imePadding()) {
                    items(lazyLines.size, key = { lineIds.getOrElse(it) { it.toLong() } }) { idx ->
                        val req = lazyFocusReq.getOrPut(idx) { FocusRequester() }
                        BasicTextField(
                            value = lazyFields.getOrElse(idx) { TextFieldValue(lazyLines.getOrElse(idx) { "" }) },
                            onValueChange = {
                                handleLine(idx, it)
                                cursorLine = idx + 1
                                cursorCol = it.selection.start.coerceAtMost(it.text.length) + 1
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(req).padding(horizontal = 8.dp, vertical = 4.dp)
                                .onFocusChanged { fs ->
                                    if (fs.isFocused) {
                                        lazyFocusedIdx = idx
                                        // Update cursor from the field's current selection
                                        val fv = lazyFields.getOrElse(idx) { TextFieldValue("") }
                                        cursorLine = idx + 1
                                        cursorCol = fv.selection.start.coerceAtMost(fv.text.length) + 1
                                    }
                                }
                                .onKeyEvent { event ->
                                    // Only handle KeyUp to avoid double-trigger
                                    if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                                    // Backspace at line start → merge with previous line
                                    if (event.key == Key.Backspace && idx > 0) {
                                        val fv = lazyFields.getOrElse(idx) { TextFieldValue("") }
                                        if (fv.selection.collapsed && fv.selection.start == 0) {
                                            handleLine(idx, TextFieldValue(""))
                                            cursorLine = idx
                                            cursorCol = lazyFields.getOrElse(idx - 1) { TextFieldValue("") }.text.length + 1
                                            return@onKeyEvent true
                                        }
                                    }
                                    false
                                },
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp, color = onSurface, lineHeight = lineHeightSp),
                            cursorBrush = SolidColor(accent),
                            visualTransformation = highlighter
                        )
                    }
                }
            } else {
                // ── Read-only viewer with syntax highlighting ──
                val lineStarts = remember(txt) { val off = mutableListOf(0); var i = 0; while (i < txt.length) { if (txt[i] == '\n') off.add(i + 1); i++ }; off }
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(lineStarts.size, key = { it }) { idx ->
                        val s = lineStarts[idx]; val e = if (idx + 1 < lineStarts.size) lineStarts[idx + 1] - 1 else txt.length
                        val line = if (e > s) txt.substring(s, e) else ""
                        Row(Modifier.fillMaxWidth()) {
                            Text("${idx + 1}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = muted, textAlign = TextAlign.End,
                                modifier = Modifier.width(36.dp).padding(end = 4.dp, top = 2.dp))
                            Box(Modifier.width(1.dp).height(lineH).background(muted.copy(alpha = 0.15f)))
                            Spacer(Modifier.width(6.dp))
                            val hl = remember(line) { highlighter.filter(AnnotatedString(line)).text }
                            Text(hl, fontFamily = FontFamily.Monospace, fontSize = fontSizeSp, lineHeight = lineHeightSp, color = onSurface, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    // ── Exit / preview confirmation dialog ──
    if (showExitDialog) {
        val isPreview = exitReason == "preview"
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("未保存的修改", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) },
            text = { Text("你有未保存的修改，退出将丢失这些更改。", color = muted) },
            confirmButton = {
                TextButton(onClick = {
                    showExitDialog = false
                    scope.launch {
                        saving = true
                        val j = if (editMultiline) editText.text else lazyLines.joinToString("\n")
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
                }) { Text(if (isPreview) "保存并预览" else "保存并退出", color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitDialog = false }) { Text("取消", color = muted) }
                    TextButton(onClick = {
                        showExitDialog = false
                        if (isPreview) editMode = false else onClose()
                    }) { Text("不保存", color = Color(0xFFC62828)) }
                }
            },
            containerColor = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp)
        )
    }
}
