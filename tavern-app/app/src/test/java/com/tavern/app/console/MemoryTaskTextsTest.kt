package com.tavern.app.console

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two lines a memory pass shows: one while it runs, one when it dies. Both were wrong in ways
 * that only showed up on a device — a batch counter stuck at zero for the whole first batch, and a
 * generic failure when the core service had been stopped on purpose — so they are pinned here.
 */
class MemoryTaskTextsTest {

    @Test
    fun theFirstBatchSaysWhatItIsDoingInsteadOfCountingFromZero() {
        assertEquals("正在整理第 1 批…", memoryTaskProgressText(0, 0))
        assertEquals("3 批，72 条", memoryTaskProgressText(3, 72))
    }

    @Test
    fun aStoppedServiceIsNamedAsTheReasonThePassEnded() {
        val text = memoryTaskFailureText(true, "Failed to connect to /127.0.0.1:8000")
        // The underlying error is not a fault in the pass, so it must not be the headline.
        assertEquals(true, text.contains("核心服务已停止"))
        assertEquals(true, text.contains("已写入的记忆保留"))
        assertEquals(false, text.contains("127.0.0.1"))
    }

    @Test
    fun anyOtherFailureKeepsItsOwnMessage() {
        assertEquals("连接超时", memoryTaskFailureText(false, "连接超时"))
        assertEquals("整理失败", memoryTaskFailureText(false, null))
    }

    @Test
    fun aRunningMemoryPassIsWhatAsksBeforeABackup() {
        assertEquals(true, memoryTaskBlocksBackup(taskRunning = true, scanRunning = false))
        assertEquals(true, memoryTaskBlocksBackup(taskRunning = false, scanRunning = true))
        assertEquals(false, memoryTaskBlocksBackup(taskRunning = false, scanRunning = false))
    }
}
