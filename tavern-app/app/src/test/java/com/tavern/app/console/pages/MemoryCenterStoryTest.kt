package com.tavern.app.console.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCenterStoryTest {

    private fun phase(start: Int, end: Int, text: String = "第 $start-$end 条") =
        MemoryCenterSummary(level = "reflection-window", startSeq = start, endSeq = end, text = text)

    @Test
    fun shortTrailingPhasesAreShownAsOne() {
        // A save-heavy stretch leaves two-message phases behind; four of them used to fill the
        // story page with one-line entries.
        val merged = memoryCenterMergeShortPhases(
            listOf(phase(0, 39), phase(40, 41), phase(42, 43), phase(44, 45)),
        )

        assertEquals(2, merged.size)
        assertEquals(0 to 39, merged[0].startSeq to merged[0].endSeq)
        assertEquals(40 to 45, merged[1].startSeq to merged[1].endSeq)
        assertTrue(merged[1].text.contains("第 40-41 条"))
        assertTrue(merged[1].text.contains("第 44-45 条"))
    }

    @Test
    fun fullPhasesAreLeftAlone() {
        val merged = memoryCenterMergeShortPhases(
            listOf(phase(0, 39), phase(40, 79), phase(80, 119)),
        )

        assertEquals(3, merged.size)
        assertEquals(listOf(0 to 39, 40 to 79, 80 to 119), merged.map { it.startSeq to it.endSeq })
    }

    @Test
    fun aShortPhaseInTheMiddleAbsorbsTheNextOne() {
        val merged = memoryCenterMergeShortPhases(
            listOf(phase(0, 39), phase(40, 41), phase(42, 79), phase(80, 119)),
        )

        assertEquals(3, merged.size)
        assertEquals(40 to 79, merged[1].startSeq to merged[1].endSeq)
        assertEquals(80 to 119, merged[2].startSeq to merged[2].endSeq)
    }

    @Test
    fun blankTextDoesNotLeaveAnEmptySeparator() {
        val merged = memoryCenterMergeShortPhases(
            listOf(phase(0, 1, text = ""), phase(2, 3, text = "第二段")),
        )

        assertEquals(1, merged.size)
        assertEquals("第二段", merged[0].text.trim())
    }

    @Test
    fun relationFallbackKeepsRelationShapedFactsOnly() {
        val facts = listOf(
            MemoryCenterFact("周煜文", "好感度", "86"),
            MemoryCenterFact("周煜文", "当前所在地", "北门"),
            MemoryCenterFact("已收服母畜", "绫濑遥.母畜值", "3"),
            MemoryCenterFact("陈若曦", "归属", "绫濑遥"),
            // The same bond read twice in one batch must not be listed twice.
            MemoryCenterFact("周煜文", "好感度", "86"),
        )

        val fallback = memoryCenterRelationFallbackFacts(facts)

        assertEquals(listOf("好感度", "归属"), fallback.map { it.predicate })
    }

    @Test
    fun relationFallbackIsCapped() {
        val facts = (0 until 30).map { MemoryCenterFact("角色$it", "好感度", "$it") }

        assertEquals(20, memoryCenterRelationFallbackFacts(facts).size)
    }
}
