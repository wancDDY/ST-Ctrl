package com.tavern.app.console.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.memory.MemoryEdge
import com.tavern.app.console.memory.MemoryEdgeKind
import com.tavern.app.console.memory.MemoryGraph
import com.tavern.app.console.memory.MemoryNode
import com.tavern.app.console.memory.MemoryNodeKind
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppCardShape
import com.tavern.app.console.theme.AppSpacing
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One colour per kind. They are chosen to stay apart on both plates: teal for the cast, amber for
 * what they carry, violet for what they promised, rose for what turned, slate for dust. A person
 * and a prop sharing a hue was half of why the map read as a single mass.
 */
/**
 * Shortest distance from a point to a segment, in pixels. Pure so the edge hit test can be
 * unit-tested without a canvas.
 */
internal fun distanceToSegment(
    px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float,
): Float {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy
    val t = if (lengthSquared <= 0f) {
        0f
    } else {
        (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
    }
    val nearestX = ax + dx * t
    val nearestY = ay + dy * t
    return hypot(px - nearestX, py - nearestY)
}

private fun colorOf(kind: MemoryNodeKind): Color = when (kind) {
    MemoryNodeKind.SCENE -> Color(0xFF1D4ED8)
    MemoryNodeKind.PERSON -> Color(0xFF0D9488)
    MemoryNodeKind.PLACE -> Color(0xFF0369A1)
    MemoryNodeKind.ITEM -> Color(0xFFB45309)
    MemoryNodeKind.PROMISE -> Color(0xFF7C3AED)
    MemoryNodeKind.EVENT -> Color(0xFFDB2777)
    MemoryNodeKind.ENTRY -> Color(0xFF94A3B8)
}

/**
 * Edges are coloured by what they mean, not by what they join. Drawn in one grey, a key in
 * someone's pocket and a master-apprentice bond look the same, and the only way to tell them
 * apart is to zoom in and read a label that is not there.
 */
private fun edgeColor(kind: MemoryEdgeKind): Color = when (kind) {
    MemoryEdgeKind.PRESENCE -> Color(0xFF0EA5E9)
    MemoryEdgeKind.AT -> Color(0xFF059669)
    MemoryEdgeKind.HOLD -> Color(0xFFD97706)
    MemoryEdgeKind.RELATION -> Color(0xFFDB2777)
    MemoryEdgeKind.PROMISE -> Color(0xFF7C3AED)
    MemoryEdgeKind.CAUSE -> Color(0xFF64748B)
    MemoryEdgeKind.OTHER -> Color(0xFF94A3B8)
}

private fun edgeAlpha(kind: MemoryEdgeKind): Float = when (kind) {
    MemoryEdgeKind.RELATION -> 0.85f
    MemoryEdgeKind.AT -> 0.60f
    MemoryEdgeKind.HOLD -> 0.70f
    MemoryEdgeKind.PROMISE -> 0.65f
    // The spokes to the cast are structure, not news: at full weight they turned the middle of
    // the map into a wheel whose lines outranked the facts they carried.
    MemoryEdgeKind.PRESENCE -> 0.26f
    MemoryEdgeKind.CAUSE -> 0.55f
    MemoryEdgeKind.OTHER -> 0.38f
}

/**
 * The map itself: the current scene in the middle, the cast around it, what they carry and what
 * turned outside that, and — when asked for — the entries as dust at the rim.
 *
 * Pan and zoom go through one transform, so hit testing inverts the same transform instead of
 * keeping a second copy of the layout. Labels appear with the zoom rather than all at once: at
 * the default scale a full sentence on every promise covers the person it belongs to.
 */
@Composable
fun MemoryGraphView(
    graph: MemoryGraph,
    showEntries: Boolean,
    onToggleEntries: (Boolean) -> Unit,
    onNode: (MemoryNode) -> Unit,
    modifier: Modifier = Modifier,
    layerLimit: Int = Int.MAX_VALUE,
    selectedNode: MemoryNode? = null,
    selectedEdge: MemoryEdge? = null,
    onEdge: (MemoryEdge) -> Unit = {},
    use3D: Boolean = false,
    onToggle3D: (Boolean) -> Unit = {},
) {
    // The depth the reader asked for. Positions come from the whole graph, so switching depth
    // never reshuffles what stays on screen; the rings just fold away past the limit.
    val visibleNodes = remember(graph, layerLimit) { graph.nodes.filter { it.layer <= layerLimit } }
    val visibleIds = remember(visibleNodes) { visibleNodes.map { it.id }.toSet() }
    val visibleEdges = remember(graph, visibleIds) {
        graph.edges.filter { it.from in visibleIds && it.to in visibleIds }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.6f, 3.2f)
        offset += panChange
    }
    // On a new graph or a new depth the view frames what is visible: the outermost visible ring
    // lands near the rim, so a shallow depth fills the screen instead of huddling around the
    // scene. A pinch afterwards still wins until the next change.
    LaunchedEffect(graph, layerLimit) {
        val reach = graph.nodes.filter { it.layer in 1..layerLimit }
            .maxOfOrNull { hypot(it.x - 0.5f, it.y - 0.5f) }
            ?: 0.455f
        scale = (0.46f / reach.coerceAtLeast(0.05f)).coerceIn(0.6f, 2.8f)
        offset = Offset.Zero
    }

    // The default cache holds eight layouts. Both maps measure one label per node, so a
    // zoom or an orbit re-laid out every label on every frame; a cache wide enough for
    // the whole map turns that into a lookup.
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val sceneRadius = with(density) { 13.dp.toPx() }
    // Label ink follows the theme: slate tones picked against a light page vanish on the dark
    // one, which is the mode the console is usually read in.
    val nodeLabelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
    val dustLabelStyle = TextStyle(fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Box(modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // The legend sits over the bottom of the frame; reserving its height keeps the
                // outer ring from being drawn underneath it.
                .padding(bottom = 120.dp)
                .clipToBounds()
                .transformable(transformState)
                .pointerInput(graph, scale, offset, visibleNodes, visibleEdges) {
                    detectTapGestures { position ->
                        val side = min(size.width, size.height).toFloat()
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val x = (position.x - centerX - offset.x) / (scale * side) + 0.5f
                        val y = (position.y - centerY - offset.y) / (scale * side) + 0.5f
                        val hit = visibleNodes.minByOrNull { node ->
                            val dx = (node.x - x) * side
                            val dy = (node.y - y) * side
                            hypot(dx, dy)
                        }
                        val nodeDistance = hit?.let { hypot((it.x - x) * side, (it.y - y) * side) }
                        // Three tiers, so a line between two dots stays reachable on a crowded
                        // map: the dot itself, then a line within reach, then the dot's margin.
                        if (hit != null && nodeDistance!! <= nodeRadius(hit, sceneRadius)) {
                            onNode(hit)
                            return@detectTapGestures
                        }
                        val edgeHitPx = with(density) { 22.dp.toPx() }
                        val nearest = visibleEdges.minByOrNull { edge ->
                            val a = graph.node(edge.from) ?: return@minByOrNull Float.MAX_VALUE
                            val b = graph.node(edge.to) ?: return@minByOrNull Float.MAX_VALUE
                            val ax = centerX + (a.x - 0.5f) * side * scale + offset.x
                            val ay = centerY + (a.y - 0.5f) * side * scale + offset.y
                            val bx = centerX + (b.x - 0.5f) * side * scale + offset.x
                            val by = centerY + (b.y - 0.5f) * side * scale + offset.y
                            distanceToSegment(position.x, position.y, ax, ay, bx, by)
                        }
                        if (nearest != null) {
                            val a = graph.node(nearest.from) ?: return@detectTapGestures
                            val b = graph.node(nearest.to) ?: return@detectTapGestures
                            val ax = centerX + (a.x - 0.5f) * side * scale + offset.x
                            val ay = centerY + (a.y - 0.5f) * side * scale + offset.y
                            val bx = centerX + (b.x - 0.5f) * side * scale + offset.x
                            val by = centerY + (b.y - 0.5f) * side * scale + offset.y
                            if (distanceToSegment(position.x, position.y, ax, ay, bx, by) <= edgeHitPx) {
                                onEdge(nearest)
                                return@detectTapGestures
                            }
                        }
                        if (hit != null && nodeDistance != null) {
                            val radius = nodeRadius(hit, sceneRadius) + with(density) { 16.dp.toPx() }
                            if (nodeDistance <= radius) onNode(hit)
                        }
                    }
                },
        ) {
            val side = min(size.width, size.height)
            val center = Offset(size.width / 2f, size.height / 2f)
            val orbitColor = Color(0xFF94A3B8).copy(alpha = 0.13f)

            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, center)
            }) {
                // One faint ring per visible depth, at the radius its nodes actually sit on.
                visibleNodes.filter { it.layer > 0 }
                    .map { (hypot(it.x - 0.5f, it.y - 0.5f) * 200).roundToInt() }
                    .distinct()
                    .forEach { ring ->
                        drawCircle(
                            color = orbitColor,
                            radius = side * ring / 400f,
                            center = center,
                            style = Stroke(width = 1f),
                        )
                    }

                fun at(node: MemoryNode): Offset = Offset(
                    center.x + (node.x - 0.5f) * side,
                    center.y + (node.y - 0.5f) * side,
                )

                // One focus at a time: a selected line stands alone, a selected node keeps its
                // own lines lit, and everything else steps back to a quarter of its ink.
                val focusedEdge = selectedEdge
                val focusNodes = if (focusedEdge != null) {
                    setOf(focusedEdge.from, focusedEdge.to)
                } else {
                    selectedNode?.let { node ->
                        graph.edges.filter { it.from == node.id || it.to == node.id }
                            .map { if (it.from == node.id) it.to else it.from } + node.id
                    }.orEmpty().toSet()
                }
                fun edgeFocus(edge: MemoryEdge): Float = when {
                    focusedEdge != null -> if (edge == focusedEdge) 1f else 0.25f
                    focusNodes.isNotEmpty() -> if (edge.from in focusNodes || edge.to in focusNodes) 1f else 0.25f
                    else -> 1f
                }
                fun nodeFocus(id: String): Float = when {
                    focusedEdge != null -> if (id in focusNodes) 1f else 0.45f
                    focusNodes.isNotEmpty() -> if (id in focusNodes) 1f else 0.55f
                    else -> 1f
                }

                visibleEdges.forEach { edge ->
                    val a = graph.node(edge.from) ?: return@forEach
                    val b = graph.node(edge.to) ?: return@forEach
                    drawEdgeLine(edge, at(a), at(b), scale, edgeFocus(edge))
                }

                visibleNodes.forEach { node ->
                    val position = at(node)
                    // Deep zoom caps the on-screen dot: unscaled, the scene disc grew to a
                    // plate and the biggest halos ran together into one pale wash.
                    val cap = if (node.kind == MemoryNodeKind.SCENE) {
                        sceneRadius * 2.1f / scale
                    } else {
                        24f / scale
                    }
                    val radius = minOf(nodeRadius(node, sceneRadius), cap)
                    val dim = nodeFocus(node.id)
                    val color = colorOf(node.kind)
                    drawCircle(color = color.copy(alpha = 0.10f * dim), radius = radius * 1.55f, center = position)
                    drawCircle(color = color.copy(alpha = dim), radius = radius, center = position)
                    drawCircle(color = Color.White.copy(alpha = dim), radius = radius, center = position, style = Stroke(width = 1.5f))
                    // The ends of a selected line, or the selected node itself, get a ring that
                    // reads as "this is what the sheet is about".
                    if (dim >= 1f && focusNodes.contains(node.id)) {
                        drawCircle(
                            color = color.copy(alpha = 0.9f),
                            radius = radius + 3f,
                            center = position,
                            style = Stroke(width = 2f),
                        )
                    }
                    if (node.kind == MemoryNodeKind.SCENE) {
                        drawCircle(
                            color = color.copy(alpha = 0.35f * dim),
                            radius = radius * 2.2f,
                            center = position,
                            style = Stroke(width = 1.2f),
                        )
                    }
                }
            }

            // Labels live outside the zoom transform: the dots scale, the words do not. Inside
            // the transform a deep zoom blew every label up with it and the names read as a pile
            // over the very nodes they name.
            fun onScreen(point: Offset): Offset = Offset(
                center.x + (point.x - center.x) * scale + offset.x,
                center.y + (point.y - center.y) * scale + offset.y,
            )

            // Edge labels sit under the nodes so a dot always wins the overlap with a pill.
            // They are placed along the line rather than at its middle: on a map with a scene
            // node in the centre every edge passes near the middle, and the midpoints of three
            // relations landed on top of each other. Alternating the position also separates
            // two labels that share a line.
            visibleEdges.forEachIndexed { index, edge ->
                val a = graph.node(edge.from) ?: return@forEachIndexed
                val b = graph.node(edge.to) ?: return@forEachIndexed
                val text = edgeText(edge)
                if (text.isEmpty() || edge.kind == MemoryEdgeKind.PRESENCE) return@forEachIndexed
                val minScale = if (edge.kind == MemoryEdgeKind.RELATION) 0.9f else 1.15f
                if (scale < minScale) return@forEachIndexed
                val from = Offset(
                    center.x + (a.x - 0.5f) * side,
                    center.y + (a.y - 0.5f) * side,
                )
                val to = Offset(
                    center.x + (b.x - 0.5f) * side,
                    center.y + (b.y - 0.5f) * side,
                )
                val length = hypot(to.x - from.x, to.y - from.y)
                if (length * scale < 96f) return@forEachIndexed
                val t = if (index % 2 == 0) 0.34f else 0.62f
                // Off the line, alternating sides: two labels that share a line otherwise sit
                // on top of each other, and a label that lands beside the centre covers the
                // scene's own name.
                val nx = -(to.y - from.y) / length
                val ny = (to.x - from.x) / length
                val shift = if (index % 2 == 0) 14f else -14f
                val anchor = Offset(
                    from.x + (to.x - from.x) * t + nx * shift,
                    from.y + (to.y - from.y) * t + ny * shift,
                )
                val labelAt = onScreen(anchor)
                if (hypot(labelAt.x - center.x, labelAt.y - center.y) < 70f) return@forEachIndexed
                drawEdgeLabel(
                    measurer = measurer,
                    text = text,
                    at = labelAt,
                    color = edgeColor(edge.kind),
                    frame = size,
                )
            }

            // One label per clear patch of screen. Drawing every name made a busy map read as a
            // grey band: the reader could not tell which word belonged to which dot. Labels are
            // now placed in priority order and one that would land on an already-placed label is
            // skipped. Nothing is lost — zooming separates them, and tapping a dot still names it.
            val drafts = ArrayList<DraftLabel>()
            visibleNodes.forEachIndexed { index, node ->
                if (scale < labelZoomOf(node.kind)) return@forEachIndexed
                val position = onScreen(
                    Offset(
                        center.x + (node.x - 0.5f) * side,
                        center.y + (node.y - 0.5f) * side,
                    ),
                )
                val layout = measurer.measure(
                    nodeText(node),
                    style = if (node.kind == MemoryNodeKind.ENTRY) dustLabelStyle else nodeLabelStyle,
                )
                val labelX = (position.x - layout.size.width / 2f)
                    .coerceIn(4f, size.width - layout.size.width - 4f)
                val cap = if (node.kind == MemoryNodeKind.SCENE) {
                    sceneRadius * 2.1f / scale
                } else {
                    24f / scale
                }
                val labelY = position.y + minOf(nodeRadius(node, sceneRadius), cap) * scale +
                    with(density) { 3.dp.toPx() }
                drafts += DraftLabel(
                    index = index,
                    priority = labelPriority(node),
                    layout = layout,
                    at = Offset(labelX, labelY),
                )
            }
            val keep = labelsThatFit(drafts.map { draft ->
                // Two pixels of air around each box: boxes that merely touch read as one word when
                // the gap is zero, and "not overlapping" is not the same as "legible".
                LabelBox(
                    index = draft.index,
                    priority = draft.priority,
                    left = draft.at.x - 2f,
                    top = draft.at.y - 1f,
                    right = draft.at.x + draft.layout.size.width + 2f,
                    bottom = draft.at.y + draft.layout.size.height + 1f,
                )
            }).toSet()
            drafts.forEach { draft ->
                if (draft.index in keep) {
                    drawText(textLayoutResult = draft.layout, topLeft = draft.at)
                }
            }
        }

        Legend(
            graph = graph,
            visibleNodes = visibleNodes.size,
            visibleEdges = visibleEdges.size,
            showEntries = showEntries,
            onToggleEntries = onToggleEntries,
            use3D = use3D,
            onToggle3D = onToggle3D,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(AppSpacing.md)
                .fillMaxWidth()
                .fillMaxWidth(),
        )
    }
}

/** The zoom at which a kind's label is worth the pixels it covers. */
private fun labelZoomOf(kind: MemoryNodeKind): Float = when (kind) {
    MemoryNodeKind.SCENE, MemoryNodeKind.PERSON -> 0.6f
    MemoryNodeKind.PLACE -> 0.8f
    MemoryNodeKind.ITEM -> 1.0f
    MemoryNodeKind.PROMISE, MemoryNodeKind.EVENT -> 1.25f
    MemoryNodeKind.ENTRY -> 1.8f
}

/** One measured label waiting for a clear place on screen. */
private data class DraftLabel(
    val index: Int,
    val priority: Float,
    val layout: TextLayoutResult,
    val at: Offset,
)

/** A label's footprint, in canvas pixels. */
internal data class LabelBox(
    val index: Int,
    val priority: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Which labels can be drawn at once.
 *
 * Highest priority first; a box that would overlap one already accepted is dropped. Ties keep the
 * earlier index, so the same frame always draws the same names. Zooming spreads the boxes apart
 * and a skipped name returns as soon as there is room — the thinning is a property of the current
 * view, not a decision stored anywhere.
 */
internal fun labelsThatFit(boxes: List<LabelBox>): List<Int> {
    val taken = ArrayList<LabelBox>()
    val kept = ArrayList<Int>()
    boxes.sortedWith(compareByDescending<LabelBox> { it.priority }.thenBy { it.index })
        .forEach { box ->
            val clash = taken.any { other ->
                other.left < box.right && box.left < other.right &&
                    other.top < box.bottom && box.top < other.bottom
            }
            if (clash) return@forEach
            taken += box
            kept += box.index
        }
    return kept
}

/**
 * What survives when two names want the same pixels: the scene anchors the map and the cast is
 * what a reader looks for; dust rows are the first to go.
 */
private fun labelPriority(node: MemoryNode): Float {
    val base = when (node.kind) {
        MemoryNodeKind.SCENE -> 7f
        MemoryNodeKind.PERSON -> 6f
        MemoryNodeKind.PLACE -> 5f
        MemoryNodeKind.ITEM -> 4f
        MemoryNodeKind.PROMISE -> 3f
        MemoryNodeKind.EVENT -> 2f
        MemoryNodeKind.ENTRY -> 1f
    }
    return base * 10f + node.weight
}

private fun nodeRadius(node: MemoryNode, sceneRadius: Float): Float = when (node.kind) {
    MemoryNodeKind.SCENE -> sceneRadius
    MemoryNodeKind.ENTRY -> 3.5f + node.weight * 3f
    else -> 6f + node.weight * 10f
}

internal fun nodeText(node: MemoryNode): String {
    val limit = when (node.kind) {
        MemoryNodeKind.SCENE -> 8
        MemoryNodeKind.PERSON -> 6
        MemoryNodeKind.PLACE -> 6
        MemoryNodeKind.ITEM -> 5
        MemoryNodeKind.PROMISE -> 8
        MemoryNodeKind.EVENT -> 8
        MemoryNodeKind.ENTRY -> 16
    }
    val text = node.label.replace("\n", " ").trim()
    return if (text.length > limit) text.take(limit) + "…" else text
}

/** The words on a line. A relation carries its own label; an event link says only what it is. */
private fun edgeText(edge: MemoryEdge): String {
    val text = edge.label.trim()
    if (text.isEmpty()) return ""
    return if (text.length > 8) text.take(8) + "…" else text
}

private fun DrawScope.drawEdgeLine(
    edge: MemoryEdge,
    start: Offset,
    end: Offset,
    scale: Float,
    focus: Float = 1f,
) {
    val alpha = edgeAlpha(edge.kind) * (0.7f + edge.weight * 0.3f) * focus
    // Presence lines stay thin no matter their weight: three of them at full width drew a
    // grey tripod over the centre that read as the map's subject.
    val width = when (edge.kind) {
        MemoryEdgeKind.PRESENCE -> 0.9f + edge.weight * 0.15f
        else -> if (edge.dashed) 1.2f else 1f + edge.weight * 0.9f
    }
    // The canvas transform scales the stroke, so a deep zoom turned a relation line into a
    // ribbon; the cap keeps every line under a fixed width on the screen. A focused line may
    // exceed the cap by a hair so it reads above its dimmed neighbours.
    val cap = if (focus >= 1f) 5f / scale + 1.5f else 5f / scale
    val strokeWidth = minOf(width, cap)
    drawLine(
        color = edgeColor(edge.kind).copy(alpha = alpha.coerceAtMost(0.95f)),
        start = start,
        end = end,
        strokeWidth = strokeWidth,
        pathEffect = if (edge.dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 7f)) else null,
    )
}

private fun DrawScope.drawEdgeLabel(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    color: Color,
    frame: Size,
) {
    val layout = measurer.measure(text, style = TextStyle(fontSize = 9.sp, color = color))
    val padH = 4f
    val padV = 1.5f
    val width = layout.size.width + padH * 2
    val height = layout.size.height + padV * 2
    val left = (at.x - width / 2f).coerceIn(2f, (frame.width - width - 2f).coerceAtLeast(2f))
    val top = (at.y - height / 2f).coerceIn(2f, (frame.height - height - 2f).coerceAtLeast(2f))
    drawRoundRect(
        color = Color.White.copy(alpha = 0.88f),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(height / 2f),
    )
    drawText(textLayoutResult = layout, topLeft = Offset(left + padH, top + padV))
}

@Composable
internal fun Legend(
    graph: MemoryGraph,
    visibleNodes: Int,
    visibleEdges: Int,
    showEntries: Boolean,
    onToggleEntries: (Boolean) -> Unit,
    use3D: Boolean,
    onToggle3D: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        // Two rows of samples, then a control row of its own: sharing the last line with the
        // node count squeezes the switches and changes the card's height between the flat and
        // the 3D map. Fixed-size chips keep the plate the same shape in both views.
        Column(modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KindDot(MemoryNodeKind.SCENE, "场景")
                KindDot(MemoryNodeKind.PERSON, "人物")
                KindDot(MemoryNodeKind.PLACE, "地点")
                KindDot(MemoryNodeKind.ITEM, "物品")
                KindDot(MemoryNodeKind.PROMISE, "约定")
                KindDot(MemoryNodeKind.EVENT, "事件")
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EdgeSample(MemoryEdgeKind.PRESENCE, "在场")
                EdgeSample(MemoryEdgeKind.AT, "出场")
                EdgeSample(MemoryEdgeKind.HOLD, "持有")
                EdgeSample(MemoryEdgeKind.RELATION, "关系")
                EdgeSample(MemoryEdgeKind.PROMISE, "约定")
                EdgeSample(MemoryEdgeKind.CAUSE, "起因")
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${visibleNodes}/${graph.nodes.size} 节点，${visibleEdges}/${graph.edges.size} 关联",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AppAlpha.onSurfaceTertiary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegendToggle("3D 视图", use3D, onToggle3D)
                    LegendToggle("记忆条目", showEntries, onToggleEntries)
                }
            }
        }
    }
}

/**
 * A two-state chip. It replaces a pair of switches: at this width the track plus its label
 * overflowed the row, and the two maps ended up with plates of different heights.
 */
@Composable
private fun LegendToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val background = if (checked) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val ink = if (checked) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(AppRadius.sm))
            .background(background)
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink)
    }
}


@Composable
private fun KindDot(kind: MemoryNodeKind, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(7.dp),
            shape = CircleShape,
            color = colorOf(kind),
        ) {}
        Text(
            " " + label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun EdgeSample(kind: MemoryEdgeKind, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(width = 14.dp, height = 6.dp)) {
            drawLine(
                color = edgeColor(kind),
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = if (kind == MemoryEdgeKind.CAUSE) 1.2f else 2f,
                pathEffect = if (kind == MemoryEdgeKind.CAUSE) {
                    PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                } else {
                    null
                },
            )
        }
        Text(
            " " + label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
