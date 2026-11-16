package com.tavern.app.console.pages

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deploy confirmation answers two questions: what changed, and whether the
 * change is one a deploy cannot undo. Both run on card JSON alone, so they are
 * tested here without files.
 */
class CardDeployDiffTest {
    private fun card(
        description: String = "旧描述",
        firstMes: String = "开场。",
        greetings: List<String> = emptyList(),
        bookEntries: Int = 0,
        scripts: List<String> = emptyList(),
    ): JSONObject {
        val data = JSONObject()
            .put("description", description)
            .put("first_mes", firstMes)
            .put("alternate_greetings", JSONArray(greetings))
        if (bookEntries > 0) {
            val entries = JSONArray()
            repeat(bookEntries) { index -> entries.put(JSONObject().put("id", index)) }
            data.put("character_book", JSONObject().put("entries", entries))
        }
        if (scripts.isNotEmpty()) {
            val list = JSONArray()
            scripts.forEach { name -> list.put(JSONObject().put("name", name).put("type", "script")) }
            data.put(
                "extensions",
                JSONObject().put("tavern_helper", JSONObject().put("scripts", list)),
            )
        }
        return JSONObject().put("spec", "chara_card_v2").put("data", data)
    }

    @Test
    fun `a re-deploy of the same body has nothing to report`() {
        val body = card()
        assertTrue(compareCardBodies(body, body).isEmpty())
    }

    @Test
    fun `text edits are reported by field`() {
        val changes = compareCardBodies(card(description = "旧描述"), card(description = "新描述"))
        assertEquals(1, changes.size)
        assertEquals("介绍", changes[0].label)
        assertEquals("有改动", changes[0].detail)
    }

    @Test
    fun `a filled or emptied field says so instead of saying changed`() {
        val filled = compareCardBodies(card(description = ""), card(description = "新写的介绍"))
        assertEquals("新填了内容", filled[0].detail)
        val cleared = compareCardBodies(card(description = "写过的"), card(description = ""))
        assertEquals("清空了", cleared[0].detail)
    }

    @Test
    fun `collection sizes are reported as before and after`() {
        val changes = compareCardBodies(card(bookEntries = 12), card(bookEntries = 13))
        val book = changes.first { it.label == "世界书" }
        assertEquals("12 条 → 13 条", book.detail)
        assertFalse(book.structural)
    }

    @Test
    fun `a different script set is a structural change`() {
        val before = card(scripts = listOf("状态栏"))
        val after = card(scripts = listOf("状态栏", "变量初始化"))
        val changes = compareCardBodies(before, after)

        val scripts = changes.first { it.label == "脚本" }
        assertEquals("1 个 → 2 个", scripts.detail)
        assertTrue(scripts.structural)
        assertTrue(hasVariableRisk(before, after))
    }

    @Test
    fun `an opening that starts carrying state is a structural change`() {
        val plain = card(firstMes = "雨下了一夜。")
        val withState = card(firstMes = "雨下了一夜。<StatusPlaceHolderImpl/>")
        assertFalse(hasVariableRisk(plain, plain))
        assertTrue(hasVariableRisk(plain, withState))
    }

    @Test
    fun `nothing is claimed about a card that was never deployed`() {
        assertTrue(compareCardBodies(null, card()).isEmpty())
        assertFalse(hasVariableRisk(null, card()))
    }
}
