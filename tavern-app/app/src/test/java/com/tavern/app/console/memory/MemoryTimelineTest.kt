package com.tavern.app.console.memory

import com.tavern.app.console.AgentClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTimelineTest {

    private fun entry(id: String, from: Int, text: String = "第 $from 条") =
        AgentClient.MemoryEntry(id, text, emptyList(), "fact", from, from, 0)

    private fun scene(from: Int, to: Int, location: String = "柴房") =
        AgentClient.MemoryScene(from = from, to = to, location = location, date = "五月初九", time = "深夜")

    @Test
    fun `an entry lands under the scene whose floors contain it`() {
        val groups = MemoryTimeline.group(
            entries = listOf(entry("a", 3), entry("b", 12), entry("c", 25)),
            scenes = listOf(scene(0, 9), scene(10, 19)),
        )

        val second = groups.first { it.from == 10 }
        assertEquals(listOf("b"), second.entries.map { it.id })
        val third = groups.first { it.from == 20 }
        assertNull(third.scene)
        assertEquals(listOf("c"), third.entries.map { it.id })
    }

    @Test
    fun `groups read newest first and entries inside a group read in order`() {
        val groups = MemoryTimeline.group(
            entries = listOf(entry("late", 18), entry("early", 11)),
            scenes = listOf(scene(0, 9), scene(10, 19)),
        )
        assertEquals(10, groups.first().from)
        assertEquals(listOf("early", "late"), groups.first().entries.map { it.id })
    }

    @Test
    fun `a scene with a summary but no rows still appears`() {
        val groups = MemoryTimeline.group(
            entries = emptyList(),
            scenes = listOf(scene(0, 9), scene(10, 19)),
        )
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.scene != null })
    }

    @Test
    fun `without scene rows the entries still group by floor window`() {
        val groups = MemoryTimeline.group(
            entries = listOf(entry("a", 0), entry("b", 19), entry("c", 20), entry("d", 41)),
            scenes = emptyList(),
        )
        assertEquals(listOf(40, 20, 0), groups.map { it.from })
        assertEquals(listOf("a", "b"), groups.first { it.from == 0 }.entries.map { it.id })
        assertEquals(listOf("d"), groups.first { it.from == 40 }.entries.map { it.id })
    }

    @Test
    fun `a row with no floor is kept in its own group`() {
        val groups = MemoryTimeline.group(entries = listOf(entry("x", -1)), scenes = emptyList())
        assertEquals(1, groups.size)
        assertEquals(-1, groups.first().from)
        assertEquals(listOf("x"), groups.first().entries.map { it.id })
    }
}
