package com.tavern.app.console.memory

import com.tavern.app.console.AgentClient

/** One labelled row inside the edge sheet. */
data class EdgeDetailRow(val label: String, val value: String)

/** A titled group of rows: the other stored records that back the same line. */
data class EdgeDetailSection(val title: String, val rows: List<EdgeDetailRow>)

/**
 * Everything the sheet can say about one line.
 *
 * The edge carries the record it was built from; the sheet adds what the same endpoints imply
 * elsewhere in the sidecar, so a visit line can list the other stretches two people shared
 * without the graph keeping a second copy of any of it.
 */
data class EdgeDetail(
    val kindLabel: String,
    /** The two ends joined the way the line reads: an arrow when it points, a dash when not. */
    val title: String,
    /** One sentence on where the line comes from. */
    val subtitle: String,
    val facts: List<EdgeDetailRow>,
    val sections: List<EdgeDetailSection>,
    /** Floors the reader can open as source turns, or null when the line proves nothing. */
    val source: Pair<Int, Int>?,
)

object MemoryEdgeDetails {

    fun of(edge: MemoryEdge, graph: MemoryGraph, detail: AgentClient.MemoryDetail): EdgeDetail {
        val from = graph.node(edge.from)
        val to = graph.node(edge.to)
        val directed = when (edge.kind) {
            MemoryEdgeKind.HOLD, MemoryEdgeKind.PROMISE, MemoryEdgeKind.CAUSE, MemoryEdgeKind.AT -> true
            MemoryEdgeKind.OTHER -> to?.id?.startsWith("event:") == true ||
                to?.id?.startsWith("entry:") == true
            else -> false
        }
        val fromName = from?.label ?: edge.from
        val toName = to?.label ?: edge.to
        val title = if (directed) "$fromName → $toName" else "$fromName — $toName"

        val kindLabel = kindLabelOf(edge, from, to)
        val structural = edge.source.startsWith("structural:")
        val subtitle = when {
            structural -> "把图连起来的结构线，不来自某条记录"
            edge.fromFloor >= 0 -> {
                val last = edge.toFloor.coerceAtLeast(edge.fromFloor)
                "依据：第 ${edge.fromFloor + 1}–${last + 1} 层的记录"
            }
            else -> "依据：当前状态里的记录"
        }

        val facts = buildList {
            edge.evidence.takeIf { it.isNotEmpty() }?.let { add(EdgeDetailRow("原文", it)) }
            if (edge.kind == MemoryEdgeKind.RELATION && edge.fromFloor >= 0) {
                add(EdgeDetailRow("自", "第 ${edge.fromFloor + 1} 层起"))
            }
        }

        val sections = relatedRecords(edge, graph, detail)

        val source = edge.fromFloor.takeIf { it >= 0 }?.let { it to edge.toFloor.coerceAtLeast(it) }
        return EdgeDetail(
            kindLabel = kindLabel,
            title = title,
            subtitle = subtitle,
            facts = facts,
            sections = sections,
            source = source,
        )
    }

    private fun kindLabelOf(
        edge: MemoryEdge,
        from: MemoryNode?,
        to: MemoryNode?,
    ): String = when (edge.kind) {
        MemoryEdgeKind.HOLD -> "持有"
        MemoryEdgeKind.RELATION -> "关系"
        MemoryEdgeKind.PROMISE -> "约定"
        MemoryEdgeKind.CAUSE -> if (edge.dashed) "起因（推断）" else "起因"
        MemoryEdgeKind.AT -> when {
            to?.kind == MemoryNodeKind.EVENT -> "发生地"
            from?.kind == MemoryNodeKind.PERSON -> "出场"
            else -> "在"
        }
        MemoryEdgeKind.PRESENCE -> if (edge.source.startsWith("structural:")) "结构线" else "在场"
        MemoryEdgeKind.OTHER -> when {
            edge.source.startsWith("entry:") -> "提及"
            to?.id?.startsWith("event:") == true -> "涉及"
            else -> "关联"
        }
    }

    /**
     * Other stored records behind the same two ends, newest first and capped: the stretches a
     * pair shared beyond the newest one, the other turns someone took part in. State-sourced
     * lines have exactly one record by construction, so they list nothing.
     */
    private fun relatedRecords(
        edge: MemoryEdge,
        graph: MemoryGraph,
        detail: AgentClient.MemoryDetail,
    ): List<EdgeDetailSection> {
        val from = graph.node(edge.from) ?: return emptyList()
        val to = graph.node(edge.to) ?: return emptyList()
        val rows: List<EdgeDetailRow> = when {
            edge.kind == MemoryEdgeKind.AT && from.kind == MemoryNodeKind.PERSON &&
                to.kind == MemoryNodeKind.PLACE -> detail.scenes
                .filter {
                    normalizePlace(it.location) == to.label && it.present.contains(from.label)
                }
                .takeLast(3)
                .map { scene ->
                    EdgeDetailRow(
                        floorHead(scene.from, scene.to),
                        listOf(scene.event, scene.quote).firstOrNull { it.isNotEmpty() }
                            ?: scene.summary,
                    )
                }

            to.id.startsWith("event:") && from.kind == MemoryNodeKind.PERSON -> detail.events
                .filter { it.who.contains(from.label) }
                .takeLast(3)
                .map { event ->
                    EdgeDetailRow(
                        floorHead(event.from, event.to),
                        event.text,
                    )
                }

            else -> emptyList()
        }
        if (rows.size <= 1) return emptyList()
        return listOf(EdgeDetailSection("相关记录", rows.reversed()))
    }

    private fun normalizePlace(raw: String): String =
        raw.substringBefore("（").substringBefore("，").substringBefore("、").trim()

    private fun floorHead(from: Int, to: Int): String = when {
        from < 0 -> ""
        to >= from -> "第 ${from + 1}–${to + 1} 层｜"
        else -> "第 ${from + 1} 层起｜"
    }
}
