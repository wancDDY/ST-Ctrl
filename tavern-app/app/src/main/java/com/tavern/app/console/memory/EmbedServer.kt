package com.tavern.app.console.memory

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Loopback endpoint that lets the memory engine ask for a sentence vector.
 *
 * The engine lives in Node and Node has no ONNX runtime on Android, so the two halves meet over
 * 127.0.0.1: the server binds the loopback address only, accepts a single line of JSON, and
 * answers with a normalised vector. Nothing here is reachable from the network.
 */
object EmbedServer {
    private const val TAG = "MemoryEmbed"
    private const val PORT = 8129
    // Headers only ever run to a few hundred bytes; the body is a dozen turns of prose, tens of
    // kilobytes rather than one short sentence, and the caps are sized for that.
    private const val MAX_HEAD = 8192
    private const val MAX_BODY = 65536

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var worker: Thread? = null

    /**
     * When the last late load was started, so a health check that keeps asking does not keep
     * spawning threads. Ten seconds is longer than a load takes on any device this runs on.
     */
    @Volatile private var lateLoadAt = 0L

    /** Starts the listener once; a second call is a no-op. */
    @Synchronized
    fun start(context: Context) {
        if (socket != null) return
        val app = context.applicationContext
        worker = thread(name = "memory-embed", isDaemon = true) {
            try {
                val server = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                socket = server
                Log.i(TAG, "listening on 127.0.0.1:$PORT")
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    // One connection at a time: a generation asks once, and serialising keeps the
                    // session single-threaded.
                    runCatching { serve(app, client) }
                }
            } catch (error: Throwable) {
                Log.w(TAG, "listener stopped: ${error.message}")
            } finally {
                socket = null
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    fun isRunning(): Boolean = socket?.isClosed == false

    private fun serve(context: Context, client: Socket) {
        client.use { connection ->
            connection.soTimeout = 8000
            val input = BufferedInputStream(connection.getInputStream())
            val request = readRequest(input) ?: return
            val response = when {
                request.first.startsWith("GET /health") -> {
                    // `ok` answers "can this thing make a vector right now", not "is the
                    // listener up". A constant true would report a build without a model as
                    // healthy, and the engine would send batches at a listener that can only
                    // refuse them.
                    val state = MemoryEmbedding.status(context)
                    // The model is warmed at launch only if vectors were already on. Opening the
                    // switch mid-session, or a warm-up that lost a race with a cold start, leaves
                    // the files on disk and nothing loaded — and the memory screen then reports a
                    // fault for a model that is present and would load in a second. Start it here
                    // instead: every status probe passes through this line, so the screen fixes
                    // itself on the next refresh rather than waiting for a restart.
                    if (state == "not-loaded" &&
                        com.tavern.app.console.MemoryEmbeddingPreferences.load(context).retrieval &&
                        System.currentTimeMillis() - lateLoadAt > 10_000L
                    ) {
                        lateLoadAt = System.currentTimeMillis()
                        thread(name = "memory-embed-late", isDaemon = true) {
                            MemoryEmbedding.prepare(context)
                        }
                    }
                    // Answered for the state this request found, but a load started seconds ago
                    // reads better as "loading" than as "not loaded": the second sounds like the
                    // end of the story when it is the start of it.
                    val reported = if (state == "not-loaded" &&
                        lateLoadAt > 0L && System.currentTimeMillis() - lateLoadAt < 10_000L
                    ) "loading" else state
                    val health = JSONObject()
                        .put("ok", reported == "ready")
                        .put("status", reported)
                    // The size of a vector is known only after one has come out. Reporting the
                    // initial zero as `dim` made a loaded model look broken during diagnosis —
                    // "ready, dim 0" reads as a silent failure rather than as "not yet asked".
                    val size = MemoryEmbedding.dimension
                    if (size > 0) health.put("dim", size) else health.put("dimPending", true)
                    health.toString()
                }
                request.first.startsWith("POST /embed") -> handleEmbed(context, request.second)
                else -> JSONObject().put("ok", false).put("error", "unknown route").toString()
            }
            val bytes = response.toByteArray(Charsets.UTF_8)
            val out = BufferedOutputStream(connection.getOutputStream())
            out.write("HTTP/1.1 200 OK\r\n".toByteArray())
            out.write("Content-Type: application/json\r\n".toByteArray())
            out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
            out.write("Connection: close\r\n\r\n".toByteArray())
            out.write(bytes)
            out.flush()
        }
    }

    private fun handleEmbed(context: Context, body: String): String {
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "bad json").toString()
        // One question against a dozen candidate turns arrives as `texts`: the engine batches
        // because a round trip and a session lock per sentence would cost more than the encoder.
        val texts = ArrayList<String>()
        val many = root.optJSONArray("texts")
        if (many != null) {
            for (index in 0 until many.length()) {
                val value = many.optString(index, "")
                if (value.isNotBlank()) texts.add(value)
            }
        } else {
            val single = root.optString("text", "")
            if (single.isNotBlank()) texts.add(single)
        }
        if (texts.isEmpty()) {
            return JSONObject().put("ok", false).put("error", "empty text").toString()
        }
        val vectors = MemoryEmbedding.embedBatch(context, texts)
        if (vectors.all { it == null }) {
            return JSONObject().put("ok", false)
                .put("error", "model unavailable")
                .put("status", MemoryEmbedding.status(context))
                .toString()
        }
        val array = org.json.JSONArray()
        for (vector in vectors) {
            array.put(if (vector == null) "" else vector.joinToString(","))
        }
        return JSONObject().put("ok", true)
            .put("dim", MemoryEmbedding.dimension)
            .put("vectors", array)
            .toString()
    }

    /** Enough HTTP to read one request: request line, headers, then Content-Length bytes. */
    private fun readRequest(input: BufferedInputStream): Pair<String, String>? {
        val head = StringBuilder()
        var seen = 0
        while (true) {
            val value = input.read()
            if (value < 0) return null
            head.append(value.toChar())
            seen += 1
            if (seen > MAX_HEAD) return null
            if (head.endsWith("\r\n\r\n")) break
        }
        val lines = head.toString().split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val length = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (length <= 0 || length > MAX_BODY) return requestLine to ""
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        return requestLine to String(body, 0, read, Charsets.UTF_8)
    }
}
