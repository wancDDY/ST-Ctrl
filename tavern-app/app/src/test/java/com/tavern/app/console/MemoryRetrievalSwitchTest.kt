package com.tavern.app.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch decides whether a helper runs at all; the filled-in address only decides who runs
 * it. These assertions pin that order, because getting it backwards is what makes an install
 * that was told to use no vectors quietly keep using the local ones.
 */
class MemoryRetrievalSwitchTest {

    @Test
    fun `vector switch off wins over a complete address`() {
        val off = MemoryEmbeddingConfig(
            retrieval = false,
            endpoint = "https://api.example.test/v1",
            model = "text-embedding-3-small",
        )
        assertFalse(off.providerEnabled)
    }

    @Test
    fun `a complete address is what turns the vector service on`() {
        assertFalse(MemoryEmbeddingConfig(endpoint = "https://api.example.test/v1").providerEnabled)
        assertFalse(MemoryEmbeddingConfig(model = "text-embedding-3-small").providerEnabled)
        assertTrue(
            MemoryEmbeddingConfig(
                endpoint = "https://api.example.test/v1",
                model = "text-embedding-3-small",
            ).providerEnabled,
        )
    }

    @Test
    fun `the switches default to what the engine ran before they existed`() {
        val embedded = MemoryEmbeddingConfig()
        assertTrue(embedded.retrieval)

        val enhancement = MemoryEnhancementConfig()
        // Local query rewriting ran unconditionally, local rerank is a new layer.
        assertTrue(enhancement.rewriteRetrieval)
        assertFalse(enhancement.rerankRetrieval)
        assertFalse(enhancement.rewriteProviderEnabled)
        assertFalse(enhancement.rerankProviderEnabled)
    }

    @Test
    fun `each enhancement switch only speaks for its own helper`() {
        val config = MemoryEnhancementConfig(
            rewriteRetrieval = false,
            rewriteEndpoint = "https://api.example.test/v1",
            rewriteModel = "rewrite-model",
            rerankRetrieval = true,
            rerankEndpoint = "https://api.example.test/v1",
            rerankModel = "rerank-model",
        )
        assertFalse(config.rewriteProviderEnabled)
        assertTrue(config.rerankProviderEnabled)
        assertEquals("rerank-model", config.rerankModel)
    }
}
