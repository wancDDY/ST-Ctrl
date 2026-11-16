package com.tavern.app.console.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.memory.MemoryEdge
import com.tavern.app.console.memory.MemoryEdgeKind
import com.tavern.app.console.memory.MemoryGraph
import com.tavern.app.console.memory.MemoryLayout3D
import com.tavern.app.console.memory.MemoryNode
import com.tavern.app.console.memory.MemoryNodeKind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The map as a cylinder: every depth ring floats at its own height, the scene hub at the bottom
 * and the dust at the top. A drag orbits the camera, a pinch zooms, and the perspective itself
 * declutters — far nodes shrink and fade, so only the near side ever reads as dense. The shape
 * comes from MemoryLayout3D: the layer supplies the height, the flat map the order around a ring.
 */
private const val FOCAL = 1.5f

private val NEUTRAL_MIX = Color(0xFF6B7280)

// A twelfth of grey per hue: enough to stop six saturated swatches from reading as a legend
// sample and start reading as material.
private fun settled(color: Color): Color = lerp(color, NEUTRAL_MIX, 0.16f)

private fun colorOf(kind: MemoryNodeKind): Color = when (kind) {
    MemoryNodeKind.SCENE -> Color(0xFF1D4ED8)
    MemoryNodeKind.PERSON -> Color(0xFF0D9488)
    MemoryNodeKind.PLACE -> Color(0xFF0369A1)
    MemoryNodeKind.ITEM -> Color(0xFFB45309)
    MemoryNodeKind.PROMISE -> Color(0xFF7C3AED)
    MemoryNodeKind.EVENT -> Color(0xFFDB2777)
    MemoryNodeKind.ENTRY -> Color(0xFF94A3B8)
}.let(::settled)

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
    MemoryEdgeKind.PRESENCE -> 0.30f
    MemoryEdgeKind.CAUSE -> 0.55f
    MemoryEdgeKind.OTHER -> 0.38f
}

private fun baseRadius(node: MemoryNode): Float = when (node.kind) {
    MemoryNodeKind.SCENE -> 20f
    MemoryNodeKind.ENTRY -> 4f + node.weight * 3f
    else -> 5f + node.weight * 6f
}

@Composable
internal fun MemoryGraph3DView(
    graph: MemoryGraph,
    showEntries: Boolean,
    onToggleEntries: (Boolean) -> Unit,
    onNode: (MemoryNode) -> Unit,
    modifier: Modifier = Modifier,
    selectedNode: MemoryNode? = null,
    selectedEdge: MemoryEdge? = null,
    onEdge: (MemoryEdge) -> Unit = {},
    use3D: Boolean,
    onToggle3D: (Boolean) -> Unit,
    layerLimit: Int = Int.MAX_VALUE,
) {
    val visibleNodes = remember(graph, layerLimit) {
        graph.nodes.filter { it.layer <= layerLimit }
    }
    val visibleIds = remember(visibleNodes) { visibleNodes.map { it.id }.toSet() }
    val visibleEdges = remember(graph, visibleIds) {
        graph.edges.filter { it.from in visibleIds && it.to in visibleIds }
    }

    var yaw by remember { mutableFloatStateOf(0.7f) }
    var pitch by remember { mutableFloatStateOf(0.44f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var interacting by remember { mutableStateOf(false) }
    var lastInputAt by remember { mutableFloatStateOf(0f) }

    // A slow idle orbit, timed by real milliseconds: a gesture or an open sheet pauses it,
    // three quiet seconds bring it back at roughly eight degrees a second.
    LaunchedEffect(visibleNodes) {
        var last = withFrameMillis { it }
        var pending = 0L
        while (true) {
            withFrameMillis { now ->
                val delta = (now - last).coerceAtLeast(1)
                last = now
                if (!interacting && selectedNode == null && selectedEdge == null &&
                    now - lastInputAt > 3000f
                ) {
                    // Eight degrees a second does not need sixty repaints: accumulate frames
                    // and advance the angle roughly thirty times a second instead.
                    pending += delta
                    if (pending >= 33L) {
                        yaw += pending * 0.00014f
                        pending = 0L
                    }
                } else {
                    pending = 0L
                }
            }
        }
    }

    // Read the palette in composition, not in the draw scope: the labels sit on the page
    // background, so they have to follow the theme instead of a fixed slate.
    val backdrop = MaterialTheme.colorScheme.background
    val labelInk = MaterialTheme.colorScheme.onSurface
    val labelMuted = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    // The default cache holds eight layouts. Both maps measure one label per node, so a
    // zoom or an orbit re-laid out every label on every frame; a cache wide enough for
    // the whole map turns that into a lookup.
    val measurer = rememberTextMeasurer(cacheSize = 128)

    // The projection the last frame drew, shared with the tap detector. A plain map: writing it
    // during the draw pass must not trigger a recomposition.
    val projected = remember(graph) {
        HashMap<String, FloatArray>() // id -> [screenX, screenY, dotRadiusPx, depth]
    }

    // Dots with no edge are drawn but excluded from framing; a lone fragment on the top ring
    // otherwise decides the scale of the whole map.
    val connectedIds = remember(visibleEdges) {
        HashSet<String>(visibleEdges.size * 2).apply {
            visibleEdges.forEach { edge ->
                add(edge.from)
                add(edge.to)
            }
        }
    }

    // Positions are the cylinder itself: one shared radius per layer, one height per depth. That
    // is arithmetic over the node list, so it runs in composition without a first-frame delay —
    // the background pass only existed for the force layout's iterations.
    val layout = remember(graph) { MemoryLayout3D.positions(graph) }

    // The legend is a sibling below the canvas, never an overlay. Floating over it would let the
    // canvas extend underneath, so the projection would be centred on a box the reader can only
    // see two thirds of, and a node could end up hidden behind the legend.
    Column(modifier) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // A pinch can still push a dot past the edge; without this it lands on the
                // depth chips above instead of being clipped at the edge of the map.
                .clipToBounds()
                .pointerInput(visibleNodes, visibleEdges) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        interacting = true
                        yaw += pan.x * 0.008f
                        pitch = (pitch + pan.y * 0.006f).coerceIn(0.08f, 1.3f)
                        zoom = (zoom * gestureZoom).coerceIn(0.55f, 2.6f)
                    }
                }
                .pointerInput(visibleNodes, visibleEdges) {
                    detectTapGestures { position ->
                        // Tier one: a dot under the finger. Tier two: a projected line within
                        // reach. Tier three is the dot's margin, as on the flat map.
                        val hit = visibleNodes.minByOrNull { node ->
                            val p = projected[node.id] ?: return@minByOrNull Float.MAX_VALUE
                            hypot(p[0] - position.x, p[1] - position.y)
                        }
                        if (hit != null) {
                            val p = projected[hit.id]
                            if (p != null) {
                                val d = hypot(p[0] - position.x, p[1] - position.y)
                                val touch = with(density) { 24.dp.toPx() }
                                if (d <= max(p[2] + 10f, touch)) {
                                    onNode(hit)
                                    return@detectTapGestures
                                }
                            }
                        }
                        val threshold = with(density) { 22.dp.toPx() }
                        val nearest = visibleEdges.minByOrNull { edge ->
                            val a = projected[edge.from]
                            val b = projected[edge.to]
                            if (a == null || b == null) Float.MAX_VALUE
                            else distanceToSegment(
                                position.x, position.y, a[0], a[1], b[0], b[1],
                            )
                        }
                        if (nearest != null) {
                            val a = projected[nearest.from]
                            val b = projected[nearest.to]
                            if (a != null && b != null && distanceToSegment(
                                    position.x, position.y, a[0], a[1], b[0], b[1],
                                ) <= threshold
                            ) {
                                onEdge(nearest)
                            }
                        }
                    }
                },
        ) {
            val side = min(size.width, size.height)
            val center = Offset(size.width / 2f, size.height / 2f)
            val cosYaw = cos(yaw)
            val sinYaw = sin(yaw)
            val cosP = cos(pitch)
            val sinP = sin(pitch)
            // Centre on the cloud that is actually visible, not on the nominal layer range.
            val heights = visibleNodes.mapNotNull { layout[it.id]?.get(1) }
            val targetY = ((heights.maxOrNull() ?: 0f) + (heights.minOrNull() ?: 0f)) / 2f

            // One focus at a time, the same contract as the flat map.
            val focusNodes: Set<String> = if (selectedEdge != null) {
                setOf(selectedEdge.from, selectedEdge.to)
            } else {
                selectedNode?.let { node ->
                    graph.edges.filter { it.from == node.id || it.to == node.id }
                        .map { if (it.from == node.id) it.to else it.from } + node.id
                }.orEmpty().toSet()
            }
            fun edgeFocus(edge: MemoryEdge): Float = when {
                selectedEdge != null -> if (edge == selectedEdge) 1f else 0.12f
                focusNodes.isNotEmpty() -> if (edge.from in focusNodes || edge.to in focusNodes) 1f else 0.12f
                else -> 1f
            }
            fun nodeDim(id: String): Float = when {
                selectedEdge != null -> if (id in focusNodes) 1f else 0.22f
                focusNodes.isNotEmpty() -> if (id in focusNodes) 1f else 0.28f
                else -> 1f
            }

            projected.clear()
            val projectedRaw = HashMap<String, FloatArray>() // id -> [sx, sy, scale, depth]
            // Orbit the settled cloud. Nothing here depends on the flat map any more: the
            // layout already decided where each node floats.
            visibleNodes.forEach { node ->
                val world = layout[node.id] ?: return@forEach
                val x = world[0] * cosYaw - world[2] * sinYaw
                val z = world[0] * sinYaw + world[2] * cosYaw
                val y = world[1] - targetY
                val y2 = y * cosP - z * sinP
                val z2 = y * sinP + z * cosP
                // A 0.4 floor put the camera inside a dot on close orbits: FOCAL/depth then
                // explodes and the projection flies off the canvas.
                val depth = (5.2f - z2).coerceAtLeast(0.9f)
                val s = FOCAL / depth
                projectedRaw[node.id] = floatArrayOf(x * s, -y2 * s, s, depth)
            }

            // Centre what was actually projected, not the nominal layer range. The camera tilt
            // and the perspective pull the drawing off the middle, which is how half the canvas
            // ended up empty above the map.
            // Isolated dots do not get a vote on framing: one fragment four rings up deepens
            // the bounding box enough to shrink the whole cylinder into the lower half of the
            // canvas. They are still drawn, wherever their position lands.
            val framed = projectedRaw.filterKeys { it in connectedIds }.ifEmpty { projectedRaw }
            val lowest = framed.values.minOf { it[1] }
            val highest = framed.values.maxOf { it[1] }
            val midY = (lowest + highest) / 2f
            projectedRaw.values.forEach { it[1] -= midY }

            // Fit the projection on both axes. A four-layer cylinder is taller than it is wide,
            // so one shared radius either overflowed the height or left the map as a stamp in the
            // middle; the bounding box spends each axis separately.
            val maxX = framed.values.maxOf { abs(it[0]) }.coerceAtLeast(0.01f)
            val maxY = framed.values.maxOf { abs(it[1]) }.coerceAtLeast(0.01f)
            var fit = min(
                (size.width * 0.45f) / (maxX * side),
                (size.height * 0.45f) / (maxY * side),
            ).coerceIn(0.05f, 3f) * zoom

            // The far side sinks toward the background; the near side carries the reading.
            val maxScale = projectedRaw.values.maxOf { it[2] }
            fun depthFade(scale: Float): Float = (scale / maxScale).coerceIn(0.4f, 1f)

            // Layer rings. Without them a cylinder is only a column of dots and the depth of a
            // node has to be counted; these hairlines are the deck each layer sits on. They use
            // the dot transform, so they turn with the camera and stay under their own layer.
            // The root layer is skipped: its node sits on the axis, so a rim ring there would
            // draw an empty circle around nothing.
            val ringLayers = visibleNodes.map { it.layer }.distinct().filter { it > 0 }
            ringLayers.forEach { layer ->
                val y = layer * MemoryLayout3D.LAYER_HEIGHT - targetY
                val path = Path()
                val steps = 72
                for (step in 0..steps) {
                    val angle = (2.0 * PI * step / steps).toFloat()
                    val worldX = MemoryLayout3D.LAYER_RADIUS * cos(angle)
                    val worldZ = MemoryLayout3D.LAYER_RADIUS * sin(angle)
                    val x = worldX * cosYaw - worldZ * sinYaw
                    val z = worldX * sinYaw + worldZ * cosYaw
                    val y2 = y * cosP - z * sinP
                    val z2 = y * sinP + z * cosP
                    val depth = (5.2f - z2).coerceAtLeast(0.9f)
                    val scale = FOCAL / depth
                    val px = center.x + (x * scale) * fit * side
                    val py = center.y + (-y2 * scale - midY) * fit * side
                    if (step == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path = path,
                    color = labelMuted.copy(alpha = 0.20f),
                    style = Stroke(width = 1f),
                )
            }

            visibleEdges.forEach { edge ->
                val a = projectedRaw[edge.from] ?: return@forEach
                val b = projectedRaw[edge.to] ?: return@forEach
                val alpha = edgeAlpha(edge.kind) * (0.7f + edge.weight * 0.3f) * edgeFocus(edge)
                val ax = center.x + a[0] * fit * side
                val ay = center.y + a[1] * fit * side
                val bx = center.x + b[0] * fit * side
                val by = center.y + b[1] * fit * side
                // A shallow arc instead of a straight segment. Two dots joined by a ruler read
                // as a diagram; the same two joined by a bow read as a web. The bow scales
                // with the run, so short hops stay almost straight.
                val dx = bx - ax
                val dy = by - ay
                val run = hypot(dx, dy).coerceAtLeast(1f)
                val bow = run * 0.055f
                val path = Path().apply {
                    moveTo(ax, ay)
                    quadraticBezierTo(
                        (ax + bx) / 2f - dy / run * bow,
                        (ay + by) / 2f + dx / run * bow,
                        bx,
                        by,
                    )
                }
                drawPath(
                    path = path,
                    color = edgeColor(edge.kind).copy(alpha = alpha.coerceAtMost(0.95f)),
                    style = Stroke(
                        width = if (edge.dashed) 1f else 0.8f + edge.weight * 0.5f,
                        pathEffect = if (edge.dashed) {
                            PathEffect.dashPathEffect(floatArrayOf(8f, 7f))
                        } else {
                            null
                        },
                    ),
                )
            }

            val drawOrder = projectedRaw.entries.sortedByDescending { it.value[3] }
            drawOrder.forEach { (id, p) ->
                val node = graph.node(id) ?: return@forEach
                val nearness = (p[2] / maxScale).coerceIn(0.4f, 1f)
                val fog = (1f - nearness) * 0.55f
                val dim = nodeDim(id) * depthFade(p[2])
                val screen = Offset(
                    center.x + p[0] * fit * side,
                    center.y + p[1] * fit * side,
                )
                // Dust is drawn as a speck and everything else at full size: a ring of
                // equal beads reads as a necklace, a ring of specks with a few larger lights
                // in it reads as a structure with dust around it.
                val kindScale = if (node.kind == MemoryNodeKind.ENTRY) 0.62f else 1f
                val radius = (baseRadius(node) * p[2] * fit * side * 0.34f * kindScale)
                    .coerceIn(1.2f, if (node.kind == MemoryNodeKind.SCENE) 15f else 6f)
                projected[id] = floatArrayOf(screen.x, screen.y, radius, p[3])
                val color = lerp(colorOf(node.kind), backdrop, fog)
                // A light, not a bead. A wide bloom carries the node's presence and a small
                // core carries its position — no rim, no specular, no contact shadow. The
                // shaded-sphere version read as a jar of sweets, which is the impression that
                // could not be fixed by tuning a highlight.
                // A flat disc, not a light: a gradient bloom on every node turned the map into
                // a field of glows where depth had to be read from the blur rather than from
                // position and size. Flat fills keep the parallax readable and hold to one
                // material across the whole console.
                val halo = if (node.kind == MemoryNodeKind.ENTRY) 0f else radius * 2.2f
                if (halo > 0f) drawCircle(
                    color = color.copy(alpha = 0.10f * dim),
                    radius = halo,
                    center = screen,
                )
                drawCircle(
                    color = color.copy(alpha = 0.92f * dim),
                    radius = radius,
                    center = screen,
                )

                // The ends of a selected line, or the selected node itself, get a glow and a
                // bold ring: on a dimmed map this is the thing the eye lands on first.
                if (focusNodes.contains(id) && dim >= 0.99f) {
                    drawCircle(color = color.copy(alpha = 0.22f), radius = radius + 6f, center = screen)
                    drawCircle(
                        color = color.copy(alpha = 0.95f),
                        radius = radius + 4f,
                        center = screen,
                        style = Stroke(width = 2.5f),
                    )
                }
                if (node.kind == MemoryNodeKind.SCENE) {
                    drawCircle(
                        color = color.copy(alpha = 0.30f * dim),
                        radius = radius * 1.7f,
                        center = screen,
                        style = Stroke(width = 1.2f),
                    )
                }
            }

            // Labels ride on the nearest dots only, and a placed label reserves its row: on a
            // crowded ring they would otherwise stack into one unreadable smudge.
            val placedLabels = mutableListOf<Offset>()
            if (selectedNode != null) {
                projected[selectedNode.id]?.let { placedLabels.add(Offset(it[0], it[1] + it[2])) }
            }
            projected.entries.sortedBy { it.value[3] }.forEach { (id, p) ->
                if (placedLabels.size >= 6) return@forEach
                val node = graph.node(id) ?: return@forEach
                val anchor = Offset(p[0], p[1] + p[2])
                if (placedLabels.any { hypot(it.x - anchor.x, it.y - anchor.y) < 110f }) {
                    return@forEach
                }
                val layout = measurer.measure(
                    nodeText(node),
                    style = if (node.kind == MemoryNodeKind.ENTRY) {
                        TextStyle(fontSize = 9.sp, color = labelMuted)
                    } else {
                        TextStyle(fontSize = 10.sp, color = labelInk)
                    },
                )
                val labelX = (p[0] - layout.size.width / 2f)
                    .coerceIn(4f, size.width - layout.size.width - 4f)
                val labelY = p[1] + p[2] + with(density) { 3.dp.toPx() }
                drawText(textLayoutResult = layout, topLeft = Offset(labelX, labelY))
                placedLabels.add(anchor)
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
                .padding(
                    horizontal = com.tavern.app.console.theme.AppSpacing.md,
                    vertical = com.tavern.app.console.theme.AppSpacing.sm,
                )
                .fillMaxWidth(),
        )
    }
}
