package com.tavern.app.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an unattended pass may start. One chance per launch leaves a chat forty floors behind
 * with the switch on and nothing dispatching, so the screen value is the only thing that holds
 * a pass back.
 */
class MemoryRunSchedulerTest {

    @Test
    fun theTavernPageHoldsThePassBackOnlyWhileItIsInFront() {
        // The reader is waiting on their own reply from the same provider; a consolidation call
        // would queue ahead of it.
        assertFalse(shouldDispatchMemoryNow("webview", appInForeground = true))
        // Put the app away and that reason is gone: this is when a session's backlog is worked
        // off, and holding it back here meant a reader who lives in the chat never got a pass.
        assertTrue(shouldDispatchMemoryNow("webview", appInForeground = false))
    }

    @Test
    fun everywhereElseLetsItRun() {
        assertTrue(shouldDispatchMemoryNow("console", appInForeground = true))
        assertTrue(shouldDispatchMemoryNow("resume", appInForeground = true))
        assertTrue(shouldDispatchMemoryNow("startup", appInForeground = false))
    }
}

class ScheduledChatTest {

    private fun chat(
        chatId: String,
        pending: Int,
        disabled: Boolean = false,
        characterId: String = "角色甲",
    ) = AgentClient.ChatMemory(
        chatId = chatId,
        characterId = characterId,
        messageCount = 40,
        lastIndex = 30,
        pending = pending,
        restarted = false,
        entries = 10,
        digestChars = 100,
        updatedAt = 0,
        lastError = "",
        failures = 0,
        busy = false,
        disabled = disabled,
    )

    private fun state(
        lastInjectedChat: String,
        lastInjectedAt: Long,
        chats: List<AgentClient.ChatMemory>,
        lastInjectedCharacter: String = "",
    ) = AgentClient.ChatMemoryState(
        enabled = true,
        modelTakeover = true,
        chats = chats,
        lastInjectedChat = lastInjectedChat,
        lastInjectedCharacter = lastInjectedCharacter,
        lastInjectedAt = lastInjectedAt,
    )

    private val now = 1_000_000_000L

    @Test
    fun aChatWithItsSwitchOffIsNotBehind() {
        // The backlog is deliberate: the reader said this chat is not to be read. Dispatching it
        // spends a call to be told there was nothing to do, and the row then reports a pass that
        // "finished" for a chat nobody asked about.
        val picked = scheduledChat(
            state("甲聊天.jsonl", now - 60_000L, listOf(chat("甲聊天", pending = 40, disabled = true))),
            threshold = 10,
            now = now,
        )
        assertNull(picked)
    }

    @Test
    fun theChatThatWasJustReadIsTheOneThatGetsRead() {
        val picked = scheduledChat(
            state(
                "甲聊天.jsonl", now - 60_000L,
                listOf(chat("乙聊天", pending = 40), chat("甲聊天", pending = 12)),
            ),
            threshold = 10,
            now = now,
        )
        assertEquals("甲聊天", picked?.chatId)
    }

    @Test
    fun aSharedChatNameIsNotAReasonToReadTheOtherCharacter() {
        // The tavern names a chat after the date it was opened. Two characters read on the same
        // day therefore have chats of the same name, and only the character says which is open.
        val picked = scheduledChat(
            state(
                "聊天一.jsonl", now - 60_000L,
                listOf(
                    chat("聊天一", pending = 40, characterId = "角色乙"),
                    chat("聊天一", pending = 3, characterId = "角色甲"),
                ),
                lastInjectedCharacter = "角色甲",
            ),
            threshold = 10,
            now = now,
        )
        assertNull(picked)
    }

    @Test
    fun aBacklogInAChatNobodyIsReadingIsNotAReasonToSpend() {
        val picked = scheduledChat(
            state("甲聊天.jsonl", now - MEMORY_ACTIVE_WINDOW_MS - 1, listOf(chat("甲聊天", pending = 40))),
            threshold = 10,
            now = now,
        )
        assertNull(picked)
    }

    @Test
    fun aShortBacklogOrASwitchAtZeroWaits() {
        assertNull(
            scheduledChat(
                state("甲聊天.jsonl", now, listOf(chat("甲聊天", pending = 3))),
                threshold = 10,
                now = now,
            ),
        )
        // Zero is "never" — the reader keeps it manual.
        assertNull(
            scheduledChat(
                state("甲聊天.jsonl", now, listOf(chat("甲聊天", pending = 40))),
                threshold = 0,
                now = now,
            ),
        )
    }
}
