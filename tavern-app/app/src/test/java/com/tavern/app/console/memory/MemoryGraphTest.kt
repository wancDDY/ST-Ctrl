package com.tavern.app.console.memory

import com.tavern.app.console.AgentClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGraphTest {

    private fun state() = AgentClient.MemoryState(
        source = "units",
        location = "柴房",
        time = "深夜",
        present = listOf("阿甲", "阿乙"),
        people = listOf(
            AgentClient.MemoryFact("阿甲", "读者的搭档", from = 5),
            AgentClient.MemoryFact("阿乙", "收着钥匙的人", from = 8),
        ),
        items = listOf(AgentClient.MemoryFact("铜钥匙", "在阿乙身上", from = 10)),
        promises = listOf(AgentClient.MemoryFact("去码头接阿丁", "午后", from = 12)),
        relations = listOf(AgentClient.MemoryFact("阿甲与阿丁", "徒弟", from = 4)),
    )

    private fun events() = listOf(
        AgentClient.MemoryEvent(
            text = "阿甲把钥匙交给阿乙",
            whenText = "深夜",
            who = listOf("阿甲", "阿乙"),
            cause = "阿甲要出远门",
            effect = "钥匙归阿乙",
        ),
    )

    private fun entries() = listOf(
        AgentClient.MemoryEntry("e1", "阿乙怕打雷", listOf("阿乙"), "fact", 3, 3, 0),
    )

    @Test
    fun `a holder relation is an edge from the person who holds the item`() {
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = false)

        assertNotNull(graph.node("scene"))
        assertNotNull(graph.node("person:阿乙"))
        val edge = graph.edges.firstOrNull { it.from == "person:阿乙" && it.to == "item:铜钥匙" }
        assertNotNull("the item hangs off its holder", edge)
        assertEquals("持有", edge!!.label)
    }

    @Test
    fun `a relation between two people is one edge, not a third node`() {
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = false)

        assertNull(graph.node("relation:阿甲与阿丁"))
        assertTrue(graph.edges.any { it.label == "徒弟" })
    }

    @Test
    fun `an event connects to the people in it, and its cause stays dashed`() {
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = false)

        assertTrue(graph.edges.any { it.from == "person:阿乙" && it.to == "event:0" })
        val dashed = graph.edges.filter { it.dashed }
        assertTrue(dashed.all { it.label == "起因" })
    }

    @Test
    fun `entries are dust until asked for`() {
        val hidden = MemoryGraphs.build(state(), events(), entries(), showEntries = false)
        assertNull(hidden.node("entry:e1"))

        val shown = MemoryGraphs.build(state(), events(), entries(), showEntries = true)
        assertNotNull(shown.node("entry:e1"))
        assertTrue(shown.edges.any { it.from == "entry:e1" && it.to == "person:阿乙" })
    }

    @Test
    fun `the layout is deterministic and stays inside the frame`() {
        val first = MemoryGraphs.build(state(), events(), entries(), showEntries = true)
        val second = MemoryGraphs.build(state(), events(), entries(), showEntries = true)

        assertEquals(first.nodes.size, second.nodes.size)
        first.nodes.forEach { node ->
            val other = second.node(node.id)
            assertNotNull(other)
            assertEquals(node.x, other!!.x, 0.0001f)
            assertEquals(node.y, other.y, 0.0001f)
            assertTrue("x in frame: ${node.x}", node.x in 0f..1f)
            assertTrue("y in frame: ${node.y}", node.y in 0f..1f)
        }
    }

    @Test
    fun `a node nothing connects to is reported as isolated`() {
        val graph = MemoryGraphs.build(
            state = AgentClient.MemoryState(location = "柴房"),
            events = emptyList(),
            entries = listOf(AgentClient.MemoryEntry("e2", "一句没有落点的话", emptyList(), "fact", 1, 1, 0)),
            showEntries = true,
        )

        val isolated = graph.isolated().map { it.id }
        assertTrue(isolated.contains("entry:e2"))
        // The scene hangs on the place the state's location builds, so it is not loose any
        // more; the entry with no tags and no matching text still is.
        assertFalse(isolated.contains("scene"))
        assertFalse(graph.nodes.isEmpty())
    }

    @Test
    fun `the entry layer is capped`() {
        val many = (1..300).map {
            AgentClient.MemoryEntry("e$it", "第 $it 条", emptyList(), "fact", it, it, 0)
        }
        val graph = MemoryGraphs.build(state(), events(), many, showEntries = true)
        val entryNodes = graph.nodes.count { it.kind == MemoryNodeKind.ENTRY }
        assertTrue(entryNodes <= MemoryGraphs.MAX_ENTRIES)
    }

    @Test
    fun `a tag the chat keeps naming becomes a person on the map`() {
        val recurring = listOf(
            AgentClient.MemoryEntry("e1", "阿乙看见她离开", listOf("阿乙"), "fact", 1, 1, 0),
            AgentClient.MemoryEntry("e2", "阿乙没有表态", listOf("阿乙"), "fact", 2, 2, 0),
            AgentClient.MemoryEntry("e3", "一句只有分类标签的话", listOf("事实"), "fact", 3, 3, 0),
        )
        val graph = MemoryGraphs.build(
            state = AgentClient.MemoryState(location = "走廊"),
            events = emptyList(),
            entries = recurring,
            showEntries = true,
        )

        assertNotNull(graph.node("person:阿乙"))
        assertTrue(graph.edges.any { it.from == "entry:e1" && it.to == "person:阿乙" })
        assertNull("a category word is not a person", graph.node("person:事实"))
    }

    @Test
    fun `the rings separate possession from happening from dust`() {
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = true)

        assertEquals(1, graph.node("person:阿乙")!!.layer)
        assertEquals(2, graph.node("item:铜钥匙")!!.layer)
        // A turning point sits a step past the things it involves, and the dust lies deepest.
        assertEquals(3, graph.node("event:0")!!.layer)
        assertEquals(4, graph.node("entry:e1")!!.layer)
    }

    @Test
    fun `an unowned promise never names the room as its maker`() {
        val graph = MemoryGraphs.build(
            state = state().copy(promises = listOf(AgentClient.MemoryFact("谁先到谁请客", "午后", from = 12))),
            events = events(),
            entries = entries(),
            showEntries = false,
        )

        val promiseNode = graph.nodes.firstOrNull { it.kind == MemoryNodeKind.PROMISE }
        assertNotNull(promiseNode)
        val ownerLine = graph.edges.firstOrNull {
            it.kind == MemoryEdgeKind.PROMISE && it.to == promiseNode!!.id
        }
        assertNull("a promise with no maker gets no PROMISE line at all", ownerLine)
    }

    @Test
    fun `a place folds its bracketed variants and ties the cast to it`() {
        val scenes = listOf(
            AgentClient.MemoryScene(
                from = 1, to = 3, location = "柴房（门锁着）", date = "周一", time = "深夜",
                present = listOf("阿甲"), summary = "一段", event = "一件事", quote = "",
            ),
            AgentClient.MemoryScene(
                from = 4, to = 6, location = "柴房", date = "周一", time = "后半夜",
                present = listOf("阿乙"), summary = "又一段", event = "", quote = "一句话",
            ),
        )
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = false, scenes = scenes)

        val places = graph.nodes.filter { it.kind == MemoryNodeKind.PLACE }
        assertEquals(listOf("柴房"), places.map { it.label })
        assertTrue(graph.edges.any { it.kind == MemoryEdgeKind.AT && it.from == "person:阿甲" && it.to == "place:柴房" })
        assertTrue(graph.edges.any { it.kind == MemoryEdgeKind.AT && it.from == "person:阿乙" && it.to == "place:柴房" })
    }

    @Test
    fun `a turning point sits where its floors put it and carries them`() {
        val scenes = listOf(
            AgentClient.MemoryScene(
                from = 1, to = 3, location = "柴房", date = "", time = "",
                present = listOf("阿甲"), summary = "", event = "", quote = "",
            ),
        )
        val timed = listOf(
            AgentClient.MemoryEvent(
                text = "阿甲把钥匙交给阿乙", whenText = "深夜", who = listOf("阿甲", "阿乙"),
                cause = "", effect = "", from = 2, to = 3,
            ),
        )
        val graph = MemoryGraphs.build(state(), timed, entries(), showEntries = false, scenes = scenes)

        assertEquals(2, graph.node("event:0")!!.from)
        assertTrue(graph.edges.any { it.kind == MemoryEdgeKind.AT && it.from == "place:柴房" && it.to == "event:0" })
    }

    @Test
    fun `an alias resolves to the body the memory already has`() {
        val entities = listOf(
            AgentClient.MemoryEntity(
                name = "阿丙", kind = "person", aliases = listOf("小丙"),
            ),
        )
        val graph = MemoryGraphs.build(
            state = state().copy(present = listOf("阿甲", "阿乙"), promises = emptyList()),
            events = events(),
            entries = entries(),
            showEntries = false,
            entities = entities,
        )

        assertNull("the alias is not a second person", graph.node("person:小丙"))
        // A promise naming the alias hangs on the canonical body.
        val graph2 = MemoryGraphs.build(
            state = state().copy(promises = listOf(AgentClient.MemoryFact("陪小丙走一趟", "午后", from = 12))),
            events = events(),
            entries = entries(),
            showEntries = false,
            entities = entities,
        )
        val promise = graph2.nodes.firstOrNull { it.kind == MemoryNodeKind.PROMISE }
        assertNotNull(promise)
        assertTrue(
            graph2.edges.any {
                it.kind == MemoryEdgeKind.PROMISE && it.to == promise!!.id && it.from == "person:阿丙"
            },
        )
    }

    @Test
    fun `a holder written as a place is furniture, not a person`() {
        val items = listOf(
            AgentClient.MemoryFact("外套", "在衣架上身上", from = 1),
            AgentClient.MemoryFact("铜钥匙", "在书桌上身上", from = 2),
        )
        val graph = MemoryGraphs.build(
            state = state().copy(items = items),
            events = events(),
            entries = emptyList(),
            showEntries = false,
        )

        assertNull(graph.node("person:衣架上"))
        assertNull(graph.node("person:书桌上"))
        // The prop nobody holds rests on the scene, and its note keeps the spot for the sheet.
        assertTrue(graph.edges.any { it.from == "scene" && it.to == "item:外套" })
        assertFalse(graph.edges.any { it.to == "item:铜钥匙" && it.kind == MemoryEdgeKind.HOLD })
    }

    @Test
    fun `a place keeps the clause after a comma, and folds only brackets`() {
        val scenes = listOf(
            AgentClient.MemoryScene(
                from = 1, to = 3, location = "阿丙家的浴室，浴缸里", date = "", time = "",
                present = listOf("阿甲"), summary = "", event = "", quote = "",
            ),
            AgentClient.MemoryScene(
                from = 4, to = 6, location = "阿丙家的浴室（浴缸内外）", date = "", time = "",
                present = listOf("阿乙"), summary = "", event = "", quote = "",
            ),
        )
        val graph = MemoryGraphs.build(
            state = state().copy(location = ""),
            events = events(),
            entries = entries(),
            showEntries = false,
            scenes = scenes,
        )

        // Brackets are stripped as a qualifier; a comma clause is part of the name and stays.
        // The two forms therefore remain two nodes: collapsing them would also turn
        // a place and its floor into the place alone, losing the floor.
        assertEquals(
            setOf("阿丙家的浴室", "阿丙家的浴室，浴缸里"),
            graph.nodes.filter { it.kind == MemoryNodeKind.PLACE }.map { it.label }.toSet(),
        )
    }

    @Test
    fun `an edge carries the record that proves it`() {
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = false)

        val hold = graph.edges.first { it.kind == MemoryEdgeKind.HOLD }
        assertEquals("state:items", hold.source)
        assertEquals(10, hold.fromFloor)
        assertEquals("在阿乙身上", hold.evidence)

        val relation = graph.edges.first { it.kind == MemoryEdgeKind.RELATION }
        assertEquals("state:relations", relation.source)
        assertEquals(4, relation.fromFloor)
        assertEquals("徒弟", relation.evidence)

        val takePart = graph.edges.first { it.from == "person:阿甲" && it.to == "event:0" }
        assertEquals("event:who", takePart.source)
        assertEquals("阿甲把钥匙交给阿乙", takePart.evidence)
    }

    @Test
    fun `a crowd of one kind does not land on top of another node`() {
        // Twelve props held by the same person must not sit on one radius with a fixed step,
        // or the ring reads as a pile. The layout relaxes until no two footprints intersect.
        val many = (1..12).map { AgentClient.MemoryFact("物品$it", "在阿乙身上", from = it) }
        val graph = MemoryGraphs.build(
            state = state().copy(items = many),
            events = events(),
            entries = emptyList(),
            showEntries = true,
        )

        val points = graph.nodes.map { it.x to it.y }
        for (i in points.indices) {
            for (j in i + 1 until points.size) {
                val dx = points[i].first - points[j].first
                val dy = points[i].second - points[j].second
                val distance = kotlin.math.hypot(dx, dy)
                assertTrue(
                    "nodes $i and $j overlap (${"%.4f".format(distance)})",
                    distance > 0.02f,
                )
            }
        }
    }

    @Test
    fun `dust is spread around the rim instead of stacked on one spoke`() {
        val many = (1..30).map {
            AgentClient.MemoryEntry("e$it", "第 $it 条没有标签的记忆", emptyList(), "fact", it, it, 0)
        }
        val graph = MemoryGraphs.build(
            state = AgentClient.MemoryState(location = "走廊"),
            events = emptyList(),
            entries = many,
            showEntries = true,
        )

        val spots = graph.nodes.filter { it.kind == MemoryNodeKind.ENTRY }
            .map { (it.x * 1000).toInt() to (it.y * 1000).toInt() }
            .toSet()
        assertEquals(30, spots.size)
    }

    /** Two pairs who share their own rows, plus a scene every one of them hangs off. */
    private fun circles() = AgentClient.MemoryState(
        source = "units",
        location = "客栈",
        time = "傍晚",
        present = listOf("阿甲", "阿乙", "阿丙", "阿丁"),
        people = listOf(
            AgentClient.MemoryFact("阿甲", "跑船的", from = 3),
            AgentClient.MemoryFact("阿乙", "账房", from = 4),
            AgentClient.MemoryFact("阿丙", "铁匠", from = 5),
            AgentClient.MemoryFact("阿丁", "学徒", from = 6),
        ),
        items = listOf(
            AgentClient.MemoryFact("铜钥匙", "在阿乙身上", from = 7),
            AgentClient.MemoryFact("旧账本", "在阿丁身上", from = 8),
        ),
        promises = emptyList(),
        relations = emptyList(),
    )

    private fun circleEvents() = listOf(
        AgentClient.MemoryEvent(
            text = "阿甲把铜钥匙交给阿乙",
            whenText = "傍晚",
            who = listOf("阿甲", "阿乙"),
            cause = "要出远门",
            effect = "钥匙归阿乙",
        ),
        AgentClient.MemoryEvent(
            text = "阿甲与阿乙在码头结账",
            whenText = "夜里",
            who = listOf("阿甲", "阿乙"),
            cause = "",
            effect = "",
        ),
        AgentClient.MemoryEvent(
            text = "阿丙与阿丁清点旧账本",
            whenText = "午后",
            who = listOf("阿丙", "阿丁"),
            cause = "",
            effect = "",
        ),
    )

    @Test
    fun `a stored circle sits together on the first ring`() {
        val graph = MemoryGraphs.build(
            circles(), circleEvents(), emptyList(), showEntries = false,
            circles = mapOf("阿甲" to 0, "阿乙" to 0, "阿丙" to 1, "阿丁" to 1),
        )

        fun angle(id: String): Double {
            val node = graph.node(id)!!
            return Math.atan2((node.y - 0.5f).toDouble(), (node.x - 0.5f).toDouble())
        }

        fun apart(a: String, b: String): Double {
            val raw = Math.abs(angle(a) - angle(b))
            return Math.min(raw, 2 * Math.PI - raw)
        }

        val same = apart("person:阿甲", "person:阿乙")
        val stranger = apart("person:阿甲", "person:阿丙")
        assertTrue(
            "the pair that shares rows sits closer than a stranger: $same vs $stranger",
            same < stranger,
        )
    }

    @Test
    fun `two circles are separated by a gap wider than the spacing inside them`() {
        val graph = MemoryGraphs.build(
            circles(), circleEvents(), emptyList(), showEntries = false,
            circles = mapOf("阿甲" to 0, "阿乙" to 0, "阿丙" to 1, "阿丁" to 1),
        )
        val names = listOf("阿甲", "阿乙", "阿丙", "阿丁")
        val angles = names.map { name ->
            val node = graph.node("person:$name")!!
            Math.atan2((node.y - 0.5f).toDouble(), (node.x - 0.5f).toDouble())
        }.sorted()
        val steps = angles.indices.map { index ->
            val next = angles[(index + 1) % angles.size]
            val raw = if (next >= angles[index]) next - angles[index] else next + 2 * Math.PI - angles[index]
            raw
        }
        val tightest = steps.min()
        val widest = steps.max()
        assertTrue(
            "a group boundary must read as a gap: tightest $tightest, widest $widest",
            widest > tightest * 1.3,
        )
    }
}
