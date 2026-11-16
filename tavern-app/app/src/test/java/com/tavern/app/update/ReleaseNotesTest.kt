package com.tavern.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    // Bodies are assembled from lines so that no source line starts with a heading mark,
    // which the comment audit would read as a comment.
    private fun body(vararg lines: String) = lines.joinToString("\n")

    private val notes = body(
        "更正许可标注为 AGPL-3.0",
        "",
        "### 角色记忆",
        "整理不再停在第一批。",
        "- 收录旧聊天会继续处理已排队的批次。",
        "- 记忆中心的进度按批显示。",
        "",
        "### 界面",
        "控件行为与其他页面一致。",
        "- 表格行点击后立即展开。",
        "",
        "### 升级步骤",
        "- 在旧版创建备份。",
        "- 安装新版后还原。",
    )

    @Test
    fun sectionsKeepTitleSummaryAndItems() {
        val parsed = ReleaseNotes.parse(notes)!!
        assertEquals(2, parsed.sections.size)
        assertEquals("角色记忆", parsed.sections[0].title)
        assertEquals("整理不再停在第一批。", parsed.sections[0].summary)
        assertEquals(
            listOf("收录旧聊天会继续处理已排队的批次。", "记忆中心的进度按批显示。"),
            parsed.sections[0].items,
        )
    }

    @Test
    fun textBeforeTheFirstSectionStaysAsIntro() {
        val parsed = ReleaseNotes.parse(notes)!!
        assertEquals("更正许可标注为 AGPL-3.0", parsed.intro)
    }

    @Test
    fun migrationSectionBecomesSteps() {
        val parsed = ReleaseNotes.parse(notes)!!
        assertEquals("升级步骤", parsed.migrationTitle)
        assertEquals(listOf("在旧版创建备份。", "安装新版后还原。"), parsed.migrationSteps)
        assertTrue(parsed.sections.none { it.title == "升级步骤" })
    }

    @Test
    fun bodyWithoutSectionsFallsBackToPlainText() {
        assertNull(ReleaseNotes.parse("只是一段说明，没有板块标题。"))
        assertNull(ReleaseNotes.parse("   "))
    }

    @Test
    fun olderReleaseSectionIsNotPartOfThisRelease() {
        val parsed = ReleaseNotes.parse(
            body(
                "### 当前版本",
                "- 这一条属于本版。",
                "",
                "## v1.0.2",
                "- 这一条属于上一版。",
            ),
        )!!
        assertEquals(1, parsed.sections.size)
        assertEquals(listOf("这一条属于本版。"), parsed.sections[0].items)
    }

    @Test
    fun noticeBelowTheRuleIsNotGluedToTheLastStep() {
        val parsed = ReleaseNotes.parse(
            body(
                "### 升级步骤",
                "- 安装新版后还原。",
                "",
                "---",
                "许可与修改标注：整体按 GNU AGPL-3.0 发布。",
            ),
        )!!
        assertEquals(listOf("安装新版后还原。"), parsed.migrationSteps)
        assertEquals("许可与修改标注：整体按 GNU AGPL-3.0 发布。", parsed.footer)
    }

    @Test
    fun wrappedLineContinuesTheItemItFollows() {
        val parsed = ReleaseNotes.parse(
            body(
                "### 板块",
                "- 这一条很长，",
                "  折成了两行。",
            ),
        )!!
        assertEquals(listOf("这一条很长， 折成了两行。"), parsed.sections[0].items)
    }
}
