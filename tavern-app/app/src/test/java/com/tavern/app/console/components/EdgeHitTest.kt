package com.tavern.app.console.components

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeHitTest {

    @Test
    fun `the distance is zero on the segment and grows off it`() {
        assertEquals(0f, distanceToSegment(5f, 5f, 0f, 0f, 10f, 10f), 0.001f)
        assertEquals(5f, distanceToSegment(5f, 5f, 0f, 0f, 0f, 10f), 0.001f)
    }

    @Test
    fun `past the endpoints the distance is to the endpoint, not the infinite line`() {
        assertEquals(5f, distanceToSegment(15f, 10f, 0f, 0f, 10f, 10f), 0.001f)
        assertEquals(10f, distanceToSegment(-10f, 0f, 0f, 0f, 0f, 10f), 0.001f)
    }

    @Test
    fun `a degenerate segment measures to its single point`() {
        assertEquals(5f, distanceToSegment(3f, 4f, 0f, 0f, 0f, 0f), 0.001f)
    }
}
