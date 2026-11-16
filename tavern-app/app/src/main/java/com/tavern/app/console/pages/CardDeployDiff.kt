package com.tavern.app.console.pages

import org.json.JSONArray
import org.json.JSONObject

/**
 * One line of the deploy confirmation: what changed between the version already
 * in the core and the one about to replace it.
 *
 * [structural] marks the changes that no deploy can undo — a card's variable
 * scripts and the state block in its opening message shape what each chat stores,
 * so a chat written against the old shape may not read the new one.
 */
internal data class CardChange(
    val label: String,
    val detail: String,
    val structural: Boolean = false,
)

private val TEXT_FIELDS = listOf(
    "description" to "介绍",
    "personality" to "性格",
    "scenario" to "场景",
    "first_mes" to "开场白",
    "mes_example" to "示例对话",
    "system_prompt" to "系统提示",
    "post_history_instructions" to "历史后指令",
)

/** The card's payload; v2 cards keep everything under data, v1 cards at the top. */
private fun bodyOf(json: JSONObject): JSONObject = json.optJSONObject("data") ?: json

private fun entriesOf(json: JSONObject): JSONArray? =
    bodyOf(json).optJSONObject("character_book")?.optJSONArray("entries")

private fun regexOf(json: JSONObject): JSONArray? =
    bodyOf(json).optJSONObject("extensions")?.optJSONArray("regex_scripts")

private fun helperArrayOf(json: JSONObject): JSONArray? =
    bodyOf(json).optJSONObject("extensions")
        ?.optJSONObject("tavern_helper")
        ?.optJSONArray("scripts")

/**
 * Names of the TavernHelper scripts a card ships, folders flattened. These carry
 * the variable and status-bar logic most cards rely on, so their names are the
 * closest thing to a signature of the card's data model that a card file offers.
 */
private fun helperScriptNames(json: JSONObject): List<String> {
    val names = mutableListOf<String>()
    fun walk(array: JSONArray?) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val node = array.optJSONObject(index) ?: continue
            val type = node.optString("type", "script")
            val name = node.optString("name", "")
            if (name.isNotBlank() && type != "folder") names.add(name)
            walk(node.optJSONArray("scripts"))
        }
    }
    walk(helperArrayOf(json))
    return names.sorted()
}

/**
 * Markers of an opening message that initialises stored state rather than just
 * telling a story. A card that gains or loses one of these starts every chat with
 * a differently shaped state, which is the case old transcripts cannot follow.
 */
private fun stateBlockSignature(json: JSONObject): String {
    val marker = Regex("""StatusPlaceHolderImpl|stat_data|<UpdateVariable|<Variable""")
    val data = bodyOf(json)
    val openings = mutableListOf(data.optString("first_mes", ""))
    data.optJSONArray("alternate_greetings")?.let { array ->
        for (index in 0 until array.length()) openings.add(array.optString(index, ""))
    }
    return openings.joinToString("\u0000") { if (marker.containsMatchIn(it)) "1" else "0" }
}

/** True when the two bodies store their per-chat state differently. */
internal fun hasVariableRisk(before: JSONObject?, after: JSONObject): Boolean {
    if (before == null) return false
    if (helperScriptNames(before) != helperScriptNames(after)) return true
    return stateBlockSignature(before) != stateBlockSignature(after)
}

/** Before-and-after counts when the size moved, otherwise whether the contents moved. */
private fun listChange(
    label: String,
    unit: String,
    before: JSONArray?,
    after: JSONArray?,
): CardChange? {
    val oldCount = before?.length() ?: 0
    val newCount = after?.length() ?: 0
    if (oldCount != newCount) return CardChange(label, "$oldCount $unit → $newCount $unit")
    val same = (before?.toString() ?: "") == (after?.toString() ?: "")
    return if (same) null else CardChange(label, "内容有改动")
}

/**
 * What changed between the version in the core and the one replacing it.
 *
 * An empty list means the two bodies say the same thing, which is the honest
 * answer for a re-deploy and deserves its own sentence rather than a blank list.
 */
internal fun compareCardBodies(before: JSONObject?, after: JSONObject): List<CardChange> {
    if (before == null) return emptyList()
    val old = bodyOf(before)
    val new = bodyOf(after)
    val changes = mutableListOf<CardChange>()

    for ((key, label) in TEXT_FIELDS) {
        val was = old.optString(key, "").trim()
        val now = new.optString(key, "").trim()
        if (was == now) continue
        changes += CardChange(
            label,
            when {
                was.isEmpty() -> "新填了内容"
                now.isEmpty() -> "清空了"
                else -> "有改动"
            },
        )
    }

    listChange("备用开场白", "条", old.optJSONArray("alternate_greetings"), new.optJSONArray("alternate_greetings"))
        ?.let { changes += it }
    listChange("世界书", "条", entriesOf(before), entriesOf(after))?.let { changes += it }
    listChange("正则", "条", regexOf(before), regexOf(after))?.let { changes += it }

    val oldScripts = helperScriptNames(before)
    val newScripts = helperScriptNames(after)
    if (oldScripts != newScripts) {
        changes += CardChange(
            "脚本",
            if (oldScripts.size != newScripts.size) "${oldScripts.size} 个 → ${newScripts.size} 个" else "换成了别的脚本",
            structural = true,
        )
    }

    return changes
}
