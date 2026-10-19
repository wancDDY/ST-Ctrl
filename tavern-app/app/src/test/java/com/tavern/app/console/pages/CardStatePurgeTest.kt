package com.tavern.app.console.pages

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tavern only offers to import a card's world book and regex once, keyed by the avatar
 * file name in accountStorage. A card the app deleted has to lose those records, or a card
 * imported again never gets its world book — the case the app's delete has to reproduce.
 */
class CardStatePurgeTest {

    private val avatar = "《示例角色》v2.3.1.png"
    private val labels = setOf("示例角色", "《示例角色》v2.3.1", avatar)

    private fun settings(): JSONObject = JSONObject(
        """
        {
          "accountStorage": {
            "AlertWI_$avatar": "true",
            "AlertRegex_$avatar": "true",
            "AlertTasks_旁观角色.png": "true",
            "AlertWI_旁观角色.png": "true",
            "AlertRegex_openai_DeepSeek V4": "true",
            "SelectedNavTab": "1"
          },
          "extension_settings": {
            "character_allowed_regex": ["旁观角色.png", "$avatar"],
            "quickReplyV2": {
              "characterConfigs": { "$avatar": {"set": "示例角色"}, "旁观角色.png": {"set": "旁观角色"} }
            },
            "tavern_helper": {
              "script": {
                "popuped": { "characters": ["旁观角色.png", "$avatar"] },
                "enabled": { "characters": ["旁观角色.png", "$avatar"] }
              }
            }
          },
          "world_info_settings": {
            "world_info": {
              "charLore": [
                { "name": "《示例角色》v2.3.1", "extraBooks": ["示例角色开局创建"] },
                { "name": "旁观角色", "extraBooks": [] }
              ]
            }
          },
          "regex_scripts": [
            { "scriptName": "示例角色-变量", "findRegex": "a" },
            { "scriptName": "旁观角色-变量", "findRegex": "b" }
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun everyCardScopedRecordIsRemoved() {
        val json = settings()

        assertTrue(purgeCardState(json, labels, setOf("示例角色-变量")))

        val storage = json.getJSONObject("accountStorage")
        assertFalse(storage.has("AlertWI_$avatar"))
        assertFalse(storage.has("AlertRegex_$avatar"))
        // Another card's markers and the preset-keyed ones are not this card's.
        assertTrue(storage.has("AlertWI_旁观角色.png"))
        assertTrue(storage.has("AlertTasks_旁观角色.png"))
        assertTrue(storage.has("AlertRegex_openai_DeepSeek V4"))
        assertTrue(storage.has("SelectedNavTab"))

        val extensions = json.getJSONObject("extension_settings")
        assertEquals(1, extensions.getJSONArray("character_allowed_regex").length())
        assertEquals("旁观角色.png", extensions.getJSONArray("character_allowed_regex").getString(0))

        val script = extensions.getJSONObject("tavern_helper").getJSONObject("script")
        assertEquals(
            "旁观角色.png",
            script.getJSONObject("popuped").getJSONArray("characters").getString(0),
        )
        assertEquals(1, script.getJSONObject("popuped").getJSONArray("characters").length())
        assertEquals(1, script.getJSONObject("enabled").getJSONArray("characters").length())

        val configs = extensions.getJSONObject("quickReplyV2").getJSONObject("characterConfigs")
        assertFalse(configs.has(avatar))
        assertTrue(configs.has("旁观角色.png"))

        val lore = json.getJSONObject("world_info_settings").getJSONObject("world_info")
            .getJSONArray("charLore")
        assertEquals(1, lore.length())
        assertEquals("旁观角色", lore.getJSONObject(0).getString("name"))

        val regex = json.getJSONArray("regex_scripts")
        assertEquals(1, regex.length())
        assertEquals("旁观角色-变量", regex.getJSONObject(0).getString("scriptName"))
    }

    @Test
    fun theSecondPassChangesNothing() {
        val json = settings()
        assertTrue(purgeCardState(json, labels, setOf("示例角色-变量")))
        assertFalse(purgeCardState(json, labels, setOf("示例角色-变量")))
    }

    @Test
    fun anEmptyLabelSetKeepsUnrelatedCards() {
        val json = settings()
        assertFalse(purgeCardState(json, emptySet(), emptySet()))
        assertEquals(6, json.getJSONObject("accountStorage").length())
        assertEquals(2, json.getJSONArray("regex_scripts").length())
    }

    @Test
    fun arraysThatLoseNothingSurvive() {
        val json = JSONObject(
            """{"extension_settings":{"character_allowed_regex":["其他.png"]}}""",
        )
        assertFalse(purgeCardState(json, labels, emptySet()))
        val kept = json.getJSONObject("extension_settings").getJSONArray("character_allowed_regex")
        assertEquals(1, kept.length())
        assertEquals("其他.png", kept.getString(0))
        assertEquals(1, JSONArray(kept.toString()).length())
    }
}
