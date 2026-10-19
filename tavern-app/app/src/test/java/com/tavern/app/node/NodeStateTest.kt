package com.tavern.app.node

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeStateTest {

    @Test
    fun `initial state is IDLE`() = runTest {
        NodeState.setIdle()
        assertEquals(NodeState.State.IDLE, NodeState.state.first())
    }

    @Test
    fun `STARTING to RUNNING transition`() = runTest {
        NodeState.setStarting()
        assertEquals(NodeState.State.STARTING, NodeState.state.first())

        NodeState.setRunning(8000)
        assertEquals(NodeState.State.RUNNING, NodeState.state.first())
        assertEquals(8000, NodeState.port.first())
    }

    @Test
    fun `ERROR state carries message`() = runTest {
        val msg = "EACCES: permission denied"
        NodeState.setError(msg)
        assertEquals(NodeState.State.ERROR, NodeState.state.first())
        assertEquals(msg, NodeState.lastError.first())
    }

    @Test
    fun `setStarting clears error`() = runTest {
        NodeState.setError("previous error")
        NodeState.setStarting()
        assertNull(NodeState.lastError.first())
    }

    /**
     * The status page reads this flag to keep offering "starting" while the state flow
     * was overwritten by a stop that finished after the boot began.
     */
    @Test
    fun `start attempts are visible for their whole duration`() = runTest {
        NodeState.endStart()
        assertFalse(NodeState.startInFlight.first())

        NodeState.beginStart()
        assertTrue(NodeState.startInFlight.first())

        NodeState.endStart()
        assertFalse(NodeState.startInFlight.first())
    }
}
