package com.tavern.app.console.pages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCenterDiagnosticsTest {

    private fun linesOf(json: String): List<MemoryCenterDiagnosticsLine> {
        return memoryCenterDiagnosticsLines(JSONObject(json))
    }

    private fun lineStartingWith(
        lines: List<MemoryCenterDiagnosticsLine>,
        prefix: String,
    ): String? {
        return lines.firstOrNull { it.text.startsWith(prefix) }?.text
    }

    @Test
    fun fullReportIsRenderedWithoutWarnings() {
        val lines = linesOf(
            """
            {
              "sampling": { "temperature": 1.0, "repetitionPenalty": 1.1, "frequencyPenalty": 0,
                            "presencePenalty": 0, "conflict": false, "source": "last-generate-request" },
              "memoryFeatures": [ { "id": "built-in", "enabled": true },
                                  { "id": "summarize", "enabled": false },
                                  { "id": "chat-vectorization", "enabled": true } ],
              "retrieval": { "scoreThreshold": 0.45, "budgetChars": 1600, "lastInjectedLines": 7,
                             "lastDroppedBelowThreshold": 3 },
              "warnings": []
            }
            """.trimIndent(),
        )

        assertEquals(
            "采样参数：温度 1.0，重复惩罚 1.1，频率惩罚 0，存在惩罚 0",
            lineStartingWith(lines, "采样参数："),
        )
        assertEquals(
            "记忆功能：角色记忆 已开启，总结扩展 未开启，聊天向量化 已开启",
            lineStartingWith(lines, "记忆功能："),
        )
        assertEquals(
            "检索：分数门限 0.45，上次注入条数 7，上次低于门限丢弃条数 3",
            lineStartingWith(lines, "检索："),
        )
        assertFalse(lines.any { it.isWarning })
        assertEquals(3, lines.size)
    }

    @Test
    fun warningIsRenderedAsAWarningLine() {
        val lines = linesOf(
            """
            {
              "sampling": { "temperature": 1.0, "source": "last-generate-request" },
              "warnings": [ { "code": "penalty-conflict", "text": "重复惩罚与频率惩罚同时开启，可能互相抵消。" } ]
            }
            """.trimIndent(),
        )

        val warnings = lines.filter { it.isWarning }
        assertEquals(1, warnings.size)
        assertEquals("重复惩罚与频率惩罚同时开启，可能互相抵消。", warnings.first().text)
        assertTrue(lines.size > warnings.size)
    }

    @Test
    fun missingAndNullFieldsAreOmittedInsteadOfPrinted() {
        val empty = linesOf("{}")
        val allNull = linesOf(
            """{ "sampling": null, "memoryFeatures": null, "retrieval": null, "warnings": null }""",
        )
        val blankValues = linesOf(
            """
            {
              "sampling": { "temperature": null, "repetitionPenalty": "", "frequencyPenalty": "热",
                            "presencePenalty": 0, "source": null },
              "memoryFeatures": [ { "id": "built-in", "enabled": null }, { "id": null, "enabled": true } ],
              "retrieval": { "scoreThreshold": null, "budgetChars": "很多", "lastInjectedLines": 7 }
            }
            """.trimIndent(),
        )

        for (lines in listOf(empty, allNull, blankValues)) {
            assertTrue(lines.isNotEmpty())
            assertFalse(lines.any { it.text.contains("null") })
            assertFalse(lines.any { it.text.isBlank() })
        }

        assertEquals("采样参数：尚未取到实际请求", empty.first().text)
        assertEquals(
            "记忆功能：角色记忆 未知，总结扩展 未知，聊天向量化 未知",
            lineStartingWith(empty, "记忆功能："),
        )
        assertEquals(
            "记忆功能：角色记忆 未知，总结扩展 未知，聊天向量化 未知",
            lineStartingWith(allNull, "记忆功能："),
        )

        assertEquals(
            "采样参数：存在惩罚 0（尚未取到实际请求）",
            lineStartingWith(blankValues, "采样参数："),
        )
        assertEquals("检索：上次注入条数 7", lineStartingWith(blankValues, "检索："))
    }

    @Test
    fun unknownSourceSaysNoRequestWasCaptured() {
        val unknown = linesOf(
            """{ "sampling": { "temperature": 1.0, "source": "unknown" } }""",
        )
        assertEquals(
            "采样参数：温度 1.0（尚未取到实际请求）",
            lineStartingWith(unknown, "采样参数："),
        )

        val captured = linesOf(
            """{ "sampling": { "temperature": 1.0, "source": "last-generate-request" } }""",
        )
        assertEquals("采样参数：温度 1.0", lineStartingWith(captured, "采样参数："))
        assertFalse(captured.any { it.text.contains("尚未取到实际请求") })
    }

    @Test
    fun absentMemoryFeaturesReadAsUnknown() {
        val lines = linesOf("""{ "memoryFeatures": [ { "id": "built-in", "enabled": true } ] }""")

        assertEquals(
            "记忆功能：角色记忆 已开启，总结扩展 未知，聊天向量化 未知",
            lineStartingWith(lines, "记忆功能："),
        )
    }

    @Test
    fun retrievalLineKeepsOnlyTheReportedNumbers() {
        val lines = linesOf("""{ "retrieval": { "scoreThreshold": 0.45 } }""")

        assertEquals("检索：分数门限 0.45", lineStartingWith(lines, "检索："))
        assertFalse(lines.any { it.text.contains("上次注入条数") })
    }

    @Test
    fun exponentNotationKeepsItsWholePart() {
        val lines = linesOf("""{ "retrieval": { "scoreThreshold": 1.6e5 } }""")

        assertEquals("检索：分数门限 160000", lineStartingWith(lines, "检索："))
    }

    @Test
    fun renderSpendIsReportedWithItsSectionBreakdown() {
        val lines = linesOf(
            """
            {
              "render": { "budgetChars": 1600, "usedChars": 1596, "injectedLines": 42,
                          "usedTokens": 2150, "tokenSource": "gpt-2-bpe",
                          "sectionsOffered": 11, "sectionsDropped": 5,
                          "sections": [ { "title": "Memory book", "tier": "core", "chars": 480, "lines": 24 },
                                        { "title": "Current facts", "tier": "core", "chars": 120, "lines": 4 } ] }
            }
            """.trimIndent(),
        )

        assertEquals(
            "渲染：已用 1596/1600 字符，注入 42 行，约 2150 token，段落 6/11（5 段未装入）",
            lineStartingWith(lines, "渲染："),
        )
        assertEquals(
            "段落占用：Memory book 480，Current facts 120",
            lineStartingWith(lines, "段落占用："),
        )
    }

    @Test
    fun budgetSourceSaysWhetherTheBudgetScalesWithTheModel() {
        val scaled = linesOf(
            """{ "render": { "budgetChars": 1600, "budgetTokens": 2133, "budgetSource": "prompt-tokens", "promptTokens": 12288 } }""",
        )
        assertEquals(
            "注入额度：随模型上下文自动伸缩（提示词预算 12288 token）",
            lineStartingWith(scaled, "注入额度："),
        )

        val scaledWithoutPrompt = linesOf("""{ "render": { "budgetSource": "prompt-tokens" } }""")
        assertEquals(
            "注入额度：随模型上下文自动伸缩",
            lineStartingWith(scaledWithoutPrompt, "注入额度："),
        )

        val fallback = linesOf("""{ "render": { "budgetChars": 1600, "budgetSource": "fallback" } }""")
        assertEquals("注入额度：固定值（尚未取到模型上下文）", lineStartingWith(fallback, "注入额度："))

        // An engine that does not report the source says nothing about it rather than
        // guessing which one it used.
        assertFalse(linesOf("""{ "render": { "budgetChars": 1600 } }""").any { it.text.startsWith("注入额度：") })
    }

    @Test
    fun renderReportOmitsWhatTheEngineDidNotSend() {
        val partial = linesOf("""{ "render": { "usedChars": 900, "budgetChars": 1600 } }""")
        assertEquals("渲染：已用 900/1600 字符", lineStartingWith(partial, "渲染："))
        assertFalse(partial.any { it.text.contains("段落") })

        val inconsistent = linesOf(
            """{ "render": { "usedChars": 900, "sectionsOffered": 3, "sectionsDropped": 4 } }""",
        )
        // Dropped sections above the offered count cannot be subtracted, so the count is
        // reported as absent instead of printing a negative number of rendered sections.
        assertEquals("渲染：已用 900 字符", lineStartingWith(inconsistent, "渲染："))

        val noDrops = linesOf("""{ "render": { "sectionsOffered": 3, "sectionsDropped": 0 } }""")
        assertEquals("渲染：段落 3/3", lineStartingWith(noDrops, "渲染："))

        val empty = linesOf("""{ "render": {} }""")
        assertFalse(empty.any { it.text.startsWith("渲染：") || it.text.startsWith("段落占用：") })

        val notYetRendered = linesOf("""{ "render": { "budgetChars": 1600, "usedChars": 0, "usedTokens": 0 } }""")
        assertEquals("渲染：已用 0/1600 字符", lineStartingWith(notYetRendered, "渲染："))
    }

    @Test
    fun advisoriesCarryTheirSourceAndStorageIsSummarized() {
        val lines = linesOf(
            """
            {
              "sampling": { "temperature": 1.5, "topP": 0.95, "topK": 40, "minP": 0.05,
                            "source": "last-generate-request" },
              "advisories": [
                { "code": "sampling-not-forwarded", "source": "code", "verifiedAt": "2026-10-13",
                  "text": "当前通道 openai 不会把 top_k、min_p 发给服务商。" },
                { "code": "temperature-high", "source": "community", "verifiedAt": "2026-10-13",
                  "text": "温度 1.5 偏高。" }
              ],
              "storage": { "ok": true, "bytes": 57679872, "freeBytes": 0, "integrity": "ok",
                           "counts": { "messages": 1228, "facts": 307, "entries": 118 },
                           "missingIndexes": [] }
            }
            """.trimIndent(),
        )

        assertEquals(
            "采样参数：温度 1.5，top_p 0.95，top_k 40，min_p 0.05",
            lineStartingWith(lines, "采样参数："),
        )
        assertTrue(lines.any { it.text == "当前通道 openai 不会把 top_k、min_p 发给服务商。（按核心源码核对，2026-10-13）" })
        assertTrue(lines.any { it.text == "温度 1.5 偏高。（社区说法，2026-10-13）" })
        assertEquals(
            "存储：库 55.0 MB，空闲 0.0 MB，完整性 ok，索引齐全",
            lineStartingWith(lines, "存储："),
        )
        assertEquals(
            "存储内容：消息 1228，事实 307，记忆本 118",
            lineStartingWith(lines, "存储内容："),
        )
    }

    @Test
    fun aStorageReportThatFailedIsNotPrinted() {
        val lines = linesOf("""{ "storage": { "ok": false, "error": "worker gone" } }""")
        assertFalse(lines.any { it.text.startsWith("存储") })
    }

    @Test
    fun aMissingIndexIsCountedInsteadOfListed() {
        val lines = linesOf(
            """{ "storage": { "ok": true, "missingIndexes": ["idx_memory_chunks_scope_updated"] } }""",
        )
        assertEquals("存储：缺 1 个索引", lineStartingWith(lines, "存储："))
        assertFalse(lines.any { it.text.contains("idx_memory_chunks_scope_updated") })
    }

    @Test
    fun `the session sampling override is built from the typed fields or refused`() {
        val override = memoryCenterSamplingOverride("0.8", "off", "0", "")
        assertEquals(3, override?.length())
        assertEquals(0.8, override?.optDouble("temperature") ?: -1.0, 0.0001)
        assertEquals(true, override?.opt("repetitionPenalty") == org.json.JSONObject.NULL)
        assertEquals(0.0, override?.optDouble("frequencyPenalty") ?: -1.0, 0.0001)
        assertEquals(false, override?.has("presencePenalty"))

        // Blank everywhere means "no override", which the caller treats as "switch off".
        assertNull(memoryCenterSamplingOverride("", "  ", "", ""))

        // One bad field refuses the whole thing instead of applying the rest.
        assertNull(memoryCenterSamplingOverride("0.8", "很热", "", ""))

        // The same "take it out" instruction is also accepted as a single CJK character.
        val chineseOff = memoryCenterSamplingOverride("", "", "", "无")
        assertEquals(1, chineseOff?.length())
        assertEquals(true, chineseOff?.opt("presencePenalty") == org.json.JSONObject.NULL)
    }

    @Test
    fun `manual fact expressions are parsed or refused, never guessed`() {
        val parsed = memoryCenterParseFact("阿戊.location = 北门客栈")
        assertEquals("阿戊", parsed?.subject)
        assertEquals("location", parsed?.predicate)
        assertEquals("北门客栈", parsed?.value)

        // A value that contains "=" keeps everything after the first one.
        assertEquals(
            "a=1",
            memoryCenterParseFact("阿戊.note = a=1")?.value,
        )

        for (refused in listOf("", "阿戊", "阿戊.location", "= 北门客栈", "阿戊. = 北门客栈", ".location = x", "阿戊.location =")) {
            assertNull(refused, memoryCenterParseFact(refused))
        }
    }

    @Test
    fun `a reused prefix reads as a measurement, a lost one as a warning`() {
        val healthy = memoryCenterPrefixCacheLine(
            JSONObject(
                """
                {"messages": 60, "reuseRatio": 0.95, "reuseMessages": 57, "firstDiff": 57,
                 "freshChars": 176, "sinceSeconds": 32.1}
                """.trimIndent(),
            ),
        )
        assertEquals(
            "前缀复用：上次请求复用 95%（57/60 条消息与上一轮完全一致），需重算 176 字。前缀稳定，服务商可以命中缓存。",
            healthy?.text,
        )
        assertFalse(healthy!!.isWarning)

        // The interesting case: the first difference is at the very front, so everything behind
        // it is recomputed. That has to carry the advice, not just the number.
        val lost = memoryCenterPrefixCacheLine(
            JSONObject(
                """
                {"messages": 60, "reuseRatio": 0.05, "reuseMessages": 2, "firstDiff": 2,
                 "freshChars": 30000, "sinceSeconds": 30}
                """.trimIndent(),
            ),
        )
        assertTrue(lost!!.isWarning)
        assertTrue(lost.text.startsWith("前缀复用：上次请求复用 5%（2/60 条消息与上一轮完全一致），需重算 30000 字。"))
        assertTrue(lost.text.contains("重算从第 3 条（共 60 条）开始"))
        assertTrue(lost.text.contains("服务商报告"))
    }

    @Test
    fun `no previous request is not the same as reusing nothing`() {
        val first = memoryCenterPrefixCacheLine(
            JSONObject("""{"messages": 12, "reuseRatio": 0, "reuseMessages": 0, "firstDiff": 0, "freshChars": 400, "sinceSeconds": null}"""),
        )
        assertEquals("前缀复用：还没有可比对的上一轮请求（本次会话尚无记录）", first?.text)
        assertFalse(first!!.isWarning)

        // Absent and unusable reports are left out rather than rendered as zero.
        assertNull(memoryCenterPrefixCacheLine(null))
        assertNull(memoryCenterPrefixCacheLine(JSONObject("""{"messages": 0}""")))
        assertNull(memoryCenterPrefixCacheLine(JSONObject("""{}""")))
    }

    @Test
    fun `the reuse line reaches the rendered list from the endpoint payload`() {
        // The engine sends the report nested under prefixCache. Parsing it one level too high or
        // too low would leave the line out of the page without any test failing on the text.
        val lines = linesOf(
            """
            {
              "prefixCache": { "messages": 40, "reuseRatio": 0.31, "reuseMessages": 12,
                               "firstDiff": 12, "freshChars": 9000, "sinceSeconds": 30 },
              "warnings": []
            }
            """.trimIndent(),
        )
        val line = lines.firstOrNull { it.text.startsWith("前缀复用：") }
        assertEquals(
            "前缀复用：上次请求复用 31%（12/40 条消息与上一轮完全一致），需重算 9000 字。" +
                "重算从第 13 条（共 40 条）开始：排在它前面、每轮都会变的内容会让它之后的全部作废。" +
                "这是按字符比对得出的下限，实际计费以「服务商报告」为准。",
            line?.text,
        )
        assertTrue(line?.isWarning == true)

        // A payload without the field renders the rest and simply omits this line.
        assertFalse(linesOf("""{ "warnings": [] }""").any { it.text.startsWith("前缀复用：") })
    }

    @Test
    fun `the provider's own counters are reported next to the estimate`() {
        val billed = memoryCenterProviderUsageLine(
            JSONObject(
                """{"hitTokens": 9412, "missTokens": 258731, "promptTokens": 268143, "hitRatio": 0.035}""",
            ),
        )
        assertEquals("服务商报告：命中 9412 token、未命中 258731 token（命中 4%）。", billed?.text)
        assertFalse(billed!!.isWarning)

        // OpenAI nests the cached part inside its prompt count, so there is no separate miss
        // number to print.
        val nested = memoryCenterProviderUsageLine(
            JSONObject("""{"hitTokens": 1500, "promptTokens": 2000, "hitRatio": 0.75}"""),
        )
        assertEquals("服务商报告：提示词 2000 token，其中命中 1500 token（命中 75%）。", nested?.text)
    }

    @Test
    fun `a cache that expired between turns reads as a warning, not as zero reuse`() {
        val expired = memoryCenterProviderUsageLine(
            JSONObject("""{"hitTokens": 0, "missTokens": 268143, "promptTokens": 268143, "hitRatio": 0}"""),
        )
        assertTrue(expired!!.isWarning)
        assertTrue(expired.text.startsWith("服务商报告：命中 0 token、未命中 268143 token（命中 0%）。"))
        assertTrue(expired.text.contains("缓存存活时间"))

        // Absent or unrecognised counters leave the line out instead of printing zeros.
        assertNull(memoryCenterProviderUsageLine(null))
        assertNull(memoryCenterProviderUsageLine(JSONObject("""{}""")))
        assertNull(memoryCenterProviderUsageLine(JSONObject("""{"at": 1, "model": "deepseek-chat"}""")))
    }

    @Test
    fun `the provider line reaches the rendered list from the endpoint payload`() {
        val lines = linesOf(
            """
            {
              "providerUsage": { "hitTokens": 800, "missTokens": 200, "promptTokens": 1000,
                                 "hitRatio": 0.8 },
              "warnings": []
            }
            """.trimIndent(),
        )
        assertTrue(lines.any { it.text == "服务商报告：命中 800 token、未命中 200 token（命中 80%）。" })
    }
}
