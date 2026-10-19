package com.tavern.app.console.pages

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCenterProbeTest {

    @Test
    fun `a probe the core could not complete is not reported as a model list`() {
        // The core answers an unreachable address or a rejected key with HTTP 200 and this body,
        // so an empty result is the only thing separating it from a provider that published no
        // models. Reading it as success told the reader a broken address worked.
        val failed = JSONObject("""{"error": true, "data": {"data": []}}""")
        val thrown = runCatching { memoryProbeModels(failed) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown!!.message.orEmpty().contains("连接失败"))
    }

    @Test
    fun `a rejected request without a body is a failure too`() {
        val thrown = runCatching { memoryProbeModels(JSONObject("""{"error": true}""")) }
            .exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `an answered probe returns the model ids it published`() {
        val models = memoryProbeModels(
            JSONObject(
                """
                {"object": "list", "data": [
                    {"id": "gpt-4o-mini"},
                    {"id": "gpt-4o"},
                    {"id": "gpt-4o"},
                    {"name": "named-only"}
                ]}
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("gpt-4o", "gpt-4o-mini", "named-only"), models)
    }

    @Test
    fun `a provider that answered without models stays a success`() {
        // An address that answers but publishes no list is not the same failure: the reader is
        // told to fill the model in by hand, which is what the screen shows for an empty list.
        assertEquals(emptyList<String>(), memoryProbeModels(JSONObject("""{"object": "list"}""")))
    }
}
