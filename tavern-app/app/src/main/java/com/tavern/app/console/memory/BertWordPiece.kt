package com.tavern.app.console.memory

/**
 * WordPiece for BERT-family Chinese models, on the JVM.
 *
 * The tokenizer ships inside the model folder as `tokenizer.json`; only the pieces this app
 * needs are read from it (vocabulary, the continuation prefix, the unknown token). Written by
 * hand rather than pulled in as a dependency because the whole point of the local model is to
 * stay small and offline.
 */
class BertWordPiece(
    private val vocab: Map<String, Int>,
    private val unkToken: String = "[UNK]",
    private val continuingPrefix: String = "##",
    private val maxCharsPerWord: Int = 100,
) {
    val clsId: Int = vocab["[CLS]"] ?: 101
    val sepId: Int = vocab["[SEP]"] ?: 102
    val unkId: Int = vocab[unkToken] ?: 100
    val padId: Int = vocab["[PAD]"] ?: 0
    val vocabSize: Int get() = vocab.size

    /** Token ids for one sentence, with the `[CLS]` and `[SEP]` wrapper the encoder expects. */
    fun encode(text: String, maxLength: Int = 256): IntArray {
        val ids = ArrayList<Int>(maxLength)
        ids.add(clsId)
        for (piece in basicTokens(text)) {
            for (id in wordPiece(piece)) {
                if (ids.size >= maxLength - 1) break
                ids.add(id)
            }
            if (ids.size >= maxLength - 1) break
        }
        ids.add(sepId)
        return ids.toIntArray()
    }

    /**
     * Whitespace and punctuation split, with every CJK character on its own.
     *
     * This is what BERT's own pre-tokenizer does for Chinese: the vocabulary is built from single
     * characters, so a three-character name has to arrive as three pieces before WordPiece can match anything.
     */
    private fun basicTokens(text: String): List<String> {
        val cleaned = StringBuilder(text.length + 16)
        for (ch in text) {
            when {
                ch.isISOControl() || ch == '\uFFFD' -> cleaned.append(' ')
                isCjk(ch) -> cleaned.append(' ').append(ch).append(' ')
                else -> cleaned.append(ch)
            }
        }
        val out = ArrayList<String>()
        for (word in cleaned.toString().split(' ', '\n', '\r', '\t')) {
            if (word.isEmpty()) continue
            var start = 0
            for (index in word.indices) {
                if (!isPunctuation(word[index])) continue
                if (index > start) out.add(word.substring(start, index))
                out.add(word[index].toString())
                start = index + 1
            }
            if (start < word.length) out.add(word.substring(start))
        }
        return out
    }

    /** Greedy longest-match-first, the algorithm the BERT paper specifies for unknown words. */
    private fun wordPiece(token: String): List<Int> {
        if (token.length > maxCharsPerWord) return listOf(unkId)
        val ids = ArrayList<Int>(4)
        var start = 0
        while (start < token.length) {
            var end = token.length
            var matched = -1
            while (start < end) {
                val piece = if (start > 0) continuingPrefix + token.substring(start, end)
                else token.substring(start, end)
                val id = vocab[piece]
                if (id != null) {
                    matched = id
                    break
                }
                end -= 1
            }
            if (matched < 0) return listOf(unkId)
            ids.add(matched)
            start = end
        }
        return ids
    }

    private fun isCjk(ch: Char): Boolean =
        ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' || ch in '\uF900'..'\uFAFF'

    private fun isPunctuation(ch: Char): Boolean = when (Character.getType(ch).toByte()) {
        Character.CONNECTOR_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.MATH_SYMBOL,
        Character.CURRENCY_SYMBOL,
        Character.MODIFIER_SYMBOL,
        Character.OTHER_SYMBOL,
        -> true
        else -> false
    }
}
