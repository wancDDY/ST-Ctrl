package com.tavern.app.console.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelFitTest {

    private fun box(index: Int, priority: Float, left: Float, top: Float, width: Float = 60f, height: Float = 14f) =
        LabelBox(index, priority, left, top, left + width, top + height)

    @Test
    fun `labels that do not touch are all kept`() {
        val kept = labelsThatFit(listOf(box(0, 10f, 0f, 0f), box(1, 10f, 200f, 0f)))
        assertEquals(listOf(0, 1), kept.sorted())
    }

    @Test
    fun `an overlapping label yields to the one with more weight`() {
        // The lower priority is listed first to prove order does not decide the winner.
        val kept = labelsThatFit(listOf(box(0, 1f, 0f, 0f), box(1, 9f, 10f, 4f)))
        assertEquals(listOf(1), kept)
    }

    @Test
    fun `a label blocked by a dropped one is still drawn`() {
        // 0 spans 0..60, 1 spans 40..100 (covers 0), 2 spans 70..130 (covers 1, clears 0).
        val kept = labelsThatFit(listOf(box(0, 9f, 0f, 0f), box(1, 5f, 40f, 0f), box(2, 1f, 70f, 0f)))
        assertTrue("0 keeps its place", 0 in kept)
        assertTrue("1 is covered by 0", 1 !in kept)
        assertTrue("2 is free once 1 is gone: $kept", 2 in kept)
    }

    @Test
    fun `equal weight keeps the earlier label, whatever the list order`() {
        val forward = labelsThatFit(listOf(box(0, 5f, 0f, 0f), box(1, 5f, 5f, 0f)))
        val backward = labelsThatFit(listOf(box(1, 5f, 5f, 0f), box(0, 5f, 0f, 0f)))
        assertEquals(listOf(0), forward)
        assertEquals(forward, backward)
    }

    @Test
    fun `an empty map asks for nothing`() {
        assertEquals(emptyList<Int>(), labelsThatFit(emptyList()))
    }
}
