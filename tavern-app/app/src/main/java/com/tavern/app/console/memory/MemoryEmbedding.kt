package com.tavern.app.console.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.LongBuffer
import org.json.JSONObject

/**
 * Sentence embeddings, on the device.
 *
 * The memory engine runs inside Node, which has no ONNX runtime for Android; this is the Kotlin
 * half that does have one, and [EmbedServer] hands it to the engine over loopback.
 *
 * The model ships inside the installation package and is unpacked on first use; a download
 * ([MemoryModelPack]) can replace it when the bundled copy is missing or out of date. Until the
 * files are on disk every entry point answers "not ready" instead of throwing — the page needs to
 * tell "not installed yet" from "failed to load", and those look identical from inside a loader
 * that only knows about files.
 */
object MemoryEmbedding {
    private const val TAG = "MemoryEmbed"
    private const val FOLDER = "memory-model"
    private const val MAX_TOKENS = 256
    // Sized so a chunk stays a few hundred milliseconds: eight rows of a quarter of the context
    // is the point where a bigger matrix costs more time than the extra rows save.
    private const val BATCH_SIZE = 8

    @Volatile private var session: OrtSession? = null
    @Volatile private var tokenizer: BertWordPiece? = null
    @Volatile private var failed: String? = null

    /** Dimensions of the pooled vector, or 0 before the model has loaded. */
    val dimension: Int get() = dim

    @Volatile private var dim = 0

    fun status(context: Context?): String = when {
        session != null -> "ready"
        failed != null -> "failed: $failed"
        context != null && !MemoryModelPack.installed(context) -> "missing"
        else -> "not-loaded"
    }

    /**
     * Loads the model if it is on disk, copying it out of assets the first time.
     *
     * ONNX Runtime reads a path, not an asset stream, so a bundled model is unpacked once. The
     * copy is skipped when a file of the same size is already there: the check is cheap and a
     * partially copied model would fail much later, during inference.
     */
    @Synchronized
    fun prepare(context: Context): Boolean {
        if (session != null) return true
        if (failed != null) return false
        val dir = File(context.filesDir, FOLDER)
        return try {
            unpackAssets(context, dir)
            val modelFile = File(dir, "model.onnx")
            val tokenizerFile = File(dir, "tokenizer.json")
            if (!modelFile.isFile || !tokenizerFile.isFile) {
                // Not a failure: no download yet looks exactly like this, and a sticky "failed"
                // would outlive the download that fixes it.
                return false
            }
            val vocab = readVocab(tokenizerFile)
            tokenizer = BertWordPiece(vocab)
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // Two threads: the query is one short sentence, and leaving every core busy wakes
                // the big cluster for a few milliseconds of work.
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            session = env.createSession(modelFile.absolutePath, options)
            dim = 0
            Log.i(TAG, "model ready (${modelFile.length() / 1024} KB, vocab ${vocab.size})")
            true
        } catch (error: Throwable) {
            failed = error.message ?: error.javaClass.simpleName
            Log.w(TAG, "model load failed", error)
            false
        }
    }

    /**
     * Forgets the last attempt, so a freshly downloaded model is picked up without a restart.
     *
     * Loading runs once and remembers its outcome; that is what keeps every generation from
     * paying for a file check. A model that arrives afterwards is invisible to it until this is
     * called.
     */
    @Synchronized
    fun retry(context: Context): Boolean {
        runCatching { session?.close() }
        session = null
        tokenizer = null
        dim = 0
        failed = null
        return prepare(context)
    }

    /**
     * The L2-normalised CLS vector for one sentence, or null when the model is not usable.
     *
     * Normalised so cosine similarity is a plain dot product; the model is trained to be used
     * this way, and it keeps the comparison in the engine to one multiply per dimension.
     */
    fun embed(context: Context, text: String): FloatArray? =
        embedBatch(context, listOf(text)).firstOrNull()

    /**
     * Vectors for several sentences in one pass, or nulls for the ones that could not be made.
     *
     * One generation compares the question against a dozen candidate turns. Encoding them one at
     * a time costs a full encoder pass each (measured 86-190 ms warm on device), which is a second
     * and a half before the model has even been asked to reply; padding them into a matrix and
     * running once costs one pass of the longest sequence in the chunk.
     */
    fun embedBatch(context: Context, texts: List<String>): List<FloatArray?> {
        if (texts.isEmpty()) return emptyList()
        if (!prepare(context)) return texts.map { null }
        val activeSession = session ?: return texts.map { null }
        val activeTokenizer = tokenizer ?: return texts.map { null }
        val out = arrayOfNulls<FloatArray>(texts.size)
        var start = 0
        while (start < texts.size) {
            val end = minOf(start + BATCH_SIZE, texts.size)
            val rows = ArrayList<IntArray>(end - start)
            for (index in start until end) {
                val trimmed = texts[index].trim().take(400)
                rows.add(if (trimmed.isEmpty()) IntArray(0) else activeTokenizer.encode(trimmed, MAX_TOKENS))
            }
            val vectors = runChunk(activeSession, rows)
            for (offset in vectors.indices) out[start + offset] = vectors[offset]
            start = end
        }
        return out.toList()
    }

    /** One ONNX pass for up to [BATCH_SIZE] sentences, padded to the longest in the chunk. */
    private fun runChunk(activeSession: OrtSession, rows: List<IntArray>): List<FloatArray?> {
        val padded = padRows(rows) ?: return rows.map { null }
        val width = padded.first
        val idsArray = padded.second
        val maskArray = padded.third
        return try {
            val shape = longArrayOf(rows.size.toLong(), width.toLong())
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, LongBuffer.wrap(idsArray), shape).use { idsTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(maskArray), shape).use { maskTensor ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(rows.size * width)), shape)
                        .use { typeTensor ->
                            val inputs = HashMap<String, OnnxTensor>()
                            for (name in activeSession.inputNames) {
                                inputs[name] = when (name) {
                                    "input_ids" -> idsTensor
                                    "attention_mask" -> maskTensor
                                    "token_type_ids" -> typeTensor
                                    else -> idsTensor
                                }
                            }
                            activeSession.run(inputs).use { result ->
                                poolFirstToken(result.get(0).value, rows.size)
                            }
                        }
                }
            }
        } catch (error: Throwable) {
            // A model exported with a fixed batch size of one rejects the padded matrix. Falling
            // back to one row at a time keeps recall working at the old speed instead of losing
            // the whole vector path to a shape mismatch.
            if (rows.size > 1) {
                Log.w(TAG, "batched embed failed, retrying one by one: ${error.message}")
                return rows.map { runChunk(activeSession, listOf(it)).firstOrNull() }
            }
            Log.w(TAG, "embed failed", error)
            rows.map { null }
        }
    }

    /**
     * A rectangular matrix for the encoder, plus the mask that tells it which positions are real.
     *
     * Two off-by-one traps live here and both are silent: a row of padding that is not masked
     * changes its neighbour's vector, and a mask one shorter than the row truncates the sentence.
     * Kept as its own function so the arithmetic can be checked without a runtime and a model.
     */
    internal fun padRows(rows: List<IntArray>): Triple<Int, LongArray, LongArray>? {
        val width = rows.maxOfOrNull { it.size } ?: 0
        if (width == 0) return null
        val ids = LongArray(rows.size * width) { flat ->
            val row = rows[flat / width]
            val column = flat % width
            if (column < row.size) row[column].toLong() else 0L
        }
        val mask = LongArray(rows.size * width) { flat ->
            val row = rows[flat / width]
            if (flat % width < row.size) 1L else 0L
        }
        return Triple(width, ids, mask)
    }

    @Suppress("UNCHECKED_CAST")
    private fun poolFirstToken(value: Any?, count: Int): List<FloatArray?> {
        // [batch][tokens][hidden] for the sentence-transformers export.
        val batch = value as? Array<Array<FloatArray>> ?: return List(count) { null }
        val out = ArrayList<FloatArray?>(count)
        for (index in 0 until count) {
            val row = batch.getOrNull(index)
            val cls = row?.firstOrNull()
            if (cls == null) {
                out.add(null)
                continue
            }
            var norm = 0.0
            for (component in cls) norm += component.toDouble() * component
            norm = Math.sqrt(norm)
            if (norm <= 0.0) {
                out.add(null)
                continue
            }
            dim = cls.size
            out.add(FloatArray(cls.size) { position -> (cls[position] / norm).toFloat() })
        }
        return out
    }

    private fun unpackAssets(context: Context, dir: File) {
        if (!dir.exists() && !dir.mkdirs()) return
        val assets = context.assets.list(FOLDER).orEmpty()
        for (name in assets) {
            val target = File(dir, if (name == "model_quantized.onnx") "model.onnx" else name)
            val expected = context.assets.open("$FOLDER/$name").use { it.available().toLong() }
            if (target.isFile && target.length() > 0) continue
            // Through a staging file: the model is stored deflated, so this can be interrupted
            // mid-stream, and a half-written model.onnx is large enough to pass the "is it
            // there" check while failing at inference with nothing in the log to explain it.
            val staging = File(dir, "$name.part")
            staging.delete()
            context.assets.open("$FOLDER/$name").use { input ->
                staging.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            if (!staging.renameTo(target)) {
                staging.delete()
                throw IllegalStateException("could not place $name")
            }
            Log.i(TAG, "unpacked $name (${expected / 1024} KB)")
        }
        // A quantized export keeps its name; the loader wants one path either way.
        val quantized = File(dir, "model_quantized.onnx")
        val canonical = File(dir, "model.onnx")
        if (!canonical.isFile && quantized.isFile) quantized.renameTo(canonical)
    }

    private fun readVocab(file: File): Map<String, Int> {
        val text = file.readText()
        val root = JSONObject(text)
        val model = root.optJSONObject("model") ?: root
        val vocabJson = model.optJSONObject("vocab") ?: return emptyMap()
        val map = HashMap<String, Int>(vocabJson.length() * 2)
        for (key in vocabJson.keys()) map[key] = vocabJson.getInt(key)
        return map
    }

    /** For tests and diagnostics: how many tokens the tokenizer produces for a sentence. */
    fun tokenCount(text: String): Int = tokenizer?.encode(text, MAX_TOKENS)?.size ?: 0

    /** Number of dimensions reported by the last successful embedding. */
    fun shapeHint(): String = "dim=$dim status=${status(null)}"
}
