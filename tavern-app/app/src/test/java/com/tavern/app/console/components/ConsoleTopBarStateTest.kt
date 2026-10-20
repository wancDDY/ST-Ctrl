package com.tavern.app.console.components

import com.tavern.app.console.ConsoleTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleTopBarStateTest {
    @Test
    fun activeTaskOutranksVersionNotice() {
        val display = resolveTaskIslandDisplay(
            tasks = listOf(ConsoleTask(ConsoleTask.RESTORE, "正在还原 3/10")),
            doneText = null,
            versionNoticeText = "v2.0.0 更新日志"
        )

        assertEquals("还原 30%", display.text)
        assertTrue(display.running)
        assertFalse(display.info)
    }

    @Test
    fun oneTaskIsNamedSoTheSlotIdentifiesIt() {
        val display = resolveTaskIslandDisplay(
            tasks = listOf(ConsoleTask(ConsoleTask.MEMORY, "整理记忆 甲", "3 批，12 条")),
            doneText = null,
            versionNoticeText = null
        )

        assertEquals("整理记忆 甲", display.text)
        assertTrue(display.running)
        assertFalse(display.info)
    }

    @Test
    fun severalTasksAreCountedBecauseOneSlotCannotNameThemAll() {
        val display = resolveTaskIslandDisplay(
            tasks = listOf(
                ConsoleTask(ConsoleTask.MEMORY, "整理记忆 甲", "3 批，12 条"),
                ConsoleTask(ConsoleTask.EXTENSION_INSTALL, "正在安装 插件", "下载中")
            ),
            doneText = null,
            versionNoticeText = null
        )

        assertEquals("2 个任务进行中", display.text)
        assertTrue(display.running)
        assertFalse(display.info)
    }

    @Test
    fun completionNoticeOutranksVersionNotice() {
        val display = resolveTaskIslandDisplay(
            tasks = emptyList(),
            doneText = "还原完成",
            versionNoticeText = "v2.0.0 更新日志"
        )

        assertEquals("还原完成", display.text)
        assertFalse(display.running)
        assertFalse(display.info)
    }

    @Test
    fun coreUpdateUsesActionableRunningText() {
        val display = resolveTaskIslandDisplay(
            tasks = listOf(ConsoleTask(ConsoleTask.CORE_UPDATE, "正在更新核心 v1.19.0，下载核心")),
            doneText = null,
            versionNoticeText = null
        )

        assertEquals("更新核心 v1.19.0，下载核心", display.text)
        assertTrue(display.running)
        assertFalse(display.info)
    }

    @Test
    fun versionNoticeIsInformationalWhenNoTaskIsActive() {
        val display = resolveTaskIslandDisplay(
            tasks = emptyList(),
            doneText = null,
            versionNoticeText = "v2.0.0 更新日志"
        )

        assertEquals("v2.0.0 更新日志", display.text)
        assertFalse(display.running)
        assertTrue(display.info)
    }

    @Test
    fun pendingNoticeNamesBothVersionsWhenBothAreNew() {
        val text = pendingNoticeText(
            com.tavern.app.update.PeriodicUpdateChecker.PendingUpdates(
                appVersion = "2.0.1",
                coreVersion = "1.20.0"
            )
        )

        assertEquals("v2.0.1，核心 1.20.0", text)
    }

    @Test
    fun pendingNoticeStaysSilentWhenNothingIsNewer() {
        val text = pendingNoticeText(
            com.tavern.app.update.PeriodicUpdateChecker.PendingUpdates(
                appVersion = null,
                coreVersion = null
            )
        )

        assertEquals(null, text)
    }

    @Test
    fun pendingNoticeNamesTheOnlyNewerSide() {
        assertEquals(
            "新版本 v2.0.1",
            pendingNoticeText(
                com.tavern.app.update.PeriodicUpdateChecker.PendingUpdates("2.0.1", null)
            )
        )
        assertEquals(
            "核心 1.20.0",
            pendingNoticeText(
                com.tavern.app.update.PeriodicUpdateChecker.PendingUpdates(null, "1.20.0")
            )
        )
    }
}
