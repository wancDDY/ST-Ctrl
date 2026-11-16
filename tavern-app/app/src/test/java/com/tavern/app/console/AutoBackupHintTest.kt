package com.tavern.app.console

import com.tavern.app.console.pages.autoBackupHint
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoBackupHintTest {
    @Test
    fun `remind-only wording never promises an automatic copy`() {
        assertEquals("首次启动应用时提醒", autoBackupHint(true, null))
        assertEquals("下次启动应用时提醒", autoBackupHint(true, 0))
        assertEquals("约 3 天后提醒备份", autoBackupHint(true, 3))
    }

    @Test
    fun `automatic wording keeps the existing schedule copy`() {
        assertEquals("首次启动应用时备份", autoBackupHint(false, null))
        assertEquals("下次启动应用时备份", autoBackupHint(false, 0))
        assertEquals("约 3 天后自动备份", autoBackupHint(false, 3))
    }
}
