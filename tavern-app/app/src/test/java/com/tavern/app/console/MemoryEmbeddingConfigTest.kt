package com.tavern.app.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three ways recall can compare a question against an old turn, and which one a given
 * configuration actually asks for.
 *
 * Worth pinning down because a half-filled service address is the case that looks configured and
 * silently is not: the engine reads a complete address or nothing, so the page must agree that
 * this configuration means "local".
 */
class MemoryEmbeddingConfigTest {

    @Test
    fun `an untouched configuration is words only`() {
        val config = MemoryEmbeddingConfig()
        assertFalse("turning vectors on costs a first pass over the chat, so it is not the default",
            config.retrieval)
        assertEquals(MemoryRetrievalMode.KEYWORD, config.mode)
        assertFalse(config.providerEnabled)
    }

    @Test
    fun `turning recall on with no address uses the bundled model`() {
        val config = MemoryEmbeddingConfig(retrieval = true)
        assertEquals(MemoryRetrievalMode.LOCAL, config.mode)
        assertFalse(config.providerEnabled)
    }

    @Test
    fun `switching recall off means words only, whatever else is filled in`() {
        val config = MemoryEmbeddingConfig(
            retrieval = false,
            endpoint = "https://api.siliconflow.cn/v1",
            model = "BAAI/bge-m3",
        )
        assertEquals(MemoryRetrievalMode.KEYWORD, config.mode)
        assertFalse("a switch that is off must not ask a service either", config.providerEnabled)
    }

    @Test
    fun `a complete address means the reader's own service`() {
        val config = MemoryEmbeddingConfig(
            retrieval = true,
            endpoint = "https://api.siliconflow.cn/v1",
            model = "BAAI/bge-m3",
        )
        assertEquals(MemoryRetrievalMode.SERVICE, config.mode)
        assertTrue(config.providerEnabled)
    }

    @Test
    fun `half an address falls back to the bundled model instead of a service that cannot work`() {
        val noModel = MemoryEmbeddingConfig(retrieval = true, endpoint = "https://api.siliconflow.cn/v1")
        assertEquals(MemoryRetrievalMode.LOCAL, noModel.mode)
        assertFalse(noModel.providerEnabled)

        val noEndpoint = MemoryEmbeddingConfig(retrieval = true, model = "BAAI/bge-m3")
        assertEquals(MemoryRetrievalMode.LOCAL, noEndpoint.mode)
        assertFalse(noEndpoint.providerEnabled)
    }
}
