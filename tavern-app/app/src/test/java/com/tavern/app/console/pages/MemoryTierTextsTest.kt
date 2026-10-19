package com.tavern.app.console.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line under each switch says which path is actually running. It is the only place a reader
 * can tell "the local fallback" from "the service I configured", so the wording is pinned here:
 * a service is named by its model, and a half-filled service says so instead of pretending.
 */
class MemoryTierTextsTest {

    @Test
    fun extractionNamesTheModelItWillUse() {
        assertEquals(
            "当前：核心已配置的模型",
            memoryAnalysisTierText(useForAnalysis = false, endpoint = "", model = ""),
        )
        assertEquals(
            "当前：模型服务 gemini-2.5-flash",
            memoryAnalysisTierText(
                useForAnalysis = true,
                endpoint = "https://api.example.test/v1",
                model = "gemini-2.5-flash",
            ),
        )
        // Half a service is not a service: the pass runs on the core's model and says so.
        val incomplete = memoryAnalysisTierText(
            useForAnalysis = true,
            endpoint = "https://api.example.test/v1",
            model = "",
        )
        assertTrue(incomplete.contains("未填全"))
        assertFalse(incomplete.contains("模型服务 "))
    }

    @Test
    fun rewritingAndRerankNameTheirOwnFallback() {
        assertEquals("当前：不改写查询", memoryRewriteTierText(false, "", ""))
        assertEquals(
            "当前：本地改写（切词、同义与指代）",
            memoryRewriteTierText(true, "", ""),
        )
        assertEquals(
            "当前：模型服务 rewrite-model",
            memoryRewriteTierText(true, "https://api.example.test/v1", "rewrite-model"),
        )

        assertEquals("当前：保持检索顺序", memoryRerankTierText(false, "", ""))
        assertEquals("当前：本地重排", memoryRerankTierText(true, "", ""))
        assertEquals(
            "当前：重排服务 rerank-model",
            memoryRerankTierText(true, "https://api.example.test/v1", "rerank-model"),
        )
    }

    @Test
    fun vectorsReportBothTheServiceAndHowMuchOfTheIndexIsBuilt() {
        assertEquals(
            "当前：不使用向量",
            memoryVectorTierText("off", "embed-model", 0, 0, null),
        )
        assertEquals(
            "当前：设备本地向量",
            memoryVectorTierText("local", "", 0, 0, null),
        )
        assertEquals(
            "当前：向量服务 embed-model（已索引 320 / 320 条，1024 维）",
            memoryVectorTierText("model", "embed-model", 320, 320, 1024),
        )
        // Local vectors are out of the picture in this tier, so an empty index has to be named.
        val empty = memoryVectorTierText("model", "embed-model", 0, 120, 1024)
        assertTrue(empty.contains("索引为空"))
        assertTrue(empty.contains("已索引 0 / 120 条"))
    }
}
