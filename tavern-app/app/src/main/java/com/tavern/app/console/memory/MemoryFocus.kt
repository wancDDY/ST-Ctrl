package com.tavern.app.console.memory

import com.tavern.app.console.AgentClient

/**
 * The spread engine: from one anchor node, walk the lines outward and keep the subnetwork that
 * answers "what does this have to do with". Pure and deterministic — same graph, same anchor,
 * same result — so it can be unit-tested without a canvas and reused by any surface that has an
 * anchor, from a tapped node to a searched phrase.
 *
 * Three stops, whichever comes first: a score that decays by [decay] per step and cuts off at
 * [SCORE_FLOOR], a node budget that scales with the chat's length, or no new nodes left. Weak
 * lines (presence, mentions, dust) carry only one hop out of the anchor, so a roomful of people
 * cannot relay the spread across the whole map, and the scene hub is never expanded through.
 */
object MemoryFocus {

    private const val SCORE_FLOOR = 0.15f

    /** Why a node is on the focused map: the line it was reached through and how far out. */
    data class Reason(val viaLabel: String, val distance: Int, val kind: MemoryEdgeKind)

    data class Focus(
        val anchorId: String,
        val nodes: List<MemoryNode>,
        val edges: List<MemoryEdge>,
        /** One reason per kept node except the anchor itself. */
        val reasons: Map<String, Reason>,
        /** True when the budget cut the spread before it ran out of graph. */
        val capped: Boolean,
    )

    private fun isWeak(edge: MemoryEdge): Boolean =
        edge.kind == MemoryEdgeKind.PRESENCE || edge.kind == MemoryEdgeKind.OTHER || edge.steps >= 2f

    private fun lineName(edge: MemoryEdge): String = when {
        edge.label.isNotEmpty() -> edge.label
        edge.kind == MemoryEdgeKind.HOLD -> "持有"
        edge.kind == MemoryEdgeKind.RELATION -> "关系"
        edge.kind == MemoryEdgeKind.PROMISE -> "约定"
        edge.kind == MemoryEdgeKind.CAUSE -> "起因"
        edge.kind == MemoryEdgeKind.AT -> "出场"
        edge.kind == MemoryEdgeKind.PRESENCE -> "在场"
        else -> "提及"
    }

    fun spread(graph: MemoryGraph, anchorId: String, budget: Int, decay: Float = 0.6f): Focus =
        spread(graph, setOf(anchorId), budget, decay)

    /**
     * The spread from several anchors at once: "this sentence" touches a person, a place and a
     * turning point together, and the map grows outward from all of them as if they were one.
     * Every anchor counts as distance zero; unknown ids are dropped silently.
     */
    fun spread(graph: MemoryGraph, anchors: Set<String>, budget: Int, decay: Float = 0.6f): Focus {
        val anchorIds = anchors.filter { graph.node(it) != null }.toSet()
        if (anchorIds.isEmpty()) return Focus(
            anchorId = "",
            nodes = emptyList(),
            edges = emptyList(),
            reasons = emptyMap(),
            capped = false,
        )
        val adjacency = HashMap<String, MutableList<Pair<String, MemoryEdge>>>()
        graph.edges.forEach { edge ->
            adjacency.getOrPut(edge.from) { ArrayList() }.add(edge.to to edge)
            adjacency.getOrPut(edge.to) { ArrayList() }.add(edge.from to edge)
        }

        // Dijkstra from the anchor; the scene is a hub the spread may end on but never pass
        // through, and a weak line carries only its first hop.
        val distance = HashMap<String, Float>()
        val reachedBy = HashMap<String, MemoryEdge>()
        val settled = HashSet<String>()
        anchorIds.forEach { distance[it] = 0f }
        while (true) {
            val current = distance.entries
                .filter { it.key !in settled }
                .minByOrNull { it.value }
                ?: break
            settled += current.key
            if (current.key == "scene" && current.key !in anchorIds) continue
            val base = current.value
            (adjacency[current.key] ?: emptyList()).forEach { (neighbour, edge) ->
                if (current.key !in anchorIds && isWeak(edge)) return@forEach
                val candidate = base + if (edge.steps > 0f) edge.steps else 1f
                if (candidate < (distance[neighbour] ?: Float.MAX_VALUE)) {
                    distance[neighbour] = candidate
                    reachedBy[neighbour] = edge
                }
            }
        }

        // Score decays per whole step; past the floor the node reads as noise next to the
        // anchor, so it stays off the map even when the budget has room.
        val scoreFloor = SCORE_FLOOR
        val candidates = distance.entries
            .filter { it.key !in anchorIds }
            .map { it.key to kotlin.math.ceil(it.value).toInt() }
            .filter { (_, depth) -> Math.pow(decay.toDouble(), depth.toDouble()) >= scoreFloor }
            .sortedBy { it.second }
        // The budget counts the whole map, anchor included: the caller asked for a map of that
        // size, not for that many extras around one more.
        val kept = candidates.take((budget - 1).coerceAtLeast(0))
        val capped = candidates.size > kept.size

        val keptIds = kept.map { it.first }.toSet() + anchorIds
        val nodes = graph.nodes.filter { it.id in keptIds }
        val edges = graph.edges.filter { it.from in keptIds && it.to in keptIds }
        val reasons = kept.associate { (id, depth) ->
            val edge = reachedBy[id]
            val name = edge?.let(::lineName) ?: "关联"
            id to Reason(name, depth, edge?.kind ?: MemoryEdgeKind.OTHER)
        }
        return Focus(
            anchorId = anchorIds.first(),
            nodes = nodes,
            edges = edges,
            reasons = reasons,
            capped = capped,
        )
    }

    /**
     * The bodies the map already has that one chat floor touches: the turning point, the memory
     * rows and the props whose floors cover it, plus the cast and the place of the stretch it
     * falls in. These are the anchors a "this sentence" map grows from.
     */
    fun anchorsForFloor(
        graph: MemoryGraph,
        detail: AgentClient.MemoryDetail,
        floor: Int,
    ): Set<String> {
        val anchors = HashSet<String>()
        graph.nodes.forEach { node ->
            if (node.from >= 0 && floor >= node.from && floor <= maxOf(node.to, node.from)) {
                anchors += node.id
            }
        }
        detail.scenes.forEach { scene ->
            if (scene.from <= floor && floor <= scene.to) {
                scene.present.forEach { name ->
                    val id = "person:$name"
                    if (graph.node(id) != null) anchors += id
                }
                val placeId = "place:" + scene.location
                    .substringBefore("（").substringBefore("，").substringBefore("、").trim()
                if (graph.node(placeId) != null) anchors += placeId
            }
        }
        return anchors
    }
}
