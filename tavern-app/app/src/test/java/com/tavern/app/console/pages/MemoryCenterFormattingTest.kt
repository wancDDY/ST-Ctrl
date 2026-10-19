package com.tavern.app.console.pages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCenterFormattingTest {

    @Test
    fun legacyStateChangesAreSeparatedFromNarrative() {
        val formatted = memoryCenterReadableSummary(
            "阿戊抵达北门。State changes: 阿戊.location = 北门; 阿甲.status = 等待。",
        )

        assertTrue(formatted.contains("阿戊抵达北门"))
        assertFalse(formatted.contains("阿戊的地点"))
        assertFalse(formatted.contains("阿甲的状态"))
        assertFalse(formatted.contains("State changes"))
        assertFalse(formatted.contains(".location"))
        assertFalse(formatted.contains(".status"))
    }

    @Test
    fun summaryRangePrefixIsRemoved() {
        val formatted = memoryCenterReadableSummary(
            "[120-160] 阿戊抵达北门，并与阿甲会合。",
        )

        assertTrue(formatted.startsWith("阿戊抵达北门"))
    }

    @Test
    fun systemPreambleAndMarkdownAreRemoved() {
        val formatted = memoryCenterReadableSummary(
            "System：**姓名**：阿乙 **阶段**：秩序期 请根据以上内容合理安排出场、剧情、NPC生成等内容 阿戊推开北门。",
        )

        assertTrue(formatted.startsWith("阿戊推开北门"))
        assertFalse(formatted.contains("System"))
        assertFalse(formatted.contains("**"))
    }

    @Test
    fun structuredFactValuesArePresentedAsReadableText() {
        val formatted = memoryCenterReadableValue(
            """{"type":"crime","title":"城北抢劫案","desc":"警方已封锁道路。","unused":7}""",
        )

        assertTrue(formatted.contains("城北抢劫案"))
        assertTrue(formatted.contains("警方已封锁道路"))
        assertFalse(formatted.contains("unused"))
    }

    @Test
    fun storedEntryTextDoesNotReachTheScreenWithTheOldSeparator() {
        // The engine composes character, event and place entries with a middle dot between their
        // fields, and stored text keeps whatever it was written with, so the display layer is the
        // only place that can keep the reader from seeing two styles side by side.
        val formatted = memoryCenterReadableValue("身份：学生 · 外貌：长发 · 性格：冷淡")

        assertFalse(formatted.contains(" · "))
        assertTrue(formatted.contains("身份：学生，外貌：长发，性格：冷淡"))
    }
}
