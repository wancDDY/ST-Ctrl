package com.tavern.app.console.memory

import com.tavern.app.console.AgentClient

/** One labelled row inside a section, already worded for the sheet. */
data class NodeDetailRow(val label: String, val value: String)

/** A titled group of rows: the stretches a person appears in, the memory rows about them. */
data class NodeDetailSection(val title: String, val rows: List<NodeDetailRow>)

/**
 * Everything the sheet can say about one node.
 *
 * A graph node on its own is a dot with a name; the reader who taps it is asking "what happened
 * there, who was in it, who is this to whom, what did they say, what do they carry". All of that
 * is already in the sidecar — scenes, entries, events, attributes and the state slots — so the
 * detail is a lookup across the rows that mention the node, never a model call and never a second
 * copy of the transcript. Rows are capped per section: a sheet that lists forty entries is the
 * same as no sheet.
 */
data class NodeDetail(
    val kindLabel: String,
    val title: String,
    val subtitle: String,
    val facts: List<NodeDetailRow>,
    val sections: List<NodeDetailSection>,
    /** Floors this node covers, when it has any; empty for rules-derived rows. */
    val floors: String,
    /** The range the reader can open as source turns, or null when there is nothing to show. */
    val source: Pair<Int, Int>?,
)

object MemoryNodeDetails {
    private const val MAX_ENTRIES = 6
    private const val MAX_SCENES = 4

    fun of(node: MemoryNode, graph: MemoryGraph, detail: AgentClient.MemoryDetail): NodeDetail {
        return when (node.kind) {
            MemoryNodeKind.PERSON -> person(node, graph, detail)
            MemoryNodeKind.PLACE -> place(node, graph, detail)
            MemoryNodeKind.ITEM -> item(node, graph, detail)
            MemoryNodeKind.PROMISE -> promise(node, graph, detail)
            MemoryNodeKind.EVENT -> event(node, graph, detail)
            MemoryNodeKind.SCENE -> scene(node, graph, detail)
            MemoryNodeKind.ENTRY -> entry(node, graph, detail)
        }
    }

    private fun person(
        node: MemoryNode,
        graph: MemoryGraph,
        detail: AgentClient.MemoryDetail,
    ): NodeDetail {
        val name = node.label
        val entity = entityFor(detail, name)
        val slot = detail.state?.people?.firstOrNull { it.title == name }
        // One row per label. The same person carries a role from the slot, a status from the
        // scene and an attribute row that may repeat either; listing all three read as a page
        // that could not make up its mind.
        val facts = mutableListOf<NodeDetailRow>()
        fun fact(label: String, value: String) {
            if (value.isBlank() || facts.any { it.label == label }) return
            facts += NodeDetailRow(label, value)
        }
        entity?.role?.let { fact("身份", it) }
        entity?.status?.let { fact("现状", it) }
        entity?.aliases?.takeIf { it.isNotEmpty() }?.let { fact("又称", it.joinToString("、")) }
        if (entity == null) slot?.note?.let { fact("此刻", it) }
        detail.attributes
            .filter { namesIt(entity, name, it.subject) && !MemoryGraphs.isCollective(it.subject) }
            .forEach { fact(it.predicate, it.value) }
        val sections = mutableListOf<NodeDetailSection>()

        val relations = graph.edges.filter {
            it.kind == MemoryEdgeKind.RELATION && (it.from == node.id || it.to == node.id)
        }.mapNotNull { edge ->
            val other = graph.node(if (edge.from == node.id) edge.to else edge.from) ?: return@mapNotNull null
            NodeDetailRow("与" + other.label, edge.label.ifEmpty { "有关系" })
        }
        // The row is stored from one side only; the heading says which side that is.
        if (relations.isNotEmpty()) sections += NodeDetailSection("关系（自本人一侧）", relations)

        val carrying = graph.edges.filter {
            it.kind == MemoryEdgeKind.HOLD && it.from == node.id
        }.mapNotNull { edge -> graph.node(edge.to)?.let { NodeDetailRow(it.label, "随身带着") } }
        if (carrying.isNotEmpty()) sections += NodeDetailSection("随身", carrying)

        val promises = graph.edges.filter {
            it.kind == MemoryEdgeKind.PROMISE && it.from == node.id
        }.mapNotNull { edge -> graph.node(edge.to)?.let { NodeDetailRow(it.label, it.note) } }
        if (promises.isNotEmpty()) sections += NodeDetailSection("约定", promises)

        val scenes = detail.scenes.filter { it.present.contains(name) }.takeLast(MAX_SCENES)
        if (scenes.isNotEmpty()) {
            sections += NodeDetailSection(
                "在哪些段落",
                scenes.map { scene ->
                    NodeDetailRow(sceneHeadline(scene), listOf(scene.event, scene.quote)
                        .firstOrNull { it.isNotEmpty() } ?: scene.summary)
                }.reversed(),
            )
        }

        val entries = detail.entries.filter { mentions(it, name) }.takeLast(MAX_ENTRIES)
        if (entries.isNotEmpty()) {
            sections += NodeDetailSection(
                "与之相关的记忆",
                entries.map { NodeDetailRow(floorOf(it.from), it.text) }.reversed(),
            )
        }

        // The subtitle only speaks when the facts above do not: repeating the role under the
        // name and again as a row is the same sentence three times.
        val subtitle = if (facts.isEmpty()) {
            listOfNotNull(entity?.role, slot?.note).firstOrNull { it.isNotEmpty() }.orEmpty()
        } else {
            ""
        }

        return NodeDetail(
            kindLabel = MemoryGraphs.labelOf(node.kind),
            title = name,
            subtitle = subtitle,
            facts = facts,
            sections = sections,
            floors = floorRange(node.from, node.to),
            source = node.from.takeIf { it >= 0 }?.let { it to node.to },
        )
    }

    private fun item(
        node: MemoryNode,
        graph: MemoryGraph,
        detail: AgentClient.MemoryDetail,
    ): NodeDetail {
        val name = node.label
        val holder = graph.edges.firstOrNull { it.kind == MemoryEdgeKind.HOLD && it.to == node.id }
            ?.let { graph.node(it.from)?.label }
        val facts = buildList {
            holder?.takeIf { it.isNotEmpty() }?.let { add(NodeDetailRow("现在在", it)) }
            detail.state?.items?.firstOrNull { it.title == name }?.note
                ?.takeIf { it.isNotEmpty() }?.let { if (holder == null) add(NodeDetailRow("位置", it)) }
            detail.attributes.filter { namesIt(entityFor(detail, name), name, it.subject) }.take(3)
                .forEach { add(NodeDetailRow(it.predicate, it.value)) }
        }
        val sections = mutableListOf<NodeDetailSection>()

        val scenes = detail.scenes.filter { scene ->
            holder != null && scene.present.contains(holder)
        }.takeLast(MAX_SCENES)
        if (scenes.isNotEmpty()) {
            sections += NodeDetailSection(
                "跟着谁出现在哪",
                scenes.map { NodeDetailRow(sceneHeadline(it), it.event.ifEmpty { it.summary }) }.reversed(),
            )
        }

        val events = detail.events.filter { it.text.contains(name) || it.effect.contains(name) }
            .takeLast(3)
        if (events.isNotEmpty()) {
            sections += NodeDetailSection(
                "相关转折",
                events.map { NodeDetailRow(it.whenText.ifEmpty { "经过" }, it.text) }.reversed(),
            )
        }

        val entries = detail.entries.filter { mentions(it, name) }.takeLast(MAX_ENTRIES)
        if (entries.isNotEmpty()) {
            sections += NodeDetailSection(
                "与之相关的记忆",
                entries.map { NodeDetailRow(floorOf(it.from), it.text) }.reversed(),
            )
        }

        return NodeDetail(
            kindLabel = MemoryGraphs.labelOf(node.kind),
            title = name,
            subtitle = holder?.takeIf { it.isNotEmpty() }?.let { "在$it 身上" }.orEmpty(),
            facts = facts,
            sections = sections,
            floors = floorRange(node.from, node.to),
            source = node.from.takeIf { it >= 0 }?.let { it to node.to },
        )
    }

    private fun promise(
        node: MemoryNode,
        graph: MemoryGraph,
        detail: AgentClient.MemoryDetail,
    ): NodeDetail {
        val owner = graph.edges.firstOrNull { it.kind == MemoryEdgeKind.PROMISE && it.to == node.id }
            ?.let { graph.node(it.from)?.takeIf { n -> n.kind == MemoryNodeKind.PERSON }?.label }
        return NodeDetail(
            kindLabel = MemoryGraphs.labelOf(node.kind),
            title = node.label,
            subtitle = node.note.ifEmpty { owner?.let { "由$it 许下" }.orEmpty() },
            facts = listOfNotNull(
                owner?.takeIf { it.isNotEmpty() }?.let { NodeDetailRow("谁许下", it) },
                node.note.takeIf { it.isNotEmpty() }?.let { NodeDetailRow("何时", it) },
            ),
            sections = listOfNotNull(
                graph.node("scene")?.let { scene ->
                    NodeDetailSection(
                        "在哪儿说定",
                        listOf(NodeDetailRow(scene.label, scene.note)),
                    )
                },
            ),
            floors = floorRange(node.from, node.to),
            source = node.from.takeIf { it >= 0 }?.let { it to node.to },
        )
    }

    private fun event(
        node: MemoryNode,
        graph: MemoryGraph,
        detail: AgentClient.MemoryDetail,
    ): NodeDetail {
        val index = node.id.substringAfter("event:", "").toIntOrNull()
        val row = index?.let { detail.events.getOrNull(it) }
        val who = graph.edges.filter { it.to == node.id && it.kind != MemoryEdgeKind.CAUSE }
            .mapNotNull { graph.node(it.from)?.takeIf { n -> n.kind == MemoryNodeKind.PERSON }?.label }
        val where = graph.edges.filter { it.to == node.id && it.kind == MemoryEdgeKind.AT }
            .mapNotNull { graph.node(it.from)?.takeIf { n -> n.kind == MemoryNodeKind.PLACE }?.label }
        val facts = listOfNotNull(
            row?.whenText?.takeIf { it.isNotEmpty() }?.let { NodeDetailRow("何时", it) },
            where.takeIf { it.isNotEmpty() }?.let { NodeDetailRow("何地", it.joinToString("、")) },
            who.takeIf { it.isNotEmpty() }?.let { NodeDetailRow("在场", it.joinToString("、")) },
            row?.cause?.takeIf { it.isNotEmpty() }?.let { NodeDetailRow("起因", it) },
            row?.effect?.takeIf { it.isNotEmpty() }?.let { NodeDetailRow("结果", it) },
        )
        return NodeDetail(
            kindLabel = MemoryGraphs.labelOf(node.kind),
            title = node.label,
            subtitle = row?.effect.orEmpty(),
            facts = facts,
            sections = emptyList(),
            floors = floorRange(node.from, node.to),
            source = node.from.takeIf { it >= 0 }?.let { it to node.to },
        )
    }

    private fun scene(
        node: MemoryNode,
        graph: MemoryGraph,
        detail: AgentClient.MemoryDetail,
    ): NodeDetail {
        // The centre node's label is the place text the state carries; the stretches name the
        // same spot with varying detail in brackets, so the match folds on the text before the
        // bracket and falls back to containment before it falls back to the newest stretch.
        val placeText = normalizePlace(node.label)
        val row = detail.scenes.lastOrNull { normalizePlace(it.location) == placeText }
            ?: detail.scenes.lastOrNull { it.location.contains(placeText) || placeText.contains(it.location) }
            ?: detail.scenes.lastOrNull()
        val people = graph.edges.filter { it.from == node.id && it.kind == MemoryEdgeKind.PRESENCE }
            .mapNotNull { graph.node(it.to)?.label }
        val facts = buildList {
            if (node.label != "当前场景") add(NodeDetailRow("何地", node.label))
            row?.let { add(NodeDetailRow("何时", listOf(it.date, it.time).filter(String::isNotEmpty).joinToString(" "))) }
            row?.location?.takeIf { it.isNotEmpty() && it != node.label }
                ?.let { add(NodeDetailRow("这一段的地点", it)) }
            val cast = people.ifEmpty { row?.present.orEmpty() }
            if (cast.isNotEmpty()) add(NodeDetailRow("在场", cast.joinToString("、")))
            row?.entered?.takeIf { it.isNotEmpty() }?.let {
                add(NodeDetailRow("本段加入", it.joinToString("、")))
            }
            row?.left?.takeIf { it.isNotEmpty() }?.let {
                add(NodeDetailRow("本段离开", it.joinToString("、")))
            }
            row?.event?.takeIf { it.isNotEmpty() }?.let { add(NodeDetailRow("这一段的变化", it)) }
            row?.summary?.takeIf { it.isNotEmpty() }?.let { add(NodeDetailRow("经过", it)) }
            row?.quote?.takeIf { it.isNotEmpty() }?.let { add(NodeDetailRow("说过的话", "「$it」")) }
        }
        val inRange = row?.let { scene ->
            detail.entries.filter { it.from in scene.from..scene.to }.takeLast(MAX_ENTRIES)
        }.orEmpty()
        val sections = if (inRange.isEmpty()) emptyList() else listOf(
            NodeDetailSection(
                "这一段的记忆",
                inRange.map { NodeDetailRow(floorOf(it.from), it.text) }.reversed(),
            ),
        )
        return NodeDetail(
            kindLabel = "当前场景",
            title = node.label,
            subtitle = row?.let { listOf(it.date, it.time, it.location).filter(String::isNotEmpty).joinToString("，") }
                .orEmpty(),
            facts = facts,
            sections = sections,
            floors = row?.let { floorRange(it.from, it.to) } ?: floorRange(node.from, node.to),
            source = row?.let { it.from to it.to } ?: node.from.takeIf { it >= 0 }?.let { it to node.to },
        )
    }

    private fun entry(
        node: MemoryNode,
        graph: MemoryGraph,
        detail: AgentClient.MemoryDetail,
    ): NodeDetail {
        val row = detail.entries.firstOrNull { "entry:${it.id}" == node.id }
        val about = graph.edges.filter { it.from == node.id }
            .mapNotNull { graph.node(it.to)?.label }
        return NodeDetail(
            kindLabel = "记忆条目",
            title = node.label,
            subtitle = about.joinToString("、"),
            facts = buildList {
                row?.tags?.takeIf { it.isNotEmpty() }?.let { add(NodeDetailRow("标签", it.joinToString("、"))) }
                row?.let { add(NodeDetailRow("出自", floorOf(it.from))) }
            },
            sections = emptyList(),
            floors = floorRange(node.from, node.to),
            source = node.from.takeIf { it >= 0 }?.let { it to node.to },
        )
    }

    private fun place(
        node: MemoryNode,
        graph: MemoryGraph,
        detail: AgentClient.MemoryDetail,
    ): NodeDetail {
        val placeText = normalizePlace(node.label)
        val stretches = detail.scenes.filter { normalizePlace(it.location) == placeText }
        val latest = stretches.lastOrNull()
        val cast = stretches.flatMap { it.present }.distinct()
        val facts = buildList {
            latest?.let {
                add(NodeDetailRow("最近一次", listOf(it.date, it.time).filter(String::isNotEmpty).joinToString(" ")))
            }
            if (cast.isNotEmpty()) add(NodeDetailRow("来过的人", cast.joinToString("、")))
            if (node.note == "此刻") add(NodeDetailRow("状态", "当前场景所在"))
        }
        val sections = if (stretches.isEmpty()) {
            emptyList()
        } else {
            listOf(
                NodeDetailSection(
                    "在这个地点的段落",
                    stretches.map { scene ->
                        NodeDetailRow(
                            sceneHeadline(scene),
                            listOf(scene.event, scene.quote).firstOrNull { it.isNotEmpty() }
                                ?: scene.summary,
                        )
                    }.reversed().take(MAX_SCENES),
                ),
            )
        }
        val relatedEvents = stretches.map { it.from to it.to }
        return NodeDetail(
            kindLabel = MemoryGraphs.labelOf(node.kind),
            title = node.label,
            subtitle = latest
                ?.let { listOf(it.date, it.time).filter(String::isNotEmpty).joinToString("，") }
                .orEmpty(),
            facts = facts,
            sections = sections,
            floors = latest?.let { floorRange(it.from, it.to) }.orEmpty(),
            source = relatedEvents.lastOrNull()?.let { (from, to) -> from.takeIf { f -> f >= 0 }?.let { f -> f to to } },
        )
    }

    /** One entity row for a name, folded through the aliases the memory records. */
    private fun entityFor(detail: AgentClient.MemoryDetail, name: String): AgentClient.MemoryEntity? {
        val direct = detail.entities.firstOrNull { it.name == name }
        if (direct != null) return direct
        return detail.entities.firstOrNull { name in it.aliases }
    }

    /** Whether one attribute row is about this name, directly or under an alias. */
    private fun namesIt(entity: AgentClient.MemoryEntity?, name: String, subject: String): Boolean {
        if (subject == name) return true
        val canonical = entity?.let { if (name in it.aliases || it.name == name) it.name else null }
        return canonical != null && (subject == canonical || subject in entity?.aliases.orEmpty())
    }

    /** The spot name without its bracketed state, which the stretches vary per visit. */
    private fun normalizePlace(raw: String): String = raw.substringBefore("（").trim()

    private fun mentions(entry: AgentClient.MemoryEntry, name: String): Boolean {
        if (name.isEmpty()) return false
        if (entry.tags.any { it == name }) return true
        return entry.text.contains(name)
    }

    private fun sceneHeadline(scene: AgentClient.MemoryScene): String {
        val whenText = listOf(scene.date, scene.time).filter(String::isNotEmpty).joinToString(" ")
        val parts = listOf(
            "第 ${scene.from + 1}–${scene.to + 1} 层",
            whenText,
            scene.location,
        ).filter(String::isNotEmpty)
        return parts.joinToString("｜")
    }

    private fun floorOf(from: Int): String = if (from >= 0) "第 ${from + 1} 层" else "规则推断"

    private fun floorRange(from: Int, to: Int): String = when {
        from < 0 -> ""
        to >= from -> "第 ${from + 1}–${to + 1} 层"
        else -> "第 ${from + 1} 层起"
    }
}
