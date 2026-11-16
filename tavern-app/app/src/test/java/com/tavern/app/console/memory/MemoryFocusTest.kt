package com.tavern.app.console.memory

import com.tavern.app.console.AgentClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFocusTest {

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
    fun `the spread keeps what the anchor touches and names the line`() {
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = true)
        val focus = MemoryFocus.spread(graph, "person:阿乙", budget = 50)

        val hold = focus.reasons["item:铜钥匙"]
        assertEquals("持有", hold!!.viaLabel)
        assertEquals(1, hold.distance)
        assertTrue("the scene is one hop out", focus.reasons.containsKey("scene"))
        assertTrue("the turning point took part", focus.reasons.containsKey("event:0"))
        assertTrue("the dust entry hangs one weak hop out", focus.reasons.containsKey("entry:e1"))
    }

    @Test
    fun `the scene hub is never expanded through`() {
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = false)
        val focus = MemoryFocus.spread(graph, "person:阿乙", budget = 50)

        // The other cast member hangs on the scene only: reaching him would mean passing
        // through the hub.
        assertFalse("no relay through the scene", focus.reasons.containsKey("person:阿甲"))
        assertFalse("阿丁 sits past 阿甲, out of reach", focus.reasons.containsKey("person:阿丁"))
        assertFalse("the promise has no reachable maker", focus.reasons.containsKey("promise:0"))
    }

    @Test
    fun `the budget caps the spread and says so`() {
        val many = (1..12).map { AgentClient.MemoryFact("物品$it", "在阿乙身上", from = it) }
        val graph = MemoryGraphs.build(
            state = state().copy(items = many),
            events = events(),
            entries = emptyList(),
            showEntries = false,
        )
        val focus = MemoryFocus.spread(graph, "person:阿乙", budget = 4)

        assertTrue(focus.nodes.size <= 4)
        assertTrue(focus.capped)
        assertTrue("the anchor survives the cap", focus.nodes.any { it.id == "person:阿乙" })
    }

    @Test
    fun `several anchors grow one map together`() {
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = false)
        val focus = MemoryFocus.spread(
            graph,
            setOf("person:阿乙", "person:阿甲"),
            budget = 50,
        )

        assertTrue(focus.nodes.any { it.id == "person:阿甲" })
        assertTrue(focus.nodes.any { it.id == "person:阿乙" })
        // With both cast members anchored, the line between the two remaining names comes
        // into reach.
        assertTrue(focus.reasons.containsKey("person:阿丁"))
        assertFalse("anchors are not reasoned about themselves", focus.reasons.containsKey("person:阿甲"))
    }

    @Test
    fun `a floor anchors everything whose record covers it`() {
        val scenes = listOf(
            AgentClient.MemoryScene(
                from = 8, to = 12, location = "柴房", date = "", time = "",
                present = listOf("阿甲"), summary = "", event = "", quote = "",
            ),
        )
        val detail = AgentClient.MemoryDetail(
            chatId = "c", characterId = "ch", messageCount = 20, lastIndex = 19,
            pending = 0, restarted = false, lastError = "", failures = 0,
            updatedAt = 0, digest = "", entries = emptyList(), scenes = scenes,
        )
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = false, scenes = scenes)

        val anchors = MemoryFocus.anchorsForFloor(graph, detail, 10)
        assertTrue("the prop the floor carries", anchors.contains("item:铜钥匙"))
        assertTrue("the cast on stage that stretch", anchors.contains("person:阿甲"))
        assertTrue("the place of the stretch", anchors.contains("place:柴房"))

        // The spread from these anchors reaches the partner through the shared stretch.
        val focus = MemoryFocus.spread(graph, anchors, budget = 50)
        assertTrue(focus.nodes.any { it.id == "person:阿乙" })
    }

    @Test
    fun `an unknown anchor yields an empty spread, not a crash`() {
        val graph = MemoryGraphs.build(state(), events(), entries(), showEntries = false)
        val focus = MemoryFocus.spread(graph, "person:不存在", budget = 30)

        assertTrue(focus.nodes.isEmpty())
        assertFalse(focus.capped)
    }
}
