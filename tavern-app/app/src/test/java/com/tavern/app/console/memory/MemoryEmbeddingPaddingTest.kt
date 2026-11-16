package com.tavern.app.console.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The matrix the encoder is fed when several sentences go in one pass.
 *
 * Checked without a runtime or a model because the failure it guards is arithmetic: a row padded
 * without its mask, or a mask one position short, still produces a vector — just a slightly wrong
 * one, which shows up as "the model is not very good" and never as an error.
 */
class MemoryEmbeddingPaddingTest {

    @Test
    fun `shorter rows are padded and masked out`() {
        val rows = listOf(
            intArrayOf(1, 2, 3),
            intArrayOf(4, 5),
        )
        val padded = MemoryEmbedding.padRows(rows)!!
        assertEquals("the matrix is as wide as the longest row", 3, padded.first)
        assertArrayEquals(longArrayOf(1, 2, 3, 4, 5, 0), padded.second)
        assertArrayEquals("the pad position must not be attended to", longArrayOf(1, 1, 1, 1, 1, 0), padded.third)
    }

    @Test
    fun `an already rectangular batch is left alone`() {
        val rows = listOf(intArrayOf(7, 8), intArrayOf(9, 10))
        val padded = MemoryEmbedding.padRows(rows)!!
        assertEquals(2, padded.first)
        assertArrayEquals(longArrayOf(7, 8, 9, 10), padded.second)
        assertArrayEquals(longArrayOf(1, 1, 1, 1), padded.third)
    }

    @Test
    fun `rows longer than the first one still fit`() {
        val rows = listOf(
            intArrayOf(1),
            intArrayOf(2, 3, 4, 5),
            intArrayOf(6, 7),
        )
        val padded = MemoryEmbedding.padRows(rows)!!
        assertEquals(4, padded.first)
        assertArrayEquals(longArrayOf(1, 0, 0, 0, 2, 3, 4, 5, 6, 7, 0, 0), padded.second)
        assertArrayEquals(longArrayOf(1, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0), padded.third)
    }

    @Test
    fun `nothing to encode answers nothing rather than an empty matrix`() {
        assertNull(MemoryEmbedding.padRows(emptyList()))
        assertNull("a sentence that tokenised to nothing is not a row of padding", MemoryEmbedding.padRows(listOf(intArrayOf())))
    }

    @Test
    fun `one row per sentence keeps the sentence order`() {
        // Pooling reads row by row; a batch whose rows are reordered would pair a turn with
        // another turn's vector, which no assertion downstream could catch.
        val rows = (1..5).map { intArrayOf(it, it * 10) }
        val padded = MemoryEmbedding.padRows(rows)!!
        for (index in rows.indices) {
            assertEquals(rows[index][0].toLong(), padded.second[index * 2])
            assertEquals(rows[index][1].toLong(), padded.second[index * 2 + 1])
        }
    }
}
