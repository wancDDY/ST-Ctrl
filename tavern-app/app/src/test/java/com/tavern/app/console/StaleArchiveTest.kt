package com.tavern.app.console

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which archives the page offers to rebuild.
 *
 * A pass appends: it reads the floors the archive has never seen and leaves the rows already
 * there alone, so an archive written under an older prompt keeps that prompt's judgement for as
 * long as the chat exists. The number that decides is the one written by the last pass, against
 * the one the running build writes — and the two cases that look similar but are not: an empty
 * archive (nothing to be out of date) and an archive from before the field existed (reads zero,
 * and does need the rebuild).
 */
class StaleArchiveTest {

    private fun chat(entries: Int, version: Int) = AgentClient.ChatMemory(
        chatId = "聊天",
        characterId = "角色",
        messageCount = 100,
        lastIndex = 99,
        pending = 0,
        restarted = false,
        entries = entries,
        promptVersion = version,
        digestChars = 40,
        updatedAt = 0,
        lastError = "",
        failures = 0,
        busy = false,
    )

    @Test
    fun `an archive from an older prompt is offered`() {
        assertTrue(chat(entries = 30, version = 9).staleAgainst(10))
    }

    @Test
    fun `an archive written by this build is not`() {
        assertFalse(chat(entries = 30, version = 10).staleAgainst(10))
    }

    @Test
    fun `an archive from a newer build is not offered a downgrade`() {
        // Possible after installing an older APK over a newer one. Rebuilding would replace rows
        // written to a standard this build does not know with its own.
        assertFalse(chat(entries = 30, version = 11).staleAgainst(10))
    }

    @Test
    fun `an empty archive has nothing to be out of date`() {
        // The next pass writes it under the current prompt anyway; offering a rebuild here would
        // be asking the reader to pay for work that is already the plan.
        assertFalse(chat(entries = 0, version = 0).staleAgainst(10))
    }

    @Test
    fun `an archive from before the version was recorded asks for a rebuild`() {
        assertTrue(chat(entries = 12, version = 0).staleAgainst(10))
    }
}
