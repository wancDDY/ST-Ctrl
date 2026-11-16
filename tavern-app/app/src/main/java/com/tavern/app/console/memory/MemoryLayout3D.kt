package com.tavern.app.console.memory

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where each node floats in the 3D map: a cylinder.
 *
 * The map is built as depth rings — the scene hub at the bottom, one ring per step away from it —
 * so the honest three-dimensional shape is a stack of rings. Three proportions decide whether it
 * reads as a cylinder or as a bowl, and all three were tuned against a portrait canvas once and
 * came out wrong: the stack has to be taller than it is wide, every layer has to keep the same
 * radius, and a crowded layer's sub-rings have to sit almost on top of each other. A layer that
 * steps inward by a visible amount turns the shape into a wedding cake.
 *
 * Angles come from the flat map rather than from an even spread around the ring: the flat layout
 * already decided which dots belong together, and rotating them apart throws that away.
 */
object MemoryLayout3D {
    /** Vertical distance between two layers. Tall on purpose: this is the cylinder's axis. */
    const val LAYER_HEIGHT = 1.2f

    /**
     * Outer radius, shared by every layer. A radius taken from the flat map would open the upper
     * layers wider than the lower ones and turn the cylinder into a funnel.
     */
    const val LAYER_RADIUS = 1f

    private const val RING_STEP = 0.055f
    private const val RING_CAPACITY = 30
    private const val INNERMOST_RADIUS = 0.6f
    private const val AXIS_RADIUS = 0.1f

    fun positions(graph: MemoryGraph): Map<String, FloatArray> {
        val nodes = graph.nodes
        if (nodes.isEmpty()) return emptyMap()
        val out = HashMap<String, FloatArray>(nodes.size * 2)
        nodes.groupBy { it.layer }.forEach { (layer, list) ->
            if (layer == 0) {
                // The root connects to every layer. Put on the rim, its edges would all leave
                // from one side of the silhouette and bury the rings behind it, so it sits on
                // the axis and the lines fan out evenly.
                list.forEachIndexed { index, node ->
                    val angle = (2.0 * PI * index / list.size).toFloat()
                    val radius = if (list.size == 1) 0f else AXIS_RADIUS
                    out[node.id] = floatArrayOf(
                        cos(angle) * radius,
                        0f,
                        sin(angle) * radius,
                    )
                }
                return@forEach
            }
            // Who steps inward is decided by how far out the flat map put the node, so the
            // crowding the reader already saw there is the crowding resolved here.
            val ordered = list.sortedBy { hypot(it.x - 0.5f, it.y - 0.5f) }
            ordered.forEachIndexed { index, node ->
                val ring = index / RING_CAPACITY
                val radius = (LAYER_RADIUS - ring * RING_STEP)
                    .coerceAtLeast(INNERMOST_RADIUS)
                val angle = atan2(node.y - 0.5f, node.x - 0.5f)
                out[node.id] = floatArrayOf(
                    cos(angle) * radius,
                    layer * LAYER_HEIGHT,
                    sin(angle) * radius,
                )
            }
        }
        return out
    }
}
