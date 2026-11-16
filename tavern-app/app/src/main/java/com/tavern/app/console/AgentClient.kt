package com.tavern.app.console

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * HTTP client for the in-process agent runtime. The agent binds the first free
 * port in a fixed range (collision retry lives on the node side), so callers
 * probe the range once and keep using the live port.
 */
object AgentClient {

    const val BASE_PORT = 8123
    const val MAX_PORT = 8127

    // The service refuses every read or run without this header. It is written by the service
    // beside its state, under the app's private files directory, so another app on the device can
    // reach the port but cannot obtain the token. Read per request because a core restart rewrites
    // the file.
    @Volatile
    private var tokenFile: File? = null

    fun init(context: Context) {
        tokenFile = File(context.filesDir, "agent/memory-token.txt")
    }

    private fun token(): String? = try {
        tokenFile?.takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
    } catch (e: Exception) {
        null
    }

    private fun request(
        port: Int,
        path: String,
        method: String = "GET",
        body: String? = null,
        timeoutMs: Int = 4000,
    ): String? {
        val (code, raw) = requestWithStatus(port, path, method, body, timeoutMs) ?: return null
        // Anything in 2xx is an answer. Only 200 counted before, so the endpoints that accept
        // a job and return before doing it (starting a pass, starting an index) looked like
        // failures to every caller here — the work ran and the page said it had not started.
        if (code !in 200..299) return null
        return raw
    }

    /**
     * The reply body whatever the status was.
     *
     * `request` answers only "did it work", which is what most callers need. A refusal that
     * explains itself is the other case: the memory page prints that sentence, so it has to
     * survive a 502 instead of being flattened into "failed".
     */
    private fun requestWithStatus(
        port: Int,
        path: String,
        method: String = "GET",
        body: String? = null,
        timeoutMs: Int = 4000,
    ): Pair<Int, String>? {
        val connection = try {
            URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return null
        }
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 1500
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Accept", "application/json")
            token()?.let { connection.setRequestProperty("X-STCtrl-Token", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    suspend fun findPort(): Int? = withContext(Dispatchers.IO) {
        for (port in BASE_PORT..MAX_PORT) {
            if (request(port, "/health") != null) return@withContext port
        }
        null
    }


    /**
     * The batch a pass is on right now. Written by the engine when a batch starts and removed when
     * it ends, so `null` means nothing is running for that chat.
     */
    data class MemoryProgress(
        val chatId: String = "",
        val characterId: String = "",
        /** 1-based within the current dispatch. */
        val batch: Int = 1,
        val chatIndex: Int = 1,
        val chatTotal: Int = 1,
        val from: Int = 0,
        val to: Int = 0,
        val count: Int = 0,
        val restarted: Boolean = false,
        val phase: String = "",
        val at: Long = 0,
    ) {
        /** The one line the island and the page share: which batch, which floors. */
        fun describe(): String {
            // A repair pass reads no message, so it has no floor range to name.
            if (phase == "cues") return if (count > 0) "补线索词（$count 条）" else "补线索词"
            val where = if (from >= 0 && to >= from) "第 ${from + 1}–${to + 1} 条" else "第 ${from + 1} 条"
            // A rewrite no longer rebuilds the whole chat: the pass resumes at the floor where the
            // text changed, so the line names that floor instead of saying "from the start".
            val head = if (restarted) "从第 ${from + 1} 条起重新读取" else "第 $batch 批，$where"
            return head + if (count > 0) "（$count 条）" else ""
        }
    }

    /** One chat's memory state as the runtime reports it. */
    data class ChatMemory(
        val chatId: String,
        val characterId: String,
        val messageCount: Int,
        val lastIndex: Int,
        val pending: Int,
        val restarted: Boolean,
        val entries: Int,
        /** Rows that carry cue words, against the rows there are: equal means nothing to repair. */
        val cuedEntries: Int = 0,
        val liveEntries: Int = 0,
        /**
         * Rows that do not name who they are about, open threads above all. They are what the
         * light repair fills; a rewind is the other way to fix the same gap and costs the whole
         * chat instead of one call per batch.
         */
        val untagged: Int = 0,
        /**
         * The prompt version this archive was last written under. A pass only appends, so an
         * archive older than the running one keeps that prompt's judgement about what was worth
         * writing — a chat archived under an older build never picks up a changed standard on its
         * own. Zero means no pass has written here yet.
         */
        val promptVersion: Int = 0,
        val digestChars: Int,
        /** When the transcript last changed; 0 when the scan could not read it. */
        val lastMessageAt: Long = 0,
        val updatedAt: Long,
        val lastError: String,
        val failures: Int,
        /**
         * When an unattended pass will try this chat again after a failure, or 0 when the next
         * dispatch will. The page needs it to tell a chat that is waiting from one that never ran.
         */
        val retryAt: Long = 0,
        val busy: Boolean,
        val progress: MemoryProgress? = null,
        /**
         * The reader switched this chat out of memory. Nothing here is read, written, or carried
         * into a prompt; the archive stays on disk so switching back on resumes where it stopped.
         */
        val disabled: Boolean = false,
        /**
         * Floors a pass stepped over because the model would not file them. They are not an error
         * and they are not in the archive: the page names the range so the gap is visible.
         */
        val skippedFloors: Int = 0,
        val skippedFrom: Int = -1,
        val skippedTo: Int = -1,
        val skippedReason: String = "",
    ) {
        /**
         * Whether this archive was built to an older standard than [current] writes.
         *
         * An empty archive is not stale: there is nothing in it to be out of date, and the next
         * pass writes it under whatever the running build uses. A chat archived before the field
         * existed reads as zero, which is below any version and correctly asks for a rebuild.
         */
        fun staleAgainst(current: Int): Boolean = entries > 0 && promptVersion < current
    }

    data class ChatMemoryState(
        val enabled: Boolean,
        /** Whether a model may be called at all; the console mirrors it rather than trusting its
         *  own switch, because this is what the pass and the protocol obey. */
        val modelTakeover: Boolean = false,
        /** The batch in flight across the whole library, if any. */
        val running: MemoryProgress? = null,
        val chats: List<ChatMemory>,
        /** Set on every generation the runtime injected into; null until one has happened. */
        val lastInjectedChars: Int? = null,
        /** File name of the chat the last injection went to; what the reader is actually reading. */
        val lastInjectedChat: String = "",
        /** The character that chat belongs to; a date-based name alone can name two chats. */
        val lastInjectedCharacter: String = "",
        val lastInjectedAt: Long = 0,
        /** Turns of verbatim text that came with that injection; the detail half of the block. */
        val lastInjectedExcerpts: Int = 0,
        /**
         * The ceiling that injection was assembled against. Null before the first turn, and the
         * only place the "automatic" budget can be named: it is a share of the prompt window of a
         * turn that has already gone out.
         */
        val lastInjectedBudget: Int? = null,
        /** Rows that carry cue words, and how many rows there are to carry them. */
        val cuedEntries: Int = 0,
        val cueEntries: Int = 0,
        /** No chat has been consolidated yet, so the page says how to start rather than why. */
        val emptyArchive: Boolean = false,
        /**
         * The prompt version the running build writes. Compared against each chat's own version:
         * below it means that archive was built to an older standard and only a re-read applies
         * the current one.
         */
        val promptVersion: Int = 0,
        /**
         * What the last request spent and what filled it: tokens already used, the window they
         * were measured against, the largest messages, and why memory stayed out when it did.
         *
         * A request the provider refused for length could not be explained from either app before
         * this: the total is decided by a card and a lore book that never appear as sizes.
         */
        val lastSpent: Int = 0,
        val lastWindow: Int = 0,
        val lastSkipped: String = "",
        val lastParts: List<RequestPart> = emptyList(),
        /**
         * The status and sentence the provider answered with when it refused the request. The
         * chat page shows only "Bad Request"; which limit was hit is only in here.
         */
        val lastProviderStatus: Int = 0,
        val lastProviderDetail: String = "",
        /**
         * Memory whose chat was deleted. It is not in [chats] because there is nothing to open;
         * the page offers to move it aside instead of listing it.
         */
        val orphanCount: Int = 0,
        /** Where a clean puts the files, so the page can name the folder before and after. */
        val orphanTrashDir: String = "",
        val orphanBytes: Long = 0,
    )

    /** One message of the last request, as it counted against the window. */
    data class RequestPart(
        val role: String,
        val tokens: Int,
        val chars: Int,
        val head: String,
    )

    data class MemoryRunReport(
        val processed: Int,
        val skipped: Int,
        val failed: Int,
        /**
         * Why the service declined to start, when it did. A refusal is an answer: "already up to
         * date" and "no model selected" both come back this way, and reporting either as a failed
         * request told the reader to retry something that would never start.
         */
        val refused: String = "",
        /** The service's own sentence when it has one; empty when it only gave a code. */
        val refusedDetail: String = "",
        /**
         * Messages still behind after this dispatch. One request stops after a few batches, so
         * anything above zero means the chat is not done and another round is worth asking for.
         */
        val remaining: Int = 0,
        /** Cue words written by a repair pass; a dispatch can do this and read no message. */
        val cuesFilled: Int = 0,
        /**
         * Floors the model would not file, stepped over so the chat keeps moving. Not a failure
         * and not organised: the message after a run names the number instead of saying "done".
         */
        val declinedFloors: Int = 0,
    )

    /**
     * Which source answers for recall vectors, and whether it answers now.
     *
     * The page needs this because "the model is not there", "the service rejected the key" and
     * "recall is working and this question simply has no paraphrase match" look the same from
     * the outside, and the reader has no other way to tell them apart.
     */
    data class RecallStatus(
        val enabled: Boolean,
        val source: String,
        val ok: Boolean,
        val detail: String,
    )

    suspend fun recallStatus(port: Int): RecallStatus? = withContext(Dispatchers.IO) {
        val raw = request(port, "/v1/memory/recall") ?: return@withContext null
        try {
            val json = JSONObject(raw)
            RecallStatus(
                enabled = json.optBoolean("enabled", true),
                source = json.optString("source", "none"),
                ok = json.optBoolean("ok", false),
                detail = json.optString("status")
                    .ifEmpty { json.optString("model") }
                    .ifEmpty { json.optString("endpoint") }
                    .ifEmpty { json.optString("error") },
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun memoryState(port: Int): ChatMemoryState? = withContext(Dispatchers.IO) {
        val raw = request(port, "/v1/memory/state") ?: return@withContext null
        try {
            val json = JSONObject(raw)
            val chats = json.optJSONArray("chats")
            val rows = buildList {
                for (index in 0 until (chats?.length() ?: 0)) {
                    val row = chats?.optJSONObject(index) ?: continue
                    add(
                        ChatMemory(
                            chatId = row.optString("chatId"),
                            characterId = row.optString("characterId"),
                            messageCount = row.optInt("messageCount"),
                            lastIndex = row.optInt("lastIndex", -1),
                            pending = row.optInt("pending"),
                            restarted = row.optBoolean("restarted"),
                            entries = row.optInt("entries"),
                            cuedEntries = row.optInt("cuedEntries"),
                            liveEntries = row.optInt("liveEntries"),
                            untagged = row.optInt("untagged"),
                            promptVersion = row.optInt("promptVersion"),
                            digestChars = row.optInt("digestChars"),
                            lastMessageAt = row.optLong("lastMessageAt"),
                            updatedAt = row.optLong("updatedAt"),
                            lastError = row.optString("lastError"),
                            failures = row.optInt("failures"),
                            retryAt = row.optLong("retryAt"),
                            busy = row.optBoolean("busy"),
                            progress = progressOf(row.optJSONObject("progress")),
                            disabled = row.optBoolean("disabled"),
                            skippedFloors = row.optInt("skippedFloors"),
                            skippedFrom = row.optInt("skippedFrom", -1),
                            skippedTo = row.optInt("skippedTo", -1),
                            skippedReason = row.optString("skippedReason"),
                        ),
                    )
                }
            }
            val last = json.optJSONObject("lastInjection")
            ChatMemoryState(
                enabled = json.optBoolean("chatMemoryEnabled"),
                modelTakeover = json.optBoolean("modelTakeover"),
                running = progressOf(json.optJSONObject("running")),
                chats = rows.sortedBy { it.characterId.lowercase() },
                lastInjectedChars = last?.optInt("chars"),
                lastInjectedChat = last?.optString("chat").orEmpty(),
                lastInjectedCharacter = last?.optString("character").orEmpty(),
                lastInjectedAt = last?.optLong("at") ?: 0,
                lastInjectedExcerpts = last?.optInt("excerpts") ?: 0,
                lastInjectedBudget = last?.takeIf { it.has("budget") }?.optInt("budget"),
                lastSpent = last?.optInt("spent") ?: 0,
                lastWindow = last?.optJSONObject("limits")?.optInt("promptTokens") ?: 0,
                lastSkipped = last?.optString("skipped").orEmpty(),
                lastParts = partsOf(last?.optJSONArray("compose")),
                lastProviderStatus = last?.optJSONObject("providerError")?.optInt("status") ?: 0,
                lastProviderDetail = last?.optJSONObject("providerError")?.optString("detail").orEmpty(),
                cuedEntries = json.optJSONObject("cueCoverage")?.optInt("cued") ?: 0,
                cueEntries = json.optJSONObject("cueCoverage")?.optInt("entries") ?: 0,
                emptyArchive = json.optBoolean("emptyArchive"),
                promptVersion = json.optInt("promptVersion"),
                orphanCount = json.optJSONObject("orphans")?.optInt("count") ?: 0,
                orphanTrashDir = json.optJSONObject("orphans")?.optString("trashDir").orEmpty(),
                orphanBytes = json.optJSONObject("orphans")?.optLong("bytes") ?: 0,
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun memoryRun(
        port: Int,
        chatId: String?,
        maxChats: Int? = null,
        /** Repair the archive itself, not just read new messages: cue words, and whatever the
         *  current prompt asks for and older rows lack. Costs a model call of its own. */
        maintain: Boolean = false,
        /**
         * Which character the named chat belongs to. The tavern names chat files by date, so two
         * characters can own the same name; without this the runtime cannot tell them apart and
         * refuses the request rather than guess.
         */
        characterId: String = "",
        /**
         * Try even though this chat is waiting out a failure. Only a person pressing the button
         * sets it: an unattended pass that forced its way past the wait would pay for the same
         * refused batch every couple of minutes.
         */
        force: Boolean = false,
    ): MemoryRunReport? = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            if (!chatId.isNullOrEmpty()) put("chatId", chatId)
            if (!chatId.isNullOrEmpty() && characterId.isNotBlank()) put("characterId", characterId)
            if (maxChats != null) put("maxChats", maxChats)
            if (maintain) put("maintain", true)
            if (force) put("force", true)
        }.toString()
        // One pass can involve several model calls; the default read timeout would cut it off.
        // Status kept, not collapsed into null: the page has to be able to say "nothing to do"
        // rather than "no answer", and those are the same value once the code is thrown away.
        val (code, raw) = requestWithStatus(
            port,
            "/v1/memory/run",
            method = "POST",
            body = payload,
            timeoutMs = 600_000,
        ) ?: return@withContext null
        if (code !in 200..299) {
            return@withContext runCatching {
                val json = JSONObject(raw)
                MemoryRunReport(
                    processed = 0,
                    skipped = 0,
                    failed = 0,
                    refused = json.optString("error"),
                    refusedDetail = json.optString("detail"),
                )
            }.getOrNull()
        }
        try {
            val json = JSONObject(raw)
            MemoryRunReport(
                processed = json.optInt("processed"),
                skipped = json.optInt("skipped"),
                cuesFilled = json.optInt("cuesFilled"),
                declinedFloors = json.optInt("declinedFloors"),
                failed = json.optInt("failed"),
                remaining = json.optInt("remaining"),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** One stored memory entry of a chat, with the message range it was extracted from. */
    /** One digested stretch of the chat's earlier history, with the floors it covers. */
    data class MemoryArc(
        val text: String,
        val from: Int = -1,
        val to: Int = -1,
    )

    /** One archived chunk summary the rolling digest has already replaced. */
    data class MemorySummary(
        val from: Int = -1,
        val to: Int = -1,
        val text: String = "",
    )

    data class MemoryEntry(
        val id: String,
        val text: String,
        val tags: List<String>,
        val kind: String,
        val from: Int,
        val to: Int,
        val createdAt: Long,
        /** Held still by the reader: a pass may not retire it, and it goes into the prompt first. */
        val pinned: Boolean = false,
        /**
         * How much the slice itself made this row matter: 1 routine detail, 2 a change or a stake,
         * 3 a turning point. The page keeps the 1s behind a fold — a price or a colour answers a
         * lookup, and listing it beside the things that changed the story buries them.
         */
        val salience: Int = 1,
    )

    /** One past change to this chat's memory, as the page lists it. */
    data class MemoryJournalRow(
        val at: Long,
        val action: String,
        val summary: String,
    )

    /** One chat's memory as the page shows it: digest, entries and watermark in one answer. */
    /**
     * One circle of people who keep standing in the same scenes, as the core wrote it down.
     *
     * Computed by the core, not here: the retriever and the map have to agree about who belongs
     * together, and two implementations would drift apart without anything failing.
     */
    data class MemoryCommunity(
        val id: String,
        val members: List<String>,
    )

    data class MemoryCommunities(
        val version: Int,
        val computedAt: Long,
        /** How many people the records named. Zero explains an empty grouping better than "unknown". */
        val people: Int,
        val groups: List<MemoryCommunity>,
    )

    data class MemoryDetail(
        val chatId: String,
        val characterId: String,
        val messageCount: Int,
        val lastIndex: Int,
        val pending: Int,
        /** Rows the current prompt version wrote, and how many came from an earlier one. */
        val currentRows: Int = 0,
        val staleRows: Int = 0,
        /** First floor a rewind would keep; zero means it has to read the chat from the top. */
        val rebuildFrom: Int = 0,
        val restarted: Boolean,
        val lastError: String,
        val failures: Int,
        val retryAt: Long = 0,
        val updatedAt: Long,
        val digest: String,
        val entries: List<MemoryEntry>,
        /** One row per arc: the stretch of history the digest has already moved past. */
        val arcs: List<MemoryArc> = emptyList(),
        /** Chunk summaries the rolling digest has replaced; kept so the reader can still open them. */
        val summaries: List<MemorySummary> = emptyList(),
        /** What is true now, slot by slot, with the floor each value became true. */
        val state: MemoryState? = null,
        /** Turning points with their cause, newest last. */
        val events: List<MemoryEvent> = emptyList(),
        /** One row per stretch of chat: when, where, who and what changed. */
        val scenes: List<MemoryScene> = emptyList(),
        /** One current value per subject and predicate, with the floors it held for. */
        val attributes: List<MemoryAttribute> = emptyList(),
        /** People, places and props derived from the rows above. */
        val entities: List<MemoryEntity> = emptyList(),
        /**
         * Who keeps appearing together, written by the last consolidation pass. Null on an archive
         * that has not been consolidated since circles existed, and the map then falls back to its
         * old order rather than inventing a grouping.
         */
        val communities: MemoryCommunities? = null,
        /** The last few changes, newest first; the top one is what undo takes back. */
        val journal: List<MemoryJournalRow> = emptyList(),
        /** Topics the reader asked to forget; the pass will not write them again. */
        val forgotten: List<String> = emptyList(),
        /** The batch this chat is on right now, or null when it is idle. */
        val progress: MemoryProgress? = null,
        /**
         * The temporary network of the last generated turn: node id to heat. Anchors carry 1,
         * rows reached by association less; the map draws it as "what the model holds now".
         */
        val hot: Map<String, Float> = emptyMap(),
    )

    /** One stretch of chat, the row the timeline and the star map read. */
    data class MemoryScene(
        val from: Int,
        val to: Int,
        val location: String = "",
        val date: String = "",
        val time: String = "",
        val present: List<String> = emptyList(),
        /** Who arrived during the stretch; empty when the cast did not change. */
        val entered: List<String> = emptyList(),
        /** Who walked out before the stretch ended. */
        val left: List<String> = emptyList(),
        val summary: String = "",
        val event: String = "",
        /** One line someone actually said in that stretch, when the slice recorded one. */
        val quote: String = "",
    )

    /** One attribute of one subject, with the floor it started at and the last one it was seen. */
    data class MemoryAttribute(
        val subject: String,
        val predicate: String,
        val value: String,
        val from: Int = -1,
        val to: Int = -1,
        val seen: Int = -1,
        val confidence: Float = 0.8f,
    )

    /** A person, place or prop with how often it appears; derived, never stored twice. */
    data class MemoryEntity(
        val name: String,
        val kind: String,
        val role: String = "",
        val status: String = "",
        val aliases: List<String> = emptyList(),
        val scenes: Int = 0,
        val facts: Int = 0,
        val mentions: Int = 0,
        val firstFrom: Int = -1,
        val lastFrom: Int = -1,
    )

    /** One turning point. The cause is what a summary drops and a "why" turn asks for. */
    data class MemoryEvent(
        val text: String,
        val whenText: String = "",
        val who: List<String> = emptyList(),
        val cause: String = "",
        val effect: String = "",
        /** The floors the turn spans; -1 when the stored row predates the field. */
        val from: Int = -1,
        val to: Int = -1,
    )

    /** One chat turn behind a stored row, for the drill-down. */
    data class SourceTurn(
        val index: Int,
        val speaker: String,
        val text: String,
    )

    /**
     * One recall check: how many quoted turns the excerpt search put back inside its top `limit`.
     * `reason` says why nothing was measured, when nothing was.
     */
    data class ProbeResult(
        val checked: Int,
        val hits: Int,
        val limit: Int,
        val rate: Int,
        val reason: String,
    )

    /**
     * One slot of the current state. `from` stays -1 when the value has no floor (rule ledger).
     * `seen` is the last floor the value was confirmed at: a slot nobody has restated for a long
     * time is still true, but it is the one a reader should check first. `conflict` marks two
     * writers that disagreed at the same floor, where both versions are kept.
     */
    data class MemoryFact(
        val title: String,
        val note: String = "",
        val from: Int = -1,
        val to: Int = -1,
        val seen: Int = -1,
        val conflict: Boolean = false,
    )

    data class MemoryState(
        val source: String = "",
        val location: String = "",
        val time: String = "",
        val present: List<String> = emptyList(),
        val people: List<MemoryFact> = emptyList(),
        val items: List<MemoryFact> = emptyList(),
        val wear: List<MemoryFact> = emptyList(),
        val promises: List<MemoryFact> = emptyList(),
        val relations: List<MemoryFact> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = location.isEmpty() && time.isEmpty() && present.isEmpty() && people.isEmpty() &&
                items.isEmpty() && wear.isEmpty() && promises.isEmpty() && relations.isEmpty()
    }

    private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun arcsOf(array: JSONArray?): List<MemoryArc> {
        if (array == null || array.length() == 0) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val text = row.optString("text").trim()
                if (text.isNotEmpty()) add(MemoryArc(text, row.optInt("from", -1), row.optInt("to", -1)))
            }
        }
    }

    private fun summariesOf(array: JSONArray?): List<MemorySummary> {
        if (array == null || array.length() == 0) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val text = row.optString("text").trim()
                if (text.isNotEmpty()) add(MemorySummary(row.optInt("from", -1), row.optInt("to", -1), text))
            }
        }
    }

    /**
     * One slot group of the current state, each row carrying the floor it became true. Empty
     * groups are dropped: a heading with nothing under it reads as a bug.
     */
    private fun factsOf(
        state: JSONObject,
        key: String,
        title: (JSONObject) -> String,
        note: (JSONObject) -> String,
    ): List<MemoryFact> {
        val array = state.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val name = title(row).trim()
                if (name.isEmpty()) continue
                add(
                    MemoryFact(
                        title = name,
                        note = note(row).trim(),
                        from = row.optInt("from", -1),
                        to = row.optInt("to", -1),
                        seen = row.optInt("seen", -1),
                        conflict = row.optBoolean("conflict", false),
                    ),
                )
            }
        }
    }

    private fun eventsOf(array: JSONArray?): List<AgentClient.MemoryEvent> {
        if (array == null || array.length() == 0) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val text = row.optString("text").trim()
                if (text.isEmpty()) continue
                add(
                    AgentClient.MemoryEvent(
                        text = text,
                        whenText = row.optString("when").trim(),
                        who = buildList {
                            val names = row.optJSONArray("who")
                            for (i in 0 until (names?.length() ?: 0)) {
                                add(names?.optString(i).orEmpty())
                            }
                        },
                        cause = row.optString("cause").trim(),
                        effect = row.optString("effect").trim(),
                        from = row.optInt("from", -1),
                        to = row.optInt("to", -1),
                    ),
                )
            }
        }
    }

    private fun scenesOf(array: JSONArray?): List<MemoryScene> {
        if (array == null || array.length() == 0) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                add(
                    MemoryScene(
                        from = row.optInt("from", -1),
                        to = row.optInt("to", -1),
                        location = row.optString("location"),
                        date = row.optString("date"),
                        time = row.optString("time"),
                        present = buildList {
                            val names = row.optJSONArray("present")
                            for (i in 0 until (names?.length() ?: 0)) add(names?.optString(i).orEmpty())
                        },
                        entered = buildList {
                            val names = row.optJSONArray("entered")
                            for (i in 0 until (names?.length() ?: 0)) add(names?.optString(i).orEmpty())
                        },
                        left = buildList {
                            val names = row.optJSONArray("left")
                            for (i in 0 until (names?.length() ?: 0)) add(names?.optString(i).orEmpty())
                        },
                        summary = row.optString("summary"),
                        event = row.optString("event"),
                        quote = row.optString("quote"),
                    ),
                )
            }
        }
    }

    private fun attributesOf(array: JSONArray?): List<MemoryAttribute> {
        if (array == null || array.length() == 0) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                add(
                    MemoryAttribute(
                        subject = row.optString("subject"),
                        predicate = row.optString("predicate"),
                        value = row.optString("value"),
                        from = row.optInt("from", -1),
                        to = row.optInt("to", -1),
                        seen = row.optInt("seen", -1),
                        confidence = row.optDouble("confidence", 0.8).toFloat(),
                    ),
                )
            }
        }
    }

    /**
     * The stored circles, or null when the archive has none.
     *
     * A version this build does not know is treated as absent: the field's shape may have changed,
     * and guessing at it would put a wrong grouping on screen.
     */
    private fun communitiesOf(json: JSONObject?): MemoryCommunities? {
        if (json == null) return null
        val version = json.optInt("version", 1)
        if (version != 1) return null
        val rows = json.optJSONArray("groups") ?: return null
        val groups = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val members = buildList {
                    val list = row.optJSONArray("members")
                    for (at in 0 until (list?.length() ?: 0)) {
                        val name = list?.optString(at).orEmpty().trim()
                        if (name.isNotEmpty()) add(name)
                    }
                }
                if (members.isNotEmpty()) add(MemoryCommunity(row.optString("id"), members))
            }
        }
        // An empty grouping is an answer, not a missing one: the map says "nothing conclusive yet"
        // instead of "never computed", and stops offering a rebuild that can only repeat itself.
        return MemoryCommunities(version, json.optLong("computedAt"), json.optInt("people"), groups)
    }

    private fun entitiesOf(array: JSONArray?): List<MemoryEntity> {
        if (array == null || array.length() == 0) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                add(
                    MemoryEntity(
                        name = row.optString("name"),
                        kind = row.optString("kind"),
                        role = row.optString("role"),
                        status = row.optString("status"),
                        aliases = buildList {
                            val aliases = row.optJSONArray("aliases")
                            for (i in 0 until (aliases?.length() ?: 0)) add(aliases?.optString(i).orEmpty())
                        },
                        scenes = row.optInt("scenes"),
                        facts = row.optInt("facts"),
                        mentions = row.optInt("mentions"),
                        firstFrom = row.optInt("firstFrom", -1),
                        lastFrom = row.optInt("lastFrom", -1),
                    ),
                )
            }
        }
    }

    private fun journalOf(rows: JSONArray?): List<MemoryJournalRow> {
        val out = mutableListOf<MemoryJournalRow>()
        for (i in 0 until (rows?.length() ?: 0)) {
            val row = rows?.optJSONObject(i) ?: continue
            out += MemoryJournalRow(
                at = row.optLong("at"),
                action = row.optString("action"),
                summary = row.optString("summary"),
            )
        }
        return out
    }

    private fun progressOf(row: JSONObject?): MemoryProgress? {
        if (row == null) return null
        return MemoryProgress(
            chatId = row.optString("chatId"),
            characterId = row.optString("characterId"),
            batch = row.optInt("batch", 1),
            chatIndex = row.optInt("chatIndex", 1),
            chatTotal = row.optInt("chatTotal", 1),
            from = row.optInt("from", -1),
            to = row.optInt("to", -1),
            count = row.optInt("count"),
            restarted = row.optBoolean("restarted"),
            phase = row.optString("phase"),
            at = row.optLong("at"),
        )
    }

    /** The largest messages of the last request, largest first; the runtime already sorted them. */
    private fun partsOf(rows: JSONArray?): List<RequestPart> {
        val parts = buildList {
            for (index in 0 until (rows?.length() ?: 0)) {
                val row = rows?.optJSONObject(index) ?: continue
                val tokens = row.optInt("tokens")
                if (tokens <= 0) continue
                add(
                    RequestPart(
                        role = row.optString("role"),
                        tokens = tokens,
                        chars = row.optInt("chars"),
                        head = row.optString("head"),
                    ),
                )
            }
        }
        return parts
    }

    private fun memoryStateOf(state: JSONObject?): MemoryState? {        if (state == null) return null
        val presentArray = state.optJSONArray("present")
        val present = buildList {
            for (index in 0 until (presentArray?.length() ?: 0)) {
                val row = presentArray?.optJSONObject(index) ?: continue
                val name = row.optString("name").trim()
                if (name.isNotEmpty()) add(name)
            }
        }
        val view = MemoryState(
            source = state.optString("source"),
            location = state.optJSONObject("location")?.optString("text").orEmpty(),
            time = state.optJSONObject("time")?.optString("text").orEmpty(),
            present = present,
            people = factsOf(state, "people", { it.optString("name") }, {
                listOf(it.optString("relation"), it.optString("status"))
                    .filter(String::isNotEmpty)
                    .joinToString("；")
            }),
            items = factsOf(state, "items", { it.optString("name") }, {
                val holder = it.optString("holder")
                if (holder.isEmpty()) "" else "在${holder}身上"
            }),
            wear = factsOf(state, "wear", { it.optString("who") }, { it.optString("outfit") }),
            promises = factsOf(state, "promises", { it.optString("text") }, { it.optString("when") }),
            relations = factsOf(state, "relations", {
                it.optString("a") + "与" + it.optString("b")
            }, {
                it.optString("reading").ifEmpty { it.optString("label") }
            }),
        )
        return if (view.isEmpty) null else view
    }

    /** Reads one chat's stored memory, newest entry first. */
    suspend fun memoryDetail(port: Int, chatId: String, characterId: String): MemoryDetail? =
        withContext(Dispatchers.IO) {
            val raw = request(
                port,
                "/v1/memory/entries?chatId=" + encoded(chatId) +
                    "&characterId=" + encoded(characterId),
            ) ?: return@withContext null
        try {
            val json = JSONObject(raw)
            if (!json.optBoolean("ok", false)) return@withContext null
            val arr = json.optJSONArray("entries") ?: JSONArray()
            val entries = buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        MemoryEntry(
                            id = o.optString("id"),
                            text = o.optString("text"),
                            tags = buildList {
                                val tags = o.optJSONArray("tags")
                                for (t in 0 until (tags?.length() ?: 0)) add(tags?.optString(t).orEmpty())
                            },
                            kind = o.optString("kind", "fact"),
                            from = o.optInt("from", -1),
                            to = o.optInt("to", -1),
                            createdAt = o.optLong("createdAt"),
                            pinned = o.optBoolean("pinned"),
                            salience = o.optInt("salience", 1),
                        ),
                    )
                }
            }.reversed()
            MemoryDetail(
                chatId = json.optString("chatId", chatId),
                characterId = json.optString("characterId"),
                messageCount = json.optInt("messageCount"),
                lastIndex = json.optInt("lastIndex", -1),
                pending = json.optInt("pending"),
                currentRows = json.optInt("currentRows"),
                staleRows = json.optInt("staleRows"),
                rebuildFrom = json.optInt("rebuildFrom"),
                restarted = json.optBoolean("restarted"),
                lastError = json.optString("lastError"),
                failures = json.optInt("failures"),
                retryAt = json.optLong("retryAt"),
                updatedAt = json.optLong("updatedAt"),
                digest = json.optString("digest"),
                entries = entries,
                arcs = arcsOf(json.optJSONArray("arcs")),
                summaries = summariesOf(json.optJSONArray("summaries")),
                state = memoryStateOf(json.optJSONObject("state")),
                events = eventsOf(json.optJSONArray("events")),
                scenes = scenesOf(json.optJSONArray("scenes")),
                attributes = attributesOf(json.optJSONArray("attributes")),
                entities = entitiesOf(json.optJSONArray("entities")),
                communities = communitiesOf(json.optJSONObject("communities")),
                journal = journalOf(json.optJSONArray("journal")),
                forgotten = buildList {
                    val rows = json.optJSONArray("forgotten")
                    for (i in 0 until (rows?.length() ?: 0)) {
                        val topic = rows?.optJSONObject(i)?.optString("topic").orEmpty()
                        if (topic.isNotEmpty()) add(topic)
                    }
                },
                progress = progressOf(json.optJSONObject("progress")),
                hot = buildMap {
                    val rows = json.optJSONObject("hot")
                    if (rows != null) {
                        for (key in rows.keys()) {
                            val value = rows.optDouble(key, 0.0)
                            if (value > 0.0) put(key, value.toFloat())
                        }
                    }
                },
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Removes one entry; the rest of the chat's memory is untouched. */
    suspend fun memoryDeleteEntry(
        port: Int,
        chatId: String,
        characterId: String,
        entryId: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val raw = request(
                port,
                "/v1/memory/entries?chatId=" + encoded(chatId) +
                    "&characterId=" + encoded(characterId) +
                    "&id=" + encoded(entryId),
                method = "DELETE",
            ) ?: return@withContext false
            runCatching { JSONObject(raw).optBoolean("ok") }.getOrDefault(false)
        }

    /**
     * Clears the memory of deleted chats, two ways.
     *
     * Default is the dated folder under the memory root: nothing is deleted and a wrong press can
     * be undone by moving the files back. `purge` deletes them outright. Returns files touched and
     * the folder they went to, or null when the call failed.
     */
    suspend fun memoryCleanOrphans(port: Int, purge: Boolean = false): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("mode", if (purge) "purge" else "trash").toString()
            val raw = request(port, "/v1/memory/orphans/clean", method = "POST", body = payload)
                ?: return@withContext null
            runCatching {
                val json = JSONObject(raw).takeIf { it.optBoolean("ok") } ?: return@runCatching null
                json.optInt("moved") to json.optString("folder")
            }.getOrNull()
        }

    /**
     * Recomputes one chat's circles and stores them, without a model call.
     *
     * The circles are normally written by a consolidation pass, so an archive from before that
     * feature keeps drawing its graph in the old order until the chat happens to be consolidated
     * again. Returns how many circles came out, or null when the call failed.
     */
    suspend fun memoryRebuildCircles(port: Int, chatId: String, characterId: String): Int? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("characterId", characterId)
            }.toString()
            val raw = request(port, "/v1/memory/communities/rebuild", method = "POST", body = payload)
                ?: return@withContext null
            runCatching { JSONObject(raw).takeIf { it.optBoolean("ok") }?.optInt("groups") }.getOrNull()
        }

    /**
     * Turns one chat's memory off or on.
     *
     * Off is a stored flag rather than a deletion: the archive stays, the watermark stays, and
     * switching back on continues from where it stopped instead of rebuilding the chat from
     * scratch. Returns the state the runtime accepted, or null when the call failed.
     */
    suspend fun memorySetDisabled(
        port: Int,
        chatId: String,
        characterId: String,
        disabled: Boolean,
    ): Boolean? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("characterId", characterId)
                put("disabled", disabled)
            }.toString()
            val raw = request(port, "/v1/memory/disabled", method = "POST", body = payload)
                ?: return@withContext null
            runCatching { JSONObject(raw).takeIf { it.optBoolean("ok") }?.optBoolean("disabled") }
                .getOrNull()
        }

    /**
     * Writes a row the reader composed. Returns the id it got, or null when the server turned it
     * down — an empty sentence and a chat that is no longer there both come back that way, and the
     * page has to say which sentence it could not store instead of closing as if it worked.
     */
    suspend fun memoryAddEntry(
        port: Int,
        chatId: String,
        characterId: String,
        text: String,
        cues: List<String>,
    ): String? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("characterId", characterId)
                put("text", text)
                put("cues", JSONArray(cues))
            }.toString()
            val raw = request(port, "/v1/memory/entry", method = "POST", body = payload)
                ?: return@withContext null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
            if (!json.optBoolean("ok")) return@withContext null
            json.optString("id").ifBlank { null }
        }

    /** Holds one row still, or lets it go. A pinned row survives every later pass. */
    /** How much of a chat is vectorised, and whether a run is under way right now. */
    data class WarmStatus(val running: Boolean, val filled: Int, val total: Int)

    /** Starts building one chat's index. Returns as soon as the job is accepted, not when it ends. */
    /**
     * Starts an index build. [force] drops the vectors this chat already has first, which is what
     * the rebuild action is asking for: without it the pass finds every sentence cached
     * and returns in the same tick, so the tap looked like it did nothing.
     */
    suspend fun startMemoryWarm(
        port: Int,
        chatId: String,
        characterId: String,
        force: Boolean = false,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("characterId", characterId)
                if (force) put("force", true)
            }.toString()
            val raw = request(port, "/v1/memory/warm", method = "POST", body = payload)
                ?: return@withContext false
            runCatching { JSONObject(raw).optBoolean("ok") }.getOrDefault(false)
        }

    suspend fun memoryWarmStatus(
        port: Int,
        chatId: String,
        characterId: String,
    ): WarmStatus? =
        withContext(Dispatchers.IO) {
            val raw = request(
                port,
                "/v1/memory/warm?chatId=" + encoded(chatId) +
                    "&characterId=" + encoded(characterId),
            ) ?: return@withContext null
            runCatching {
                val json = JSONObject(raw)
                WarmStatus(
                    running = json.optBoolean("running", false),
                    filled = json.optInt("filled", 0),
                    total = json.optInt("total", 0),
                )
            }.getOrNull()
        }

    /**
     * One memory job the runtime reports as running.
     *
     * `kind` is what the work is (organise, rebuild, index), because the three read differently to
     * whoever started them and the terminal names the command it is running. The batch fields are
     * zero for an index build, which counts sentences instead.
     */
    data class MemoryTask(
        val kind: String,
        val chatId: String,
        val characterId: String,
        val phase: String,
        val batch: Int,
        val from: Int,
        val to: Int,
        val count: Int,
        val done: Int,
        val total: Int,
        val startedAt: Long,
    )

    /** Every memory job in flight, or null when the service cannot answer. */
    suspend fun memoryTasks(port: Int): List<MemoryTask>? = withContext(Dispatchers.IO) {
        val raw = request(port, "/v1/memory/tasks") ?: return@withContext null
        runCatching {
            val array = JSONObject(raw).optJSONArray("tasks") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val row = array.optJSONObject(index) ?: continue
                    add(
                        MemoryTask(
                            kind = row.optString("kind"),
                            chatId = row.optString("chatId"),
                            characterId = row.optString("characterId"),
                            phase = row.optString("phase"),
                            batch = row.optInt("batch"),
                            from = row.optInt("from", -1),
                            to = row.optInt("to", -1),
                            count = row.optInt("count"),
                            done = row.optInt("done"),
                            total = row.optInt("total"),
                            startedAt = row.optLong("startedAt"),
                        ),
                    )
                }
            }
        }.getOrNull()
    }

    /**
     * How many chats have never been indexed, whether a queue is working through them, and how far
     * the chat it is on has got. The last part is what turns the running label into a number the reader can
     * watch: a queue of five chats over twenty minutes otherwise shows nothing at all.
     */
    data class WarmOverview(
        val pending: Int,
        val running: Boolean,
        val queued: Int,
        val currentTitle: String = "",
        val currentFilled: Int = 0,
        val currentTotal: Int = 0,
    )

    suspend fun memoryWarmOverview(port: Int): WarmOverview? =
        withContext(Dispatchers.IO) {
            val raw = request(port, "/v1/memory/warm") ?: return@withContext null
            runCatching {
                val json = JSONObject(raw)
                val chats = json.optJSONArray("chats") ?: JSONArray()
                var pending = 0
                for (index in 0 until chats.length()) {
                    val row = chats.optJSONObject(index) ?: continue
                    if (!row.optBoolean("indexed", true)) pending += 1
                }
                val current = json.optJSONObject("current")
                WarmOverview(
                    pending = pending,
                    running = json.optBoolean("running", false),
                    queued = json.optInt("queued", 0),
                    currentTitle = current?.optString("characterId", "") ?: "",
                    currentFilled = current?.optInt("filled", 0) ?: 0,
                    currentTotal = current?.optInt("total", 0) ?: 0,
                )
            }.getOrNull()
        }

    /** Queues every chat that has no vectors yet. Returns how many went into the queue. */
    suspend fun startMemoryWarmAll(port: Int): Int =
        withContext(Dispatchers.IO) {
            val raw = request(port, "/v1/memory/warm", method = "POST", body = "{\"all\":true}")
                ?: return@withContext 0
            runCatching { JSONObject(raw).optInt("queued", 0) }.getOrDefault(0)
        }

    suspend fun memoryPin(
        port: Int,
        chatId: String,
        characterId: String,
        entryId: String,
        pinned: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("characterId", characterId)
                put("id", entryId)
                put("pinned", pinned)
            }.toString()
            val raw = request(port, "/v1/memory/pin", method = "POST", body = payload)
                ?: return@withContext false
            runCatching { JSONObject(raw).optBoolean("ok") }.getOrDefault(false)
        }

    /**
     * Forgets a topic. Returns how many rows went, or null when nothing matched, so the caller can
     * say which of the two happened instead of reporting a silent success.
     */
    /**
     * Marks one row settled, or puts it back as a hanging question.
     *
     * The pass classifies a row from a single slice; a reader looking at the whole chat knows
     * better. An open row is sent to the model as something still pending, so without this a
     * thread the story already closed keeps asking to be closed again.
     */
    suspend fun memorySettleEntry(
        port: Int,
        chatId: String,
        characterId: String,
        entryId: String,
        open: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("characterId", characterId)
                put("id", entryId)
                put("open", open)
            }.toString()
            val raw = request(port, "/v1/memory/settle", method = "POST", body = payload)
                ?: return@withContext false
            runCatching { JSONObject(raw).optBoolean("ok") }.getOrDefault(false)
        }

    suspend fun memoryForget(
        port: Int,
        chatId: String,
        characterId: String,
        topic: String,
    ): Int? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("characterId", characterId)
                put("topic", topic)
            }.toString()
            val raw = request(port, "/v1/memory/forget", method = "POST", body = payload)
                ?: return@withContext null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
            if (!json.optBoolean("ok")) return@withContext null
            val removed = json.optJSONObject("removed")
            (removed?.optInt("entries") ?: 0) + (removed?.optInt("attributes") ?: 0) +
                (removed?.optInt("events") ?: 0) + (removed?.optInt("scenes") ?: 0) +
                // Slots are what "who is in the room" reads; a forget that leaves one behind has
                // not removed the name from what the model is told.
                (removed?.optInt("units") ?: 0)
        }

    /**
     * What the last undo did: the one-line summary, or the reason there is nothing to say.
     *
     * [stale] is not a failure: the record described an archive that has been rebuilt since, so
     * taking it back is no longer possible. Answering with the nothing-to-undo line would be
     * accurate about the stack and wrong about the reason.
     */
    data class MemoryUndo(val summary: String = "", val stale: Boolean = false)

    /** Takes the last change back. Null when the call itself failed. */
    suspend fun memoryUndo(port: Int, chatId: String, characterId: String): MemoryUndo? =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("chatId", chatId)
                put("characterId", characterId)
            }.toString()
            val raw = request(port, "/v1/memory/undo", method = "POST", body = payload)
                ?: return@withContext null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
            if (json.optString("error") == "stale_undo") {
                return@withContext MemoryUndo(stale = true)
            }
            if (!json.optBoolean("ok")) return@withContext MemoryUndo()
            MemoryUndo(json.optJSONObject("undone")?.optString("summary").orEmpty())
        }

    /** Forgets one chat: entries, digest, state units and watermark all reset. */
    suspend fun memoryClearChat(port: Int, chatId: String, characterId: String): Boolean =
        withContext(Dispatchers.IO) {
            val raw = request(
                port,
                "/v1/memory/chat?chatId=" + encoded(chatId) +
                    "&characterId=" + encoded(characterId),
                method = "DELETE",
            ) ?: return@withContext false
            runCatching { JSONObject(raw).optBoolean("ok") }.getOrDefault(false)
        }

    /**
     * Rewinds one archive and starts the reading that applies the current prompt.
     *
     * Rows the reader wrote or pinned survive; everything the model wrote is derived again from
     * the transcript. The runtime checks the model before it empties anything, so a refusal here
     * is the archive still being intact — which is why the reason matters more than the flag.
     * `null` means the service itself did not answer.
     */
    suspend fun memoryRebuild(port: Int, chatId: String, characterId: String): RebuildOutcome? =
        withContext(Dispatchers.IO) {
            val (code, raw) = requestWithStatus(
                port,
                "/v1/memory/rebuild?chatId=" + encoded(chatId) +
                    "&characterId=" + encoded(characterId),
                method = "POST",
                // The runtime's own check of the model runs before it answers, and a model that
                // has to time out costs a wait. Four seconds cut that check off and reported a
                // refusal that never happened.
                timeoutMs = 15000,
            ) ?: return@withContext null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext null
            RebuildOutcome(
                started = code in 200..299 && json.optBoolean("ok", false),
                detail = json.optString("detail").orEmpty(),
            )
        }

    /** What the runtime said about a rewind: whether it began, and why not when it did not. */
    data class RebuildOutcome(val started: Boolean, val detail: String)

    /**
     * The chat turns behind one stored row. Returns null when the service is down, so the caller
     * can say so instead of showing an empty reader.
     */
    /**
     * One question against the excerpt search, under the same settings the prompt uses: the people
     * on stage and the place the scene stands in decide the boost. Null when the service is down.
     */
    suspend fun memorySearch(
        port: Int,
        chatId: String,
        characterId: String,
        query: String,
    ): MemorySearch? =
        withContext(Dispatchers.IO) {
            val raw = request(
                port,
                "/v1/memory/search?chatId=" + encoded(chatId) +
                    "&characterId=" + encoded(characterId) +
                    "&q=" + encoded(query),
            ) ?: return@withContext null
            try {
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) return@withContext null
                val linesAt = json.optJSONArray("lines") ?: JSONArray()
                val peopleAt = json.optJSONArray("people") ?: JSONArray()
                MemorySearch(
                    lines = buildList { for (i in 0 until linesAt.length()) add(linesAt.optString(i)) },
                    people = buildList { for (i in 0 until peopleAt.length()) add(peopleAt.optString(i)) },
                    place = json.optString("place"),
                )
            } catch (e: Exception) {
                null
            }
        }

    /** The excerpt lines one question reached, and the scene the ranking assumed. */
    data class MemorySearch(
        val lines: List<String>,
        val people: List<String>,
        val place: String,
    )

    /**
     * Quote a few turns of this chat and count how many the excerpt search finds again. Runs in
     * the core: no model call, no network, nothing written. Null when the service is down.
     */
    suspend fun memoryProbe(
        port: Int,
        chatId: String,
        characterId: String,
        count: Int = 20,
    ): ProbeResult? =
        withContext(Dispatchers.IO) {
            val raw = request(
                port,
                "/v1/memory/probe?chatId=" + encoded(chatId) +
                    "&characterId=" + encoded(characterId) +
                    "&count=" + count,
            ) ?: return@withContext null
            try {
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) return@withContext null
                ProbeResult(
                    checked = json.optInt("checked"),
                    hits = json.optInt("hits"),
                    limit = json.optInt("limit", 6),
                    rate = json.optInt("rate"),
                    reason = json.optString("reason"),
                )
            } catch (e: Exception) {
                null
            }
        }

    suspend fun memorySources(
        port: Int,
        chatId: String,
        characterId: String,
        from: Int,
        to: Int,
    ): List<SourceTurn>? =
        withContext(Dispatchers.IO) {
            val raw = request(
                port,
                "/v1/memory/sources?chatId=" + encoded(chatId) +
                    "&characterId=" + encoded(characterId) +
                    "&from=" + from +
                    "&to=" + to,
            ) ?: return@withContext null
            try {
                val json = JSONObject(raw)
                if (!json.optBoolean("ok", false)) return@withContext null
                val arr = json.optJSONArray("messages") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val row = arr.getJSONObject(i)
                        add(
                            SourceTurn(
                                index = row.optInt("index"),
                                speaker = row.optString("speaker"),
                                text = row.optString("text"),
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
}
