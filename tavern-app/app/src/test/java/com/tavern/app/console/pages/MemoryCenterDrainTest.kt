package com.tavern.app.console.pages

import com.tavern.app.webview.MemoryDrainState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCenterDrainTest {

    @Test
    fun aFinishedRunReportsHowMuchItStored() {
        val message = memoryDrainMessage(
            MemoryDrainState(ok = true, done = true, batches = 3, processed = 72),
        )

        assertTrue(message.contains("72"))
    }

    @Test
    fun aRunWithNothingLeftToDoSaysSo() {
        val message = memoryDrainMessage(
            MemoryDrainState(ok = true, done = true, batches = 0, processed = 0),
        )

        assertEquals("没有新的聊天需要整理", message)
    }

    // The page reports a reason code rather than a sentence, so an unmapped one would surface as
    // the raw fallback. This pins every code the hook can publish to a distinct, readable line.
    @Test
    fun everyReasonThePageCanReportHasItsOwnMessage() {
        val reasons = listOf(
            "no-page",
            "core-too-old",
            "page-reloaded",
            "timeout",
            "empty-generation",
            "no-chat",
            "prepare-failed",
            "commit-failed",
        )
        val messages = reasons.map { reason ->
            memoryDrainMessage(
                MemoryDrainState(
                    ok = false,
                    done = false,
                    batches = 0,
                    processed = 0,
                    reason = reason,
                ),
            )
        }

        messages.forEach { assertFalse(it.contains("整理失败：")) }
        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.none { it.isBlank() })
    }
}
