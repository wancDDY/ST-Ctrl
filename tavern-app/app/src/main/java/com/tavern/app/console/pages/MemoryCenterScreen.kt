package com.tavern.app.console.pages

import com.tavern.app.util.AppToast
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tavern.app.console.ExtensionsTabRequest
import com.tavern.app.console.MemoryEmbeddingConfig
import com.tavern.app.console.MemoryEmbeddingPreferences
import com.tavern.app.console.MemoryEnhancementConfig
import com.tavern.app.console.MemoryEnhancementPreferences
import com.tavern.app.console.MemoryScanState
import com.tavern.app.console.MemoryTaskState
import com.tavern.app.console.memoryTaskProgressText
import com.tavern.app.console.SettingsState
import com.tavern.app.console.components.ConfirmDialog
import com.tavern.app.console.components.DialogDismissButton
import com.tavern.app.console.components.ListGroup
import com.tavern.app.console.components.ListRow
import com.tavern.app.console.components.SoftActionButton
import com.tavern.app.console.components.TrailingChevron
import com.tavern.app.console.components.appSwitchColors
import com.tavern.app.console.components.appTextFieldColors
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.LocalAppColors
import com.tavern.app.console.theme.LocalFluentMotion
import com.tavern.app.console.theme.enterExpand
import com.tavern.app.console.theme.exitShrink
import com.tavern.app.node.NodeState
import com.tavern.app.update.TavernCoreCapabilities
import com.tavern.app.util.AssetExtractor
import com.tavern.app.util.JsonUtil
import com.tavern.app.webview.MemoryDrainBridge
import com.tavern.app.webview.MemoryDrainState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppDimens

// A scan page covers 200 chats and can run for minutes; a transport-level failure
// on one page says nothing about the data, so it is retried before the run is
// reported as failed.
private const val ScanRetries = 3
private const val ScanRetryDelayMs = 1_500L

private enum class MemoryCenterPage(val label: String) {
    OVERVIEW("概览"),
    STORY("故事"),
    PEOPLE("人物"),
    PLACES("物品地点"),
    BOOK("记忆本"),
    SETTINGS("设置"),
}

private data class MemoryCenterScope(
    val scopeKey: String,
    val chatId: String,
    val branchId: String,
    val kind: String,
    val messageCount: Int,
    val factCount: Int,
    val relationshipCount: Int,
    val sceneCount: Int,
    val summaryCount: Int,
    val updatedAt: Long,
    // The engine reports these so the list can explain entries ST itself cannot open:
    // a superseded swipe branch, and a chat whose character card was deleted.
    val current: Boolean,
    val missingCard: Boolean,
)

private data class MemoryCenterOverview(
    val messages: Int,
    val facts: Int,
    val relationships: Int,
    val scenes: Int,
    val summaries: Int,
    val memoryEntries: Int,
    val entities: Int,
    val sceneLocation: String,
    val sceneDate: String,
    val sceneTime: String,
    val participants: List<String>,
    val recentSummaries: List<MemoryCenterSummary>,
    val recentMessages: List<MemoryCenterMessage>,
)

private data class MemoryCenterStructured(
    val sceneLocation: String,
    val sceneDate: String,
    val sceneTime: String,
    val participants: List<String>,
    val facts: List<MemoryCenterFact>,
    val relationships: List<MemoryCenterRelationship>,
    val summaries: List<MemoryCenterSummary>,
    val entities: List<MemoryCenterEntity>,
    val scenes: List<MemoryCenterScene>,
)

private data class MemoryCenterEntity(
    val canonicalName: String,
    val entityType: String,
    val aliases: List<String>,
)

private data class MemoryCenterScene(
    val location: String,
    val date: String,
    val time: String,
    val participants: List<String>,
    val startSeq: Int,
    val endSeq: Int?,
)

internal data class MemoryCenterFact(
    val subject: String,
    val predicate: String,
    val value: String,
)

private data class MemoryCenterRelationship(
    val subject: String,
    val predicate: String,
    val objectValue: String,
)

internal data class MemoryCenterSummary(
    val level: String,
    val startSeq: Int,
    val endSeq: Int,
    val text: String,
)

private data class MemoryCenterBookEntry(
    val entryUid: String?,
    val category: String,
    val title: String,
    val content: String,
    val importance: Int,
    val pinned: Boolean,
)

private data class MemoryCenterBookRevision(
    val id: Int,
    val changeType: String,
    val title: String,
    val content: String,
    val changedAt: Long,
)

private data class MemoryCenterMessage(
    val messageUid: String,
    val seq: Int,
    val role: String,
    val speaker: String,
    val body: String,
)

private data class MemoryCenterTestResult(
    val query: String,
    val text: String,
    val tokenEstimate: Int,
    val sourceCount: Int,
    val retrievedMessages: Int = 0,
    val retrievedEvidence: Int = 0,
)

private data class MemoryCenterAiConfig(
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    // Off by default. The extraction channel already runs on the tavern's own API, so asking the
    // reader to configure a second provider before the memory book can fill up is the wrong order.
    val useForAnalysis: Boolean = false,
)

private data class MemoryCenterEmbeddingStatus(
    val enabled: Boolean = false,
    // Which path the engine is on: off, the local vectors, or the reader's service. Reported by
    // the engine so the page shows what is running rather than what it last saved.
    val tier: String = "off",
    // Named in the status line: a reader who filled two services needs to see which one answered.
    val model: String = "",
    val total: Int = 0,
    val indexed: Int = 0,
    val missing: Int = 0,
    val dimensions: Int? = null,
)

private object MemoryCenterAiPreferences {
    private const val PREFS = "stctrl_memory_ai"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_USE_FOR_ANALYSIS = "use_for_analysis"

    fun load(context: Context): MemoryCenterAiConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return MemoryCenterAiConfig(
            endpoint = prefs.getString(KEY_ENDPOINT, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, "").orEmpty(),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            useForAnalysis = prefs.getBoolean(KEY_USE_FOR_ANALYSIS, false),
        )
    }

    fun save(context: Context, config: MemoryCenterAiConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENDPOINT, config.endpoint.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putBoolean(KEY_USE_FOR_ANALYSIS, config.useForAnalysis)
            .apply()
    }
}

private class MemoryCenterApi(private val port: Int) {
    // The tavern validates every state-changing request against a token kept in a signed
    // session cookie, and this client is not the page that owns that session: it has to
    // ask /csrf-token for the pair and send both back. Without them the GET endpoints
    // keep answering while every POST is refused.
    private var csrfToken: String? = null
    private var csrfSeen = false
    private var sessionCookie: String? = null
    fun scopes(): List<MemoryCenterScope> {
        val root = request("/api/stctrl-memory/scopes", "GET", null)
        val items = root.optJSONArray("items") ?: JSONArray()
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            MemoryCenterScope(
                scopeKey = item.optString("scopeKey"),
                chatId = item.optString("chatId"),
                branchId = item.optString("branchId"),
                kind = item.optString("kind", "solo"),
                messageCount = item.optInt("messageCount", 0),
                factCount = item.optInt("factCount", 0),
                relationshipCount = item.optInt("relationshipCount", 0),
                sceneCount = item.optInt("sceneCount", 0),
                summaryCount = item.optInt("summaryCount", 0),
                updatedAt = item.optLong("updatedAt", 0L),
                // Missing flags default to the safe reading: show the row.
                current = item.optBoolean("current", true),
                missingCard = item.optBoolean("missingCard", false),
            )
        }
    }

    fun overview(scope: MemoryCenterScope): MemoryCenterOverview {
        val root = request("/api/stctrl-memory/overview", "POST", scopeRequest(scope))
        val item = root.optJSONObject("item") ?: JSONObject()
        val counts = item.optJSONObject("counts") ?: JSONObject()
        val scene = item.optJSONObject("scene")
        val participants = scene?.optJSONArray("participants") ?: JSONArray()
        return MemoryCenterOverview(
            messages = counts.optInt("messages", 0),
            facts = counts.optInt("facts", 0),
            relationships = counts.optInt("relationships", 0),
            scenes = counts.optInt("scenes", 0),
            summaries = counts.optInt("summaries", 0),
            memoryEntries = counts.optInt("memoryEntries", 0),
            entities = counts.optInt("entities", 0),
            sceneLocation = scene?.optString("location").orEmpty(),
            sceneDate = scene?.optJSONObject("metadata")?.optString("date").orEmpty(),
            sceneTime = scene?.optJSONObject("metadata")?.optString("time").orEmpty(),
            participants = List(participants.length()) { index ->
                participants.optString(index)
            }.filter { it.isNotBlank() },
            recentSummaries = parseSummaries(item.optJSONArray("recentSummaries")),
            recentMessages = parseMessages(item.optJSONArray("recentMessages")),
        )
    }

    fun structured(scope: MemoryCenterScope): MemoryCenterStructured {
        val root = request("/api/stctrl-memory/structured", "POST", scopeRequest(scope))
        val scene = root.optJSONObject("scene")
        val participants = root.optJSONArray("participants") ?: JSONArray()
        val facts = root.optJSONArray("facts") ?: JSONArray()
        val relationships = root.optJSONArray("relationships") ?: JSONArray()
        return MemoryCenterStructured(
            sceneLocation = scene?.optString("location").orEmpty(),
            sceneDate = scene?.optJSONObject("metadata")?.optString("date").orEmpty(),
            sceneTime = scene?.optJSONObject("metadata")?.optString("time").orEmpty(),
            participants = List(participants.length()) { index ->
                participants.getJSONObject(index).optString("entityName")
            }.filter { it.isNotBlank() },
            facts = List(facts.length()) { index ->
                val item = facts.getJSONObject(index)
                MemoryCenterFact(
                    subject = item.optString("subject"),
                    predicate = item.optString("predicate"),
                    value = item.optString("value"),
                )
            },
            relationships = List(relationships.length()) { index ->
                val item = relationships.getJSONObject(index)
                MemoryCenterRelationship(
                    subject = item.optString("subject"),
                    predicate = item.optString("predicate"),
                    objectValue = item.optString("object"),
                )
            },
            summaries = parseSummaries(root.optJSONArray("summaries")),
            entities = List((root.optJSONArray("entities") ?: JSONArray()).length()) { index ->
                val item = root.optJSONArray("entities")!!.getJSONObject(index)
                val aliases = item.optJSONArray("aliases") ?: JSONArray()
                MemoryCenterEntity(
                    canonicalName = item.optString("canonicalName"),
                    entityType = item.optString("entityType", "character"),
                    aliases = List(aliases.length()) { aliasIndex ->
                        aliases.optString(aliasIndex)
                    }.filter { it.isNotBlank() },
                )
            },
            scenes = List((root.optJSONArray("scenes") ?: JSONArray()).length()) { index ->
                val item = root.optJSONArray("scenes")!!.getJSONObject(index)
                val metadata = item.optJSONObject("metadata") ?: JSONObject()
                val sceneParticipants = item.optJSONArray("participants") ?: JSONArray()
                MemoryCenterScene(
                    location = item.optString("location"),
                    date = metadata.optString("date"),
                    time = metadata.optString("time"),
                    participants = List(sceneParticipants.length()) { participantIndex ->
                        sceneParticipants.optString(participantIndex)
                    }.filter { it.isNotBlank() },
                    startSeq = item.optInt("startSeq", 0),
                    endSeq = item.optInt("endSeq", -1).takeIf { it >= 0 },
                )
            },
        )
    }

    fun book(scope: MemoryCenterScope): List<MemoryCenterBookEntry> {
        val root = request("/api/stctrl-memory/book", "POST", scopeRequest(scope))
        val items = root.optJSONArray("items") ?: JSONArray()
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            MemoryCenterBookEntry(
                entryUid = item.optString("entryUid").takeIf { it.isNotBlank() },
                category = item.optString("category", "note"),
                title = item.optString("title"),
                content = item.optString("content"),
                importance = item.optInt("importance", 3),
                pinned = item.optBoolean("pinned", false),
            )
        }
    }

    fun saveBook(scope: MemoryCenterScope, entry: MemoryCenterBookEntry): MemoryCenterBookEntry {
        val body = scopeRequest(scope)
        body.put("entry", JSONObject().apply {
            entry.entryUid?.let { put("entryUid", it) }
            put("category", entry.category)
            put("title", entry.title)
            put("content", entry.content)
            put("importance", entry.importance)
            put("pinned", entry.pinned)
        })
        val root = request("/api/stctrl-memory/book/upsert", "POST", body)
        val item = root.getJSONObject("item")
        return MemoryCenterBookEntry(
            entryUid = item.optString("entryUid"),
            category = item.optString("category", entry.category),
            title = item.optString("title", entry.title),
            content = item.optString("content", entry.content),
            importance = item.optInt("importance", entry.importance),
            pinned = item.optBoolean("pinned", entry.pinned),
        )
    }

    fun deleteBook(scope: MemoryCenterScope, entryUid: String) {
        val body = scopeRequest(scope)
        body.put("entryUid", entryUid)
        request("/api/stctrl-memory/book/delete", "POST", body)
    }

    fun history(scope: MemoryCenterScope, entryUid: String): List<MemoryCenterBookRevision> {
        val body = scopeRequest(scope)
        body.put("entryUid", entryUid)
        val root = request("/api/stctrl-memory/book/history", "POST", body)
        val items = root.optJSONArray("items") ?: JSONArray()
        return List(items.length()) { index ->
            val item = items.getJSONObject(index)
            MemoryCenterBookRevision(
                id = item.optInt("id"),
                changeType = item.optString("changeType"),
                title = item.optString("title"),
                content = item.optString("content"),
                changedAt = item.optLong("changedAt", 0L),
            )
        }
    }

    fun restoreBook(scope: MemoryCenterScope, entryUid: String, historyId: Int) {
        val body = scopeRequest(scope)
        body.put("entryUid", entryUid)
        body.put("historyId", historyId)
        request("/api/stctrl-memory/book/restore", "POST", body)
    }

    fun configureEmbedding(config: MemoryEmbeddingConfig): MemoryCenterEmbeddingStatus {
        val root = request(
            "/api/stctrl-memory/embeddings/config",
            "POST",
            JSONObject().put("provider", embeddingProviderRequest(config)),
        )
        return parseEmbeddingStatus(root)
    }

    fun testEmbedding(config: MemoryEmbeddingConfig): Int {
        val root = request(
            "/api/stctrl-memory/embeddings/test",
            "POST",
            JSONObject().put("provider", embeddingProviderRequest(config)),
            30_000,
        )
        return root.optInt("dimensions", 0)
    }

    fun embeddingStatus(
        scope: MemoryCenterScope,
        config: MemoryEmbeddingConfig,
    ): MemoryCenterEmbeddingStatus {
        val body = scopeRequest(scope)
        body.put("provider", embeddingProviderRequest(config))
        val root = request("/api/stctrl-memory/embeddings/status", "POST", body)
        return parseEmbeddingStatus(root)
    }

    fun indexEmbedding(
        scope: MemoryCenterScope,
        config: MemoryEmbeddingConfig,
        maxBatches: Int = 4,
        rebuild: Boolean = false,
    ): JSONObject {
        val body = scopeRequest(scope)
        body.put("provider", embeddingProviderRequest(config))
        body.put("maxBatches", maxBatches)
        body.put("rebuild", rebuild)
        return request(
            "/api/stctrl-memory/embeddings/index",
            "POST",
            body,
            180_000,
        )
    }

    private fun embeddingProviderRequest(config: MemoryEmbeddingConfig): JSONObject {
        return JSONObject().apply {
            // The engine is told about the service only when it is usable. The switch on its own
            // is sent separately: an address nobody filled in still means local vectors.
            put("enabled", config.providerEnabled)
            if (config.providerEnabled) {
                put("endpoint", config.endpoint.trim())
                put("model", config.model.trim())
                put("autoIndex", config.autoIndex)
                if (config.apiKey.isNotBlank()) {
                    put("apiKey", config.apiKey.trim())
                }
            }
        }
    }

    fun configureEnhancement(config: MemoryEnhancementConfig): JSONObject {
        val root = JSONObject().apply {
            put("rewriteProvider", enhancementProviderRequest(
                enabled = config.rewriteProviderEnabled,
                endpoint = config.rewriteEndpoint,
                model = config.rewriteModel,
                apiKey = config.rewriteApiKey,
            ))
            put("rerankProvider", enhancementProviderRequest(
                enabled = config.rerankProviderEnabled,
                endpoint = config.rerankEndpoint,
                model = config.rerankModel,
                apiKey = config.rerankApiKey,
            ))
        }
        return request("/api/stctrl-memory/enhancement/config", "POST", root)
    }

    // Each switch is sent on its own: the engine writes only the fields that arrive, so flipping
    // the vector switch must not move the rerank one.
    fun updateRetrievalModes(
        vector: Boolean? = null,
        rewrite: Boolean? = null,
        rerank: Boolean? = null,
    ): JSONObject {
        val retrieval = JSONObject().apply {
            vector?.let { put("vector", it) }
            rewrite?.let { put("rewrite", it) }
            rerank?.let { put("rerank", it) }
        }
        return request(
            "/api/stctrl-memory/extract/settings/update",
            "POST",
            JSONObject().put("retrieval", retrieval),
        )
    }

    private fun enhancementProviderRequest(
        enabled: Boolean,
        endpoint: String,
        model: String,
        apiKey: String,
    ): JSONObject {
        return JSONObject().apply {
            put("enabled", enabled)
            if (enabled) {
                put("endpoint", endpoint.trim())
                put("model", model.trim())
                if (apiKey.isNotBlank()) {
                    put("apiKey", apiKey.trim())
                }
            }
        }
    }

    private fun parseEmbeddingStatus(root: JSONObject): MemoryCenterEmbeddingStatus {
        val provider = root.optJSONObject("provider") ?: JSONObject()
        val stats = root.optJSONObject("stats") ?: JSONObject()
        return MemoryCenterEmbeddingStatus(
            enabled = provider.optBoolean("enabled", false),
            tier = root.optString("tier").ifBlank { "off" },
            model = provider.optString("model").orEmpty(),
            total = stats.optInt("total", 0),
            indexed = stats.optInt("indexed", 0),
            missing = stats.optInt("missing", 0),
            dimensions = stats.optInt("dimensions", 0)
                .takeIf { it > 0 },
        )
    }

    fun test(scope: MemoryCenterScope, query: String): MemoryCenterTestResult {
        val body = scopeRequest(scope)
        body.put("query", query)
        // No budget is sent: the route falls back to the budget the last generation used, so
        // this panel assembles the block that will really be injected rather than one built
        // for a fixed 1600 characters. Recent messages stay excluded for the same reason.
        body.put("excludeRecent", true)
        val root = request("/api/stctrl-memory/context", "POST", body)
        // The engine reports hits apart from the sections it always injects. Without
        // them the count was the trace length, so the number shown said nothing about
        // whether the query matched anything at all.
        val retrieved = root.optJSONObject("retrieved")
        val messages = retrieved?.optInt("messages", 0) ?: 0
        val evidence = retrieved?.optInt("evidence", 0) ?: 0
        return MemoryCenterTestResult(
            query = query,
            text = root.optString("text"),
            tokenEstimate = root.optInt("tokenEstimate", 0),
            sourceCount = if (retrieved != null) messages + evidence else root.optJSONArray("trace")?.length() ?: 0,
            retrievedMessages = messages,
            retrievedEvidence = evidence,
        )
    }

    fun searchMessages(scope: MemoryCenterScope, query: String, limit: Int = 80): List<MemoryCenterMessage> {
        val body = scopeRequest(scope)
        if (query.isNotBlank()) body.put("query", query)
        body.put("limit", limit)
        body.put("offset", 0)
        val root = request("/api/stctrl-memory/messages", "POST", body)
        return parseMessages(root.optJSONArray("items"))
    }

    // Whether every reply spends the user's tokens on an extraction pass. Stored by the engine so
    // the page hook can ask on each reply, and so the setting survives a page reload.
    fun extractionSettings(): JSONObject =
        request("/api/stctrl-memory/extract/settings", "POST", JSONObject())

    fun updateExtractionSettings(autoExtract: Boolean): JSONObject {
        val body = JSONObject().apply { put("autoExtract", autoExtract) }
        return request("/api/stctrl-memory/extract/settings/update", "POST", body)
    }

    // The engine appends this to the system prompt of every call that asks a model to read or
    // find something, so the reader settles it once instead of editing prompts per call site.
    fun updateExtraPrompt(text: String): JSONObject {
        val body = JSONObject().apply { put("extraPrompt", text) }
        return request("/api/stctrl-memory/extract/settings/update", "POST", body)
    }

    // Whether the passage handed to a model has its explicit wording replaced first. The chat
    // itself is never touched: only the copy that leaves the device.
    fun updateRedaction(enabled: Boolean): JSONObject {
        val body = JSONObject().apply { put("redact", enabled) }
        return request("/api/stctrl-memory/extract/settings/update", "POST", body)
    }

    // The pass a configured provider would run, without the provider: what the engine can build
    // on its own (who spoke, the local summary) is filed instead of leaving the range pending.
    fun extractLocal(scope: MemoryCenterScope): JSONObject =
        request("/api/stctrl-memory/extract/local", "POST", scopeRequest(scope), 120_000)

    fun analyze(scope: MemoryCenterScope, config: MemoryCenterAiConfig, rebuild: Boolean = false): JSONObject {
        val body = scopeRequest(scope)
        // No batch size: the engine's own value is the one that was measured against a reasoning
        // model's hidden thinking, and a bigger batch here is what runs the output allowance out
        // before the answer starts.
        body.put("rebuild", rebuild)
        if (config.endpoint.isNotBlank() && config.model.isNotBlank()) {
            body.put("provider", JSONObject().apply {
                put("endpoint", config.endpoint.trim())
                put("model", config.model.trim())
                if (config.apiKey.isNotBlank()) put("apiKey", config.apiKey.trim())
            })
        }
        return request("/api/stctrl-memory/analyze", "POST", body, 180_000)
    }

    // The tavern already knows how to ask a provider for its model list and holds the key it was
    // configured with, so the console reuses that endpoint instead of opening a second path to
    // the same address.
    fun probeModels(endpoint: String, apiKey: String): List<String> {
        val body = JSONObject().apply {
            put("chat_completion_source", "openai")
            put("reverse_proxy", endpoint.trim())
            put("proxy_password", apiKey.trim())
        }
        return memoryProbeModels(
            request("/api/backends/chat-completions/status", "POST", body),
        )
    }

    fun scan(offset: Int, limit: Int = 200): JSONObject {
        val body = JSONObject().apply {
            put("offset", offset)
            put("limit", limit)
        }
        return request("/api/stctrl-memory/scan", "POST", body, 300_000)
    }

    fun deleteScope(scope: MemoryCenterScope) {
        request("/api/stctrl-memory/scope/delete", "POST", scopeRequest(scope))
    }

    fun clearAll() {
        request("/api/stctrl-memory/clear", "POST", JSONObject())
    }

    fun diagnostics(): JSONObject {
        return request("/api/stctrl-memory/diagnostics", "GET", null)
    }

    fun writeFact(scope: MemoryCenterScope, subject: String, predicate: String, value: String): JSONObject {
        val body = scopeRequest(scope)
        body.put("subject", subject)
        body.put("predicate", predicate)
        body.put("value", value)
        return request("/api/stctrl-memory/fact/upsert", "POST", body)
    }

    fun purgeTopic(scope: MemoryCenterScope, topic: String): JSONObject {
        val body = scopeRequest(scope)
        body.put("topic", topic)
        return request("/api/stctrl-memory/topic/purge", "POST", body)
    }

    fun forgottenTopics(scope: MemoryCenterScope): List<String> {
        val root = request("/api/stctrl-memory/topics", "POST", scopeRequest(scope))
        return memoryCenterTopicList(root.optJSONArray("topics"))
    }

    fun restoreTopic(scope: MemoryCenterScope, topic: String): List<String> {
        val body = scopeRequest(scope)
        body.put("topic", topic)
        val root = request("/api/stctrl-memory/topic/restore", "POST", body)
        return memoryCenterTopicList(root.optJSONArray("topics"))
    }

    // The only call that changes a generation parameter, and only because the reader flipped
    // the session override switch themselves.
    fun setSamplingOverride(override: JSONObject?): JSONObject {
        val body = JSONObject()
        if (override != null && override.length() > 0) {
            body.put("override", override)
        } else {
            body.put("override", JSONObject.NULL)
        }
        return request("/api/stctrl-memory/sampling/override", "POST", body)
    }

    private fun scopeRequest(scope: MemoryCenterScope): JSONObject = JSONObject().apply {
        put("chat", JSONObject().apply {
            put("kind", scope.kind)
            put("scopeId", scope.chatId)
        })
    }

    private fun parseSummaries(array: JSONArray?): List<MemoryCenterSummary> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            MemoryCenterSummary(
                level = item.optString("level"),
                startSeq = item.optInt("startSeq", 0),
                endSeq = item.optInt("endSeq", 0),
                text = item.optString("text"),
            )
        }
    }

    private fun parseMessages(array: JSONArray?): List<MemoryCenterMessage> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            MemoryCenterMessage(
                messageUid = item.optString("messageUid"),
                seq = item.optInt("seq", 0),
                role = item.optString("role"),
                speaker = item.optString("speaker"),
                body = item.optString("body"),
            )
        }
    }

    /**
     * The token and the session cookie it is bound to. Both are replaced when the session
     * expires, so the cookie of every response is kept as well.
     */
    private fun refreshCsrf() {
        val connection = URL("http://127.0.0.1:$port/csrf-token").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 4_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status ${text.take(180)}")
            }
            csrfSeen = true
            // "disabled" is what the endpoint answers when the core runs without CSRF
            // protection; nothing is expected in the header then.
            csrfToken = JSONObject(text).optString("token")
                .takeIf { it.isNotBlank() && it != "disabled" }
            sessionCookies(connection)?.let { sessionCookie = it }
        } finally {
            connection.disconnect()
        }
    }

    /** The cookies a response set, joined into one outgoing Cookie header value. */
    private fun sessionCookies(connection: HttpURLConnection): String? {
        val pairs = connection.headerFields.entries
            .filter { (name, _) -> name?.equals("Set-Cookie", ignoreCase = true) == true }
            .flatMap { it.value.orEmpty() }
            .map { it.substringBefore(';').trim() }
            .filter { it.contains('=') }
        return pairs.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun request(
        path: String,
        method: String,
        body: JSONObject?,
        readTimeoutMs: Int = 30_000,
    ): JSONObject {
        // A refused request is retried once with a fresh pair: a token can outlive the
        // session it was issued for, which is the one failure worth another attempt.
        repeat(2) { attempt ->
            if (method != "GET" && !csrfSeen) refreshCsrf()
            val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = 4_000
                connection.readTimeout = readTimeoutMs
                connection.setRequestProperty("Accept", "application/json")
                sessionCookie?.let { connection.setRequestProperty("Cookie", it) }
                if (method != "GET") {
                    csrfToken?.let { connection.setRequestProperty("X-CSRF-Token", it) }
                }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                sessionCookies(connection)?.let { sessionCookie = it }
                if (status in 200..299) {
                    return if (text.isBlank()) JSONObject() else JSONObject(text)
                }
                if (status == 403 && attempt == 0) {
                    csrfSeen = false
                    csrfToken = null
                } else {
                    throw IllegalStateException("HTTP $status ${text.take(180)}")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("HTTP 403 请求被拒绝")
    }
}

// The page reports why a run stopped instead of throwing, so each reason is turned into something
// the reader can act on rather than a code.
internal fun memoryDrainMessage(state: MemoryDrainState): String = when (state.reason) {
    "" -> if (state.processed == 0) "没有新的聊天需要整理" else "已整理 ${state.processed} 条消息"
    "no-page" -> "整理要用核心已配置的 API，先从控制台进入一次核心页面再回来。"
    "core-too-old" -> "内置核心版本过旧，无法用它已有的 API 整理。请更新内置核心。"
    "page-reloaded" -> "核心页面被刷新，整理已中断；已完成的批次会保留，可以再点一次继续。"
    "timeout" -> "整理超时，已完成的批次会保留；可以再点一次继续。"
    "empty-generation" -> "模型没有返回内容，请检查核心里配置的模型是否可用。"
    "no-chat" -> "当前没有打开聊天，无法整理。"
    "prepare-failed" -> "核心无法准备这次整理，可以查看核心日志。"
    "commit-failed" -> "整理结果无法写入记忆库，已完成的批次会保留。"
    else -> "整理失败：${state.reason}"
}

/**
 * The model ids a probe answer carries, or a failure the screen can show.
 *
 * The core answers a probe it could not complete — address unreachable, key rejected, address not
 * a URL at all — with HTTP 200 and an `error` flag, so an unusable address and a provider that
 * published no models produce the same empty body. Only the flag separates them, and reading past
 * it reported a broken address as a working one.
 */
internal fun memoryProbeModels(root: JSONObject): List<String> {
    if (root.optBoolean("error", false)) {
        throw IllegalStateException("连接失败：地址不可达或凭据被拒绝，请检查服务地址与 API Key")
    }
    val data = root.optJSONArray("data") ?: JSONArray()
    return List(data.length()) { index ->
        val item = data.optJSONObject(index) ?: JSONObject()
        item.optString("id").ifBlank { item.optString("name") }
    }.filter { it.isNotBlank() }.distinct().sorted()
}

// The four switches below each choose between a local implementation and a service. Saying which
// one is running is the whole point of the line they print, so the wording lives here rather than
// in four places that drift apart. A service is named by its model: "the service above" tells a
// reader nothing about what is actually being called.
internal fun memoryAnalysisTierText(
    useForAnalysis: Boolean,
    endpoint: String,
    model: String,
): String = when {
    !useForAnalysis -> "当前：核心已配置的模型"
    endpoint.isBlank() || model.isBlank() -> "当前：模型服务未填全，仍用核心已配置的模型"
    else -> "当前：模型服务 $model"
}

internal fun memoryRewriteTierText(
    enabled: Boolean,
    endpoint: String,
    model: String,
): String = when {
    !enabled -> "当前：不改写查询"
    endpoint.isBlank() || model.isBlank() -> "当前：本地改写（切词、同义与指代）"
    else -> "当前：模型服务 $model"
}

internal fun memoryRerankTierText(
    enabled: Boolean,
    endpoint: String,
    model: String,
): String = when {
    !enabled -> "当前：保持检索顺序"
    endpoint.isBlank() || model.isBlank() -> "当前：本地重排"
    else -> "当前：重排服务 $model"
}

internal fun memoryVectorTierText(
    tier: String,
    model: String,
    indexed: Int,
    total: Int,
    dimensions: Int?,
): String = when (tier) {
    "off" -> "当前：不使用向量"
    "model" -> {
        val counts = "已索引 $indexed / $total 条" +
            (dimensions?.let { "，$it 维" } ?: "")
        // With a service in use the local vectors are out of the picture, so an empty index
        // means this layer contributes nothing until it is built.
        val empty = if (indexed == 0) "，索引为空，请先构建" else ""
        val name = model.ifBlank { "已填写" }
        "当前：向量服务 $name（$counts$empty）"
    }
    else -> "当前：设备本地向量"
}

/**
 * Starts the memory pass for one chat. The settings page's button and the header circle both run
 * this, so which API the pass uses stays a setting rather than a property of whichever control
 * happened to be pressed. Progress is published through [MemoryTaskState] because the pass
 * outlives any one screen.
 */
private fun startMemoryPass(
    scope: MemoryCenterScope,
    api: MemoryCenterApi,
    context: Context,
) {
    val chatTitle = memoryCenterChatTitle(scope)
    val config = MemoryCenterAiPreferences.load(context)
    // An incomplete address is not a reason to refuse the pass: the core already holds a model,
    // and the reported result says which one ran. Only a usable address switches the pass over.
    val auxiliaryUsable = config.useForAnalysis
        && config.endpoint.isNotBlank()
        && config.model.isNotBlank()
    MemoryTaskState.start(scope.scopeKey, chatTitle) { onProgress ->
        if (auxiliaryUsable) {
            var processed = 0
            var batches = 0
            var skeletonBatches = 0
            while (true) {
                val result = api.analyze(scope, config)
                val count = result.optInt("processed", 0)
                if (count <= 0) break
                if (result.optString("mode") == "local-skeleton") skeletonBatches += 1
                processed += count
                batches += 1
                onProgress(batches, processed)
            }
            MemoryTaskState.Finished(
                scope.scopeKey,
                chatTitle,
                true,
                when {
                    processed == 0 -> "没有新的聊天需要整理"
                    skeletonBatches > 0 ->
                        "模型服务没有回应（检查上面填的地址与 API Key），其中 $skeletonBatches 批已用本地骨架归档（共 $processed 条消息）"
                    else -> "已整理 $processed 条消息"
                },
            )
        } else {
            val state = MemoryDrainBridge.drain(500) { batches, processed ->
                onProgress(batches, processed)
            }
            // A page that got no answer out of the model leaves the range pending, and every later
            // pass walks into the same wall. What the engine can file on its own is filed here
            // instead, and the report says which of the two happened.
            val filedLocally = if (state.reason.isEmpty()) {
                0
            } else {
                runCatching {
                    withContext(Dispatchers.IO) { api.extractLocal(scope) }
                }.getOrNull()?.optInt("processed", 0) ?: 0
            }
            // The switch said to use the auxiliary model but the address was incomplete, so the
            // pass ran on the model the core holds. Saying so is the difference between a
            // setting that was ignored and one that was never usable.
            val note = if (config.useForAnalysis) {
                "未填写有效的模型服务，本次仍用核心已配置的模型整理："
            } else {
                ""
            }
            MemoryTaskState.Finished(
                scope.scopeKey,
                chatTitle,
                state.reason.isEmpty() || filedLocally > 0,
                if (filedLocally > 0) {
                    note + "模型没有回应（${state.reason}），已用本地骨架归档 $filedLocally 条消息"
                } else {
                    note + memoryDrainMessage(state)
                }
            )
        }
    }
}

private fun memoryCenterChatTitle(scope: MemoryCenterScope): String {
    return when {
        scope.chatId.startsWith("character:") ->
            scope.chatId.removePrefix("character:").split(":", limit = 2)
                .getOrNull(0).orEmpty().ifBlank { "角色" }
        scope.chatId.startsWith("group:") -> "群聊，${scope.chatId.removePrefix("group:")}"
        else -> scope.chatId
    }
}

/**
 * What distinguishes one chat of a character from another. SillyTavern names a chat
 * after its character and appends a timestamp, so the raw name repeats the character
 * and reads as two chats when shown beside it. Only the part left after the
 * character name and the timestamp carries information.
 */
private fun memoryCenterChatSuffix(scope: MemoryCenterScope): String {
    if (!scope.chatId.startsWith("character:")) return ""
    val parts = scope.chatId.removePrefix("character:").split(":", limit = 2)
    val avatar = parts.getOrNull(0).orEmpty()
    var chat = parts.getOrNull(1).orEmpty()
    chat = when {
        avatar.isNotEmpty() && chat.startsWith(avatar) -> chat.removePrefix(avatar)
        avatar.isNotEmpty() && avatar.startsWith(chat) -> ""
        else -> chat
    }
    chat = chat.replace(Regex("""\s*-\s*\d{4}-\d{2}-\d{2}@\S*"""), "")
    return chat.replace("Branch #", "分支 #").trim().trim('-', '·', ' ')
}

private fun memoryCenterTopicList(array: JSONArray?): List<String> {
    if (array == null) return emptyList()
    return List(array.length()) { index -> array.optString(index) }
        .filter { it.isNotBlank() }
}

private fun memoryCenterTime(value: Long): String {
    if (value <= 0L) return "尚未更新"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(value))
}

private fun memoryCenterDisplayText(value: String): String {
    return value
        .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
        .replace(Regex("(?is)<(dream_scene|Analysis|JSONPatch|SceneInfo|dream_body|dream_after_format|environment_details|dream_big_discuss|revised|paragraph|thought|tableThink|tableCheck|tableEdit)\\b[^>]*>.*?</\\1\\s*>"), " ")
        .replace(Regex("(?s)```.*?```"), " ")
        .replace(Regex("(?s)<!--.*?-->"), " ")
        .replace(Regex("(?is)</?[A-Za-z][^>]*>"), " ")
        .replace(Regex("(?:检查(?:写作要求|有无|是否|格式|字数)[^。！？!?]*[。！？!?]?)"), " ")
        .replace(Regex("(?:判定\\s*[:：][^。！？!?]*[。！？!?]?)"), " ")
        .replace(Regex("(?:没有|无)\\s*[“\"'][^”\"']+[”\"'][。！？!?]?"), " ")
        .replace(Regex("(?:检查|判定)[^。！？!?]{0,80}(?:句式|格式|字数|写作|[“\"'][^”\"']+[”\"'])[^。！？!?]*[。！？!?]?"), " ")
        .replace(Regex("(?:没有|无)[^。！？!?]{0,30}(?:句式|格式)[。！？!?]?"), " ")
        .replace(Regex("\\s+"), " ")
        // Entries the engine composed itself still carry the old separator, and stored text keeps
        // whatever it was written with. Normalising on the way out keeps one style on screen
        // without editing the engine, which would reset the analysis cursor and re-run every chat
        // at the reader's expense.
        .replace(" · ", "，")
        .trim()
}

private fun memoryCenterIsStructuralSubject(value: String): Boolean {
    return value.trim().lowercase(Locale.getDefault()) in setOf(
        "system",
        "系统",
        "公共通讯",
        "各大洲",
        "核心状态",
        "衍生状态",
        "载具",
        "环境",
        "世界事件",
    )
}

internal fun memoryCenterReadableValue(value: String): String {
    val trimmed = value.trim()
    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
        return memoryCenterDisplayText(value)
    }

    return try {
        val objectValue = JSONObject(trimmed)
        val preferred = listOf("desc", "content", "title", "value", "name", "location", "status", "condition")
        val preferredText = preferred.mapNotNull { key ->
            objectValue.optString(key).takeIf { it.isNotBlank() }
        }
        if (preferredText.isNotEmpty()) {
            preferredText.distinct().joinToString("，")
        } else {
            val parts = mutableListOf<String>()
            val keys = objectValue.keys()
            while (keys.hasNext() && parts.size < 12) {
                val key = keys.next()
                val itemValue = objectValue.optString(key).takeIf { it.isNotBlank() } ?: continue
                parts += "${memoryCenterFriendlyPredicate(key)}：${memoryCenterDisplayText(itemValue)}"
            }
            parts.joinToString("；")
        }
    } catch (_: Exception) {
        val fields = listOf("title", "desc", "content", "value", "name").mapNotNull { key ->
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                .find(trimmed)
                ?.groupValues
                ?.get(1)
                ?.replace("\\\"", "\"")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        fields.distinct().joinToString("，").ifBlank { memoryCenterDisplayText(value) }
    }
}

private fun memoryCenterFriendlyPredicate(value: String): String {
    val key = value.trim().lowercase(Locale.getDefault())
    return when (key) {
        "hp_current" -> "生命值"
        "stamina_current" -> "体力"
        "hunger_current" -> "饱腹度"
        "thirst_current" -> "水分"
        "infection_current" -> "感染值"
        "morale_current" -> "士气"
        "comfort" -> "舒适度"
        "location" -> "地点"
        "current_location" -> "当前位置"
        "noise" -> "噪音"
        "temperature" -> "温度"
        "threat_level" -> "威胁程度"
        "time_weather" -> "时间与天气"
        "camp" -> "阵营"
        "mental_status" -> "精神状态"
        "physical_status" -> "身体状态"
        "reputation" -> "声望"
        "condition" -> "当前状态"
        "status" -> "状态"
        "scene_state" -> "当前场景状态"
        "appearance" -> "外貌"
        "detail" -> "详细情况"
        "equipment" -> "装备"
        "thoughts" -> "当前想法"
        "desc" -> "说明"
        "africa" -> "非洲"
        "asia" -> "亚洲"
        "europe" -> "欧洲"
        "namerica" -> "北美洲"
        "samerica" -> "南美洲"
        "oceania" -> "大洋洲"
        else -> value.replace('_', ' ').replace('.', ' ').trim()
    }
}

private fun memoryCenterFriendlySubject(value: String): String {
    return when (value.trim().lowercase(Locale.getDefault())) {
        "environment", "环境" -> "环境状态"
        "derived", "衍生状态" -> "人物状态"
        "vehicle", "载具" -> "载具状态"
        "world", "世界事件" -> "世界事件"
        "system", "system：" -> "系统"
        else -> value.trim()
    }
}

internal fun memoryCenterReadableSummary(value: String): String {
    var text = memoryCenterDisplayText(value)
        .replace(Regex("(?i)^\\s*\\[Long-term memory\\]\\s*"), "")
        .replace(
            Regex(
                "(?is)<｜begin▁of▁thinking｜>.*?(?:<｜end▁of▁thinking｜>|终[、，,]\\s*定乾坤[:：][^。！？\\n]*[。！？]?)",
            ),
            " ",
        )
        .replace(Regex("\\[\\d+-\\d+\\]\\s*"), "")
        .replace(Regex("(?i)State changes:\\s*"), "剧情状态变化。")
        .replace(Regex("(?i)Recent state changes:\\s*"), "")

    if (text.startsWith("System：") || text.startsWith("System:")) {
        val marker = "请根据以上内容合理安排出场、剧情、NPC生成等内容"
        val markerIndex = text.indexOf(marker)
        if (markerIndex >= 0) {
            text = text.substring(markerIndex + marker.length)
        }
    }

    val stateMarker = listOf("剧情状态变化。", "State changes:")
        .map { text.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull()
    if (stateMarker != null) {
        text = text.substring(0, stateMarker)
    }

    text = Regex("""([^\s；;。]{1,32})\.([\p{L}\p{N}_.-]{1,48})\s*=\s*([^；;。]+)""")
        .replace(text) { match ->
            val subject = memoryCenterFriendlySubject(match.groupValues[1])
            val predicate = memoryCenterFriendlyPredicate(match.groupValues[2])
            val valueText = match.groupValues[3].trim()
            "${subject}的${predicate}：$valueText"
        }

    return text
        .replace(Regex("""\*\*|__|`+"""), "")
        .replace(Regex("""(?m)^\s*#{1,6}\s*"""), "")
        .replace(Regex("""(?m)^\s*[-*]\s+"""), "")
        .replace(';', '；')
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun memoryCenterLocationKey(value: String): String {
    return value.lowercase(Locale.getDefault())
        .replace(Regex("[\\s/\\\\·•\\-—_]"), "")
}

private fun memoryCenterLevelTitle(level: String): String {
    return when (level) {
        "reflection-chat" -> "聊天总览"
        "reflection-window" -> "剧情阶段"
        "ai-rollup" -> "整理摘要"
        "model-analysis" -> "剧情变化"
        else -> if (level.startsWith("reflection-rollup-")) "剧情汇总" else "剧情摘要"
    }
}

private fun memoryCenterCuratedStory(
    summaries: List<MemoryCenterSummary>,
): List<MemoryCenterSummary> {
    val narrative = summaries.filter { it.level != "model-analysis" }
    val allWindows = memoryCenterMergeShortPhases(
        narrative
            .filter { it.level == "reflection-window" }
            .sortedWith(compareBy<MemoryCenterSummary> { it.startSeq }.thenBy { it.endSeq }),
    )
    val windows = allWindows.takeLast(4)
    val chat = if (windows.isEmpty()) {
        narrative
            .filter { it.level == "reflection-chat" }
            .maxByOrNull { it.endSeq }
            ?.let(::listOf)
            .orEmpty()
    } else {
        emptyList()
    }
    val rollups = narrative
        .filter { it.level.startsWith("reflection-rollup-") }
        .sortedWith(compareBy<MemoryCenterSummary> { it.startSeq }.thenBy { it.endSeq })
        .take(4)
    val coveredThrough = rollups.maxOfOrNull { it.endSeq } ?: -1
    val recentWindows = allWindows
        .filter { it.endSeq > coveredThrough }
        .take(4)
    val aiRollups = narrative
        .filter { it.level == "ai-rollup" }
        .sortedByDescending { it.endSeq }
        .filterNot { summary ->
            val text = summary.text.trimStart()
            text.startsWith("[") || text.startsWith("{") || text.contains("\"op\"")
        }
        .take(2)

    return (rollups + windows + recentWindows + chat + aiRollups)
        .distinctBy { summary ->
            val readable = memoryCenterReadableSummary(summary.text)
            if (readable.isBlank()) "${summary.level}:${summary.startSeq}:${summary.endSeq}"
            else readable.take(240).lowercase(Locale.getDefault())
        }
}

internal data class MemoryCenterDiagnosticsLine(
    val text: String,
    val isWarning: Boolean = false,
)

// A phase covering a couple of messages is not a phase: a save-heavy stretch passes the
// reflection threshold after two saves, and older builds wrote one row per save. Neighbouring
// short phases are shown as one entry instead of four one-line ones.
internal const val MEMORY_CENTER_MIN_PHASE_MESSAGES = 10

// Relation words as cards write them into their state variables.
internal val MEMORY_CENTER_RELATION_WORDS =
    Regex("好感|关系|感情|喜欢|讨厌|爱慕|称呼|称谓|道侣|夫妻|主人|归属|契约|从属|臣服|侍奉|姐妹|兄弟")

internal fun memoryCenterMergeShortPhases(
    windows: List<MemoryCenterSummary>,
): List<MemoryCenterSummary> {
    val merged = mutableListOf<MemoryCenterSummary>()
    for (window in windows) {
        val previous = merged.lastOrNull()
        val previousSpan = previous?.let { it.endSeq - it.startSeq + 1 } ?: 0
        if (previous != null && previousSpan < MEMORY_CENTER_MIN_PHASE_MESSAGES) {
            merged[merged.size - 1] = previous.copy(
                endSeq = maxOf(previous.endSeq, window.endSeq),
                text = listOf(previous.text, window.text)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n"),
            )
        } else {
            merged.add(window)
        }
    }
    return merged
}

// The relationship table holds only bonds the extractor stated. Cards keep the same information
// as state variables (an affection number per person), which never becomes a bond row; showing
// those rows stands in for one instead of an empty section.
internal fun memoryCenterRelationFallbackFacts(
    facts: List<MemoryCenterFact>,
): List<MemoryCenterFact> = facts
    .filter { MEMORY_CENTER_RELATION_WORDS.containsMatchIn(it.predicate) }
    .distinctBy { "${it.subject}\u0000${it.predicate}" }
    .take(20)

internal data class MemoryCenterManualFact(
    val subject: String,
    val predicate: String,
    val value: String,
)

/**
 * Builds the session sampling override from what the reader typed.
 *
 * Blank means "leave this parameter alone"; the word `off` (or its Chinese equivalent) means
 * "take it out of the request entirely", which is how a penalty is switched off without knowing
 * the backend's neutral value. Anything else has to be a number for that field, and one bad
 * field refuses the whole override rather than dropping it silently.
 */
internal fun memoryCenterSamplingOverride(
    temperature: String,
    repetitionPenalty: String,
    frequencyPenalty: String,
    presencePenalty: String,
    reasoningEffort: String = "",
): org.json.JSONObject? {
    val fields = listOf(
        "temperature" to temperature,
        "repetitionPenalty" to repetitionPenalty,
        "frequencyPenalty" to frequencyPenalty,
        "presencePenalty" to presencePenalty,
    )
    val result = org.json.JSONObject()
    for ((key, raw) in fields) {
        val text = raw.trim()
        if (text.isEmpty()) continue
        if (text.equals("off", ignoreCase = true) || text == "无") {
            result.put(key, org.json.JSONObject.NULL)
            continue
        }
        val value = text.toDoubleOrNull() ?: return null
        if (!value.isFinite()) return null
        result.put(key, value)
    }

    // The one field whose value is a word: thinking length is what makes a reasoning model
    // slow, and the providers only accept these four.
    val effortText = reasoningEffort.trim()
    if (effortText.isNotEmpty()) {
        if (effortText.equals("off", ignoreCase = true) || effortText == "无") {
            result.put("reasoningEffort", org.json.JSONObject.NULL)
        } else {
            val effort = when (effortText.lowercase(Locale.getDefault())) {
                "最低", "minimal" -> "minimal"
                "低", "low" -> "low"
                "中", "medium" -> "medium"
                "高", "high" -> "high"
                else -> return null
            }
            result.put("reasoningEffort", effort)
        }
    }

    return if (result.length() == 0) null else result
}

/**
 * Reads the one-line form the manual writer types: `subject.predicate = value`.
 *
 * Anything that does not carry all three parts is refused rather than guessed at, because a
 * half-parsed fact would be stored as a wrong fact about the character.
 */
internal fun memoryCenterParseFact(text: String): MemoryCenterManualFact? {
    val separator = text.indexOf('=')
    if (separator <= 0) return null
    val left = text.substring(0, separator).trim()
    val value = text.substring(separator + 1).trim()
    val dot = left.indexOf('.')
    if (dot <= 0) return null
    val subject = left.substring(0, dot).trim()
    val predicate = left.substring(dot + 1).trim()
    if (subject.isEmpty() || predicate.isEmpty() || value.isEmpty()) return null
    return MemoryCenterManualFact(subject, predicate, value)
}

// The reuse numbers the core measured on the newest pair of requests, as one line. The
// interesting part is the split between reused and recomputed: a prompt that reuses a fifth of
// a sixty-message history is paying full price for everything behind the first entry that
// changed, which is what per-turn text in front of the conversation looks like from here.
internal fun memoryCenterPrefixCacheLine(report: JSONObject?): MemoryCenterDiagnosticsLine? {
    if (report == null) return null
    val messages = memoryCenterDiagnosticsInt(report, "messages") ?: return null
    if (messages <= 0) return null

    // The engine reports the gap to the previous request as null when there is nothing to
    // compare against, which is a different statement from "reused nothing".
    if (report.isNull("sinceSeconds")) {
        return MemoryCenterDiagnosticsLine("前缀复用：还没有可比对的上一轮请求（本次会话尚无记录）")
    }

    val reused = memoryCenterDiagnosticsInt(report, "reuseMessages") ?: 0
    val fresh = memoryCenterDiagnosticsInt(report, "freshChars")
    val percent = memoryCenterDiagnosticsNumberText(report, "reuseRatio")
        ?.toDoubleOrNull()
        ?.let { Math.round(it * 100.0).toInt() }
    val firstDiff = (memoryCenterDiagnosticsInt(report, "firstDiff") ?: 0) + 1

    val measured = if (percent != null) {
        "上次请求复用 $percent%（$reused/$messages 条消息与上一轮完全一致）"
    } else {
        "上次请求复用 $reused/$messages 条消息"
    }
    val cost = fresh?.takeIf { it > 0 }?.let { "，需重算 $it 字" }.orEmpty()

    val advice = when {
        percent != null && percent >= 90 -> "前缀稳定，服务商可以命中缓存。"
        percent != null && percent >= 60 ->
            "大部分前缀可以复用，重算从第 $firstDiff 条消息开始。"
        else ->
            "重算从第 $firstDiff 条（共 $messages 条）开始：排在它前面、每轮都会变的内容" +
                "会让它之后的全部作废。这是按字符比对得出的下限，实际计费以「服务商报告」为准。"
    }

    return MemoryCenterDiagnosticsLine(
        "前缀复用：$measured$cost。$advice",
        isWarning = percent == null || percent < 60,
    )
}

// What the provider itself reported for the newest answer, next to the estimate above. The
// estimate says what the two request bodies had in common; this is the number the bill follows,
// and it is the only place a cache that had already expired shows up as a miss.
internal fun memoryCenterProviderUsageLine(report: JSONObject?): MemoryCenterDiagnosticsLine? {
    if (report == null) return null
    val hit = memoryCenterDiagnosticsInt(report, "hitTokens")
    val miss = memoryCenterDiagnosticsInt(report, "missTokens")
    val prompt = memoryCenterDiagnosticsInt(report, "promptTokens")
    if (hit == null && miss == null) return null

    val percent = memoryCenterDiagnosticsNumberText(report, "hitRatio")
        ?.toDoubleOrNull()
        ?.let { Math.round(it * 100.0).toInt() }

    val counted = when {
        hit != null && miss != null -> "服务商报告：命中 $hit token、未命中 $miss token"
        hit != null && prompt != null -> "服务商报告：提示词 $prompt token，其中命中 $hit token"
        hit != null -> "服务商报告：命中 $hit token"
        else -> "服务商报告：未命中 $miss token"
    }
    // Zero hits with a counted prompt is the one disagreement worth naming: the prompt can match
    // the previous one byte for byte and still be billed as a miss when the cache had expired.
    val expired = hit == 0 && (prompt ?: 0) > 0

    return MemoryCenterDiagnosticsLine(
        buildString {
            append(counted)
            if (percent != null) append("（命中 $percent%）")
            append("。")
            if (expired) append("两轮间隔超过了服务商的缓存存活时间，这一轮按未命中计费。")
        },
        isWarning = expired,
    )
}

// The diagnostics endpoint reports what the engine could read from the running
// settings, so every field may be absent or null. Values it did not report are left
// out of the line rather than printed: an incomplete answer still has to read as a
// report, and a literal "null" says nothing about the engine.
internal fun memoryCenterDiagnosticsLines(root: JSONObject): List<MemoryCenterDiagnosticsLine> {
    val lines = mutableListOf<MemoryCenterDiagnosticsLine>()

    val sampling = memoryCenterDiagnosticsObject(root, "sampling")
    val samplingSource = memoryCenterDiagnosticsText(sampling, "source")
    val samplingParts = memoryCenterDiagnosticsParts(
        sampling,
        listOf(
            "温度" to "temperature",
            "重复惩罚" to "repetitionPenalty",
            "频率惩罚" to "frequencyPenalty",
            "存在惩罚" to "presencePenalty",
            "推理强度" to "reasoningEffort",
            "top_p" to "topP",
            "top_k" to "topK",
            "min_p" to "minP",
        ),
    )
    val sourceNote = if (samplingSource == "last-generate-request") "" else "（尚未取到实际请求）"
    if (samplingParts.isNotEmpty()) {
        lines += MemoryCenterDiagnosticsLine("采样参数：${samplingParts.joinToString("，")}$sourceNote")
    } else if (sourceNote.isNotEmpty()) {
        lines += MemoryCenterDiagnosticsLine("采样参数：尚未取到实际请求")
    }

    val warnings = root.optJSONArray("warnings")
    if (warnings != null) {
        for (index in 0 until warnings.length()) {
            val item = warnings.optJSONObject(index) ?: continue
            val text = memoryCenterDiagnosticsText(item, "text") ?: continue
            lines += MemoryCenterDiagnosticsLine(text, isWarning = true)
        }
    }

    val features = root.optJSONArray("memoryFeatures")
    val featureStates = mutableMapOf<String, String>()
    if (features != null) {
        for (index in 0 until features.length()) {
            val item = features.optJSONObject(index) ?: continue
            val id = memoryCenterDiagnosticsText(item, "id") ?: continue
            featureStates[id] = when (memoryCenterDiagnosticsBoolean(item, "enabled")) {
                true -> "已开启"
                false -> "未开启"
                null -> "未知"
            }
        }
    }
    lines += MemoryCenterDiagnosticsLine(
        "记忆功能：" + listOf(
            "built-in" to "角色记忆",
            "summarize" to "总结扩展",
            "chat-vectorization" to "聊天向量化",
        ).joinToString("，") { (id, label) -> "$label ${featureStates[id] ?: "未知"}" },
    )

    val retrievalParts = memoryCenterDiagnosticsParts(
        memoryCenterDiagnosticsObject(root, "retrieval"),
        listOf(
            "分数门限" to "scoreThreshold",
            "上次注入条数" to "lastInjectedLines",
            "上次低于门限丢弃条数" to "lastDroppedBelowThreshold",
        ),
    )
    if (retrievalParts.isNotEmpty()) {
        lines += MemoryCenterDiagnosticsLine("检索：${retrievalParts.joinToString("，")}")
    }

    memoryCenterPrefixCacheLine(memoryCenterDiagnosticsObject(root, "prefixCache"))
        ?.let { lines += it }

    memoryCenterProviderUsageLine(memoryCenterDiagnosticsObject(root, "providerUsage"))
        ?.let { lines += it }

    // A block that is full has to read as "spent on what", not only as "how much": the
    // memory book can crowd out every other section without any counter saying so.
    val render = memoryCenterDiagnosticsObject(root, "render")
    val renderParts = mutableListOf<String>()
    val usedChars = memoryCenterDiagnosticsNumberText(render, "usedChars")
    val budgetChars = memoryCenterDiagnosticsNumberText(render, "budgetChars")
    when {
        usedChars != null && budgetChars != null -> renderParts += "已用 $usedChars/$budgetChars 字符"
        usedChars != null -> renderParts += "已用 $usedChars 字符"
        budgetChars != null -> renderParts += "预算 $budgetChars 字符"
    }
    memoryCenterDiagnosticsNumberText(render, "injectedLines")?.let { renderParts += "注入 $it 行" }
    // Zero tokens means nothing was injected yet, and "about 0 tokens" reads as a defect
    // rather than as "no generation has happened in this session".
    memoryCenterDiagnosticsInt(render, "usedTokens")
        ?.takeIf { it > 0 }
        ?.let { renderParts += "约 $it token" }
    val sectionsOffered = memoryCenterDiagnosticsInt(render, "sectionsOffered")
    val sectionsDropped = memoryCenterDiagnosticsInt(render, "sectionsDropped")
    if (sectionsOffered != null && sectionsDropped != null && sectionsDropped <= sectionsOffered) {
        val droppedNote = if (sectionsDropped > 0) "（$sectionsDropped 段未装入）" else ""
        renderParts += "段落 ${sectionsOffered - sectionsDropped}/$sectionsOffered$droppedNote"
    }
    if (renderParts.isNotEmpty()) {
        lines += MemoryCenterDiagnosticsLine("渲染：${renderParts.joinToString("，")}")
    }

    // Where the character budget came from matters as much as its value: a fixed 1600 is
    // a fifth of one prompt and more than half of another, so the line says which one it is.
    val budgetSource = memoryCenterDiagnosticsText(render, "budgetSource")
    val promptTokens = memoryCenterDiagnosticsNumberText(render, "promptTokens")
    when {
        budgetSource == "prompt-tokens" && promptTokens != null ->
            lines += MemoryCenterDiagnosticsLine("注入额度：随模型上下文自动伸缩（提示词预算 $promptTokens token）")
        budgetSource == "prompt-tokens" ->
            lines += MemoryCenterDiagnosticsLine("注入额度：随模型上下文自动伸缩")
        budgetSource == "fallback" ->
            lines += MemoryCenterDiagnosticsLine("注入额度：固定值（尚未取到模型上下文）")
    }

    val sections = render?.optJSONArray("sections")
    val sectionParts = mutableListOf<String>()
    if (sections != null) {
        for (index in 0 until sections.length()) {
            val item = sections.optJSONObject(index) ?: continue
            val title = memoryCenterDiagnosticsText(item, "title") ?: continue
            val chars = memoryCenterDiagnosticsNumberText(item, "chars") ?: continue
            sectionParts += "$title $chars"
        }
    }
    if (sectionParts.isNotEmpty()) {
        lines += MemoryCenterDiagnosticsLine("段落占用：${sectionParts.joinToString("，")}")
    }

    // Advice carries where it comes from: our own code reading and community experience are
    // not the same claim, and the reader has to be able to tell them apart.
    val advisories = root.optJSONArray("advisories")
    if (advisories != null) {
        for (index in 0 until advisories.length()) {
            val item = advisories.optJSONObject(index) ?: continue
            val text = memoryCenterDiagnosticsText(item, "text") ?: continue
            val source = memoryCenterDiagnosticsText(item, "source")
            val verifiedAt = memoryCenterDiagnosticsText(item, "verifiedAt")
            val label = when {
                source == null -> ""
                source == "community" -> "（社区说法"
                else -> "（按核心源码核对"
            }
            val suffix = when {
                label.isEmpty() -> ""
                verifiedAt == null -> "$label）"
                else -> "$label，$verifiedAt）"
            }
            lines += MemoryCenterDiagnosticsLine("$text$suffix")
        }
    }

    val storage = memoryCenterDiagnosticsObject(root, "storage")
    if (storage != null && memoryCenterDiagnosticsBoolean(storage, "ok") != false) {
        val storageParts = mutableListOf<String>()
        memoryCenterDiagnosticsNumberText(storage, "bytes")?.let {
            storageParts += "库 ${memoryCenterDiagnosticsBytes(it)}"
        }
        memoryCenterDiagnosticsNumberText(storage, "freeBytes")?.let {
            storageParts += "空闲 ${memoryCenterDiagnosticsBytes(it)}"
        }
        memoryCenterDiagnosticsText(storage, "integrity")?.let { storageParts += "完整性 $it" }
        val counts = memoryCenterDiagnosticsObject(storage, "counts")
        val countParts = memoryCenterDiagnosticsParts(
            counts,
            listOf(
                "消息" to "messages",
                "分块" to "chunks",
                "向量" to "embeddings",
                "事实" to "facts",
                "摘要" to "summaries",
                "记忆本" to "entries",
                "表格行" to "tableRows",
            ),
        )
        val missing = storage.optJSONArray("missingIndexes")
        val missingCount = missing?.length() ?: 0
        storageParts += if (missingCount == 0) "索引齐全" else "缺 $missingCount 个索引"
        lines += MemoryCenterDiagnosticsLine("存储：${storageParts.joinToString("，")}")
        if (countParts.isNotEmpty()) {
            lines += MemoryCenterDiagnosticsLine("存储内容：${countParts.joinToString("，")}")
        }
    }

    return lines
}

// Byte counts are read by a person deciding whether to clear something, so they are shown in
// the unit the storage page uses rather than in raw bytes.
private fun memoryCenterDiagnosticsBytes(raw: String): String {
    val bytes = raw.toDoubleOrNull() ?: return raw
    if (!bytes.isFinite() || bytes < 0) return raw
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1024.0) {
        String.format(Locale.US, "%.1f MB", mb)
    } else {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    }
}

private fun memoryCenterDiagnosticsObject(root: JSONObject, key: String): JSONObject? {
    return root.opt(key) as? JSONObject
}

// opt() tells a JSON null apart from a missing key, which optString() flattens into
// an empty string on one implementation and into the text "null" on another.
private fun memoryCenterDiagnosticsText(item: JSONObject?, key: String): String? {
    val value = item?.opt(key) as? String ?: return null
    val text = value.trim()
    return text.takeIf { it.isNotEmpty() && it != "null" }
}

private fun memoryCenterDiagnosticsBoolean(item: JSONObject?, key: String): Boolean? {
    return when (val value = item?.opt(key)) {
        is Boolean -> value
        is String -> when (value.trim().lowercase(Locale.US)) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> null
    }
}

// Keeps the scale the engine sent, because "1.0" and "1" describe different settings
// even though they are the same number. Text that is not a number is reported as
// absent instead of reaching optDouble, which throws on it.
private fun memoryCenterDiagnosticsNumberText(item: JSONObject?, key: String): String? {
    val text = when (val value = item?.opt(key)) {
        is Number -> value.toString()
        is String -> value.trim()
        else -> return null
    }
    val number = text.toDoubleOrNull() ?: return null
    if (!number.isFinite()) return null
    if (text.matches(Regex("""-?\d+(\.\d+)?"""))) return text
    val formatted = String.format(Locale.US, "%.4f", number)
    // Only the fraction loses its padding: trimming the whole text would turn
    // "100000.0000" into "1".
    val whole = formatted.substringBefore('.')
    val fraction = formatted.substringAfter('.', "").trimEnd('0')
    return if (fraction.isEmpty()) whole else "$whole.$fraction"
}

// Counts are compared and subtracted, so they are read as integers. A value that is not a
// whole non-negative number is treated as absent rather than rounded into a plausible count.
private fun memoryCenterDiagnosticsInt(item: JSONObject?, key: String): Int? {
    val text = memoryCenterDiagnosticsNumberText(item, key) ?: return null
    val number = text.toDoubleOrNull() ?: return null
    if (!number.isFinite() || number < 0 || number > Int.MAX_VALUE) return null
    val truncated = number.toInt()
    return truncated.takeIf { it.toDouble() == number }
}

private fun memoryCenterDiagnosticsParts(
    item: JSONObject?,
    fields: List<Pair<String, String>>,
): List<String> {
    if (item == null) return emptyList()
    return fields.mapNotNull { (label, key) ->
        // Every field is a number except the reasoning effort, which is one of four words.
        // A string where a number belongs stays omitted rather than printed as a value.
        val value = if (key == "reasoningEffort") {
            memoryCenterDiagnosticsText(item, key)?.let { memoryCenterEffortText(it) }
        } else {
            memoryCenterDiagnosticsNumberText(item, key)
        }
        value?.let { "$label $it" }
    }
}

private fun memoryCenterEffortText(value: String): String {
    return when (value.trim().lowercase(Locale.getDefault())) {
        "minimal" -> "最低"
        "low" -> "低"
        "medium" -> "中"
        "high" -> "高"
        else -> value.trim()
    }
}

@Composable
fun MemoryCenterScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val nodeState by NodeState.state.collectAsState()
    val runningSince by NodeState.runningSince.collectAsState()
    val port by NodeState.port.collectAsState()
    val api = remember(port) { MemoryCenterApi(port) }

    var scopes by remember { mutableStateOf<List<MemoryCenterScope>>(emptyList()) }
    var selectedScope by remember { mutableStateOf<MemoryCenterScope?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    // Whether the library has ever finished a load. A pending jump from the task island cannot be
    // matched against an empty list, and matching it against a stale one would open the wrong
    // chat: the request has to wait for a list that was actually read.
    var scopesLoadedOnce by remember { mutableStateOf(false) }
    val requestedScopeKey by ExtensionsTabRequest.scopeKey.collectAsState()
    var scanConfirm by remember { mutableStateOf(false) }
    // The run itself lives in MemoryScanState: paging through a library takes minutes, and a
    // sub-tab switch used to cancel it silently.
    val scanRunning by MemoryScanState.running.collectAsState()
    val scanResultText by MemoryScanState.result.collectAsState()
    val scanning = scanRunning != null
    var memoryEnabled by remember { mutableStateOf(SettingsState.tavernLongMemoryEnabled()) }
    var coreVersion by remember { mutableStateOf("") }
    var coreTooOld by remember { mutableStateOf(false) }
    // The core reads the memory option while the service starts, so a change made afterwards
    // has no effect until the next start. Comparing file times was wrong in both directions:
    // the boot flow rewrites the options file while the service is already running, which
    // pinned this banner on until the next cold start. The app now records the startup-only
    // values the running service read and compares them with the ones on screen; the
    // console-wide restart action follows the same comparison, so the banner and the action
    // cannot disagree. The banner is about the memory switch, but a restart owed to another
    // startup-only switch leaves this one unverified as well, which is what it says.
    val coreRestartPending by SettingsState.coreRestartPending.collectAsState()
    val optionsPendingRestart = coreRestartPending &&
        nodeState == NodeState.State.RUNNING &&
        runningSince > 0L

    fun refreshScopes() {
        if (nodeState != NodeState.State.RUNNING) {
            scopes = emptyList()
            error = "核心服务未启动，暂时无法使用记忆中心"
            return
        }
        coroutineScope.launch {
            loading = true
            error = null
            try {
                scopes = withContext(Dispatchers.IO) { api.scopes() }
                scopesLoadedOnce = true
                val current = selectedScope
                if (current != null) {
                    selectedScope = scopes.firstOrNull { it.scopeKey == current.scopeKey }
                }
            } catch (exception: Exception) {
                error = exception.message ?: "读取记忆失败"
            } finally {
                loading = false
            }
        }
    }

    fun scanOldChats() {
        if (nodeState != NodeState.State.RUNNING || MemoryScanState.busy) return
        MemoryScanState.start { onProgress ->
            onProgress("正在收录旧聊天")
            // Counters live outside the try so a failed run can still report how far
            // it got before it gave up.
            var completed = 0
            try {
                var offset = 0
                var total = 0
                var failed = 0
                var inserted = 0
                var updated = 0
                var iterations = 0
                val failures = mutableListOf<String>()
                while (true) {
                    // One failed page used to throw the whole run away, including the
                    // chats already indexed, so a hiccup on the first page looked like
                    // "the first attempt fails, the second one works". The page is
                    // retried; only a run of failures is reported.
                    var page: JSONObject? = null
                    var lastError: Exception? = null
                    var attempt = 0
                    while (page == null && attempt < ScanRetries) {
                        try {
                            page = withContext(Dispatchers.IO) { api.scan(offset) }
                        } catch (exception: Exception) {
                            lastError = exception
                            attempt += 1
                            if (attempt < ScanRetries) delay(ScanRetryDelayMs * attempt)
                        }
                    }
                    val currentPage = page
                        ?: throw (lastError ?: IllegalStateException("扫描请求失败"))
                    val stats = currentPage.optJSONObject("stats") ?: JSONObject()
                    total = currentPage.optInt("total", total)
                    completed += stats.optInt("scanned", 0)
                    failed += stats.optInt("failed", 0) + stats.optInt("reflectionFailed", 0)
                    inserted += stats.optInt("inserted", 0)
                    updated += stats.optInt("updated", 0)
                    val pageFailures = currentPage.optJSONArray("failures")
                    if (pageFailures != null) {
                        for (index in 0 until pageFailures.length()) {
                            val item = pageFailures.optJSONObject(index) ?: continue
                            val chatId = item.optString("chatId").ifBlank { "未知聊天" }
                            val reason = item.optString("reason").ifBlank { "读取失败" }
                            failures += "$chatId：$reason"
                        }
                    }
                    val nextOffset = currentPage.optInt("nextOffset", offset)
                    val remaining = currentPage.optInt("remaining", 0)
                    val pending = currentPage.optJSONObject("pendingChat")
                    onProgress(
                        if (pending != null) {
                            val chatId = pending.optString("chatId").ifBlank { "长聊天" }
                            val covered = pending.optInt("coveredThrough", -1) + 1
                            val messageCount = pending.optInt("messageCount", 0)
                            "正在收录长聊天：$chatId\n已覆盖 ${covered.coerceAtLeast(0)}/$messageCount 条消息"
                        } else {
                            val progress = if (total > 0) "$nextOffset/$total" else nextOffset.toString()
                            "已处理 $progress 个聊天\n成功 $completed 个，失败 $failed 个，新增 $inserted 条消息"
                        }
                    )
                    if (remaining <= 0) break
                    if (nextOffset == offset && pending == null) {
                        throw IllegalStateException("扫描进度未推进")
                    }
                    offset = nextOffset
                    iterations += 1
                    if (iterations > 10_000) {
                        throw IllegalStateException("扫描次数异常，已停止")
                    }
                }
                buildString {
                    append(if (failed > 0) "部分完成" else "收录完成")
                    append("：已收录 $completed 个聊天")
                    if (failed > 0) append("，失败 $failed 个")
                    append("，新增 $inserted 条消息")
                    if (updated > 0) append("，更新 $updated 条")
                    val uniqueFailures = failures.distinct()
                    if (uniqueFailures.isNotEmpty()) {
                        append("\n\n失败记录：\n")
                        append(uniqueFailures.take(5).joinToString("\n"))
                        if (uniqueFailures.size > 5) {
                            append("\n另有 ${uniqueFailures.size - 5} 个失败记录")
                        }
                    }
                }
            } catch (exception: Exception) {
                val indexed = if (completed > 0) "（失败前已收录 $completed 个聊天）" else ""
                "收录失败：${exception.message ?: "未知错误"}$indexed"
            }
        }
    }

    LaunchedEffect(nodeState, port) {
        refreshScopes()
        coreVersion = withContext(Dispatchers.IO) {
            val coreDir = AssetExtractor.getCoreDir(context)
            val packageVersion = JsonUtil.readVersion(
                java.io.File(coreDir, "package.json"),
                "",
            )
            if (packageVersion.isNotBlank()) {
                packageVersion
            } else {
                java.io.File(context.filesDir, "core_version.txt")
                    .takeIf { it.exists() }
                    ?.readText()
                    ?.trim()
                    .orEmpty()
            }
        }
        coreTooOld = coreVersion.isNotBlank()
            && !TavernCoreCapabilities.supports(
                TavernCoreCapabilities.Feature.MEMORY,
                coreVersion,
            )
    }

    // The run finishes outside this screen — the reader may be on another tab by then — so the
    // list has to pick up what it just indexed when they come back and see the result.
    LaunchedEffect(scanResultText) {
        if (scanResultText != null) refreshScopes()
    }

    // Only chats the core can still open are listed. A memory that belongs to a chat file the
    // character card no longer resolves is not a second conversation the reader can visit, and
    // the core itself treats that pairing as gone; the row is left out rather than shown as a
    // duplicate or parked in a section of its own. Nothing is deleted.
    val listedScopes = remember(scopes) {
        scopes.filterNot { it.missingCard }
    }

    // Memory is stored per swipe branch, so one chat produces one scope per branch and
    // the library listed them side by side — the same title repeated down the page.
    // Branches are now a property of their chat: one row, and a branch is chosen after
    // opening it.
    val chatGroups = remember(listedScopes) {
        listedScopes
            .groupBy { it.chatId.ifBlank { it.scopeKey } }
            .map { (chatId, branches) ->
                MemoryCenterChatGroup(
                    chatId = chatId,
                    branches = branches.sortedByDescending { it.updatedAt },
                )
            }
            .sortedByDescending { group -> group.branches.maxOf { it.updatedAt } }
    }

    // A tap on the task island asks for one chat, and the island's label named it, so opening the
    // library instead is what the reader would call the wrong screen. The request is dropped once
    // it has been honoured, or once a load has failed and there is nothing to open.
    LaunchedEffect(requestedScopeKey, scopesLoadedOnce, chatGroups, error) {
        val key = requestedScopeKey ?: return@LaunchedEffect
        if (error != null) {
            ExtensionsTabRequest.consumeScopeKey()
            return@LaunchedEffect
        }
        if (!scopesLoadedOnce) return@LaunchedEffect
        val group = chatGroups.firstOrNull { candidate ->
            candidate.branches.any { it.scopeKey == key }
        }
        ExtensionsTabRequest.consumeScopeKey()
        if (group != null) {
            selectedScope = group.branches.first()
        }
    }

    BackHandler(enabled = selectedScope != null) {
        selectedScope = null
    }

    if (coreTooOld) {
        // The host already applies the page gutter; padding horizontally again
        // here pushed this prompt 12dp further in than every other screen.
        Box(
            modifier = modifier.fillMaxSize().padding(vertical = AppSpacing.md),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(AppSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "需要更新核心",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                    Text(
                        "角色记忆需要 SillyTavern " +
                            "${TavernCoreCapabilities.Feature.MEMORY.minimumCoreVersion} " +
                            "或更高版本。\n\n" +
                            "当前核心：${coreVersion.ifBlank { "未知" }}\n" +
                            "请在“控制台 → 更新”中安装 " +
                            TavernCoreCapabilities.Feature.MEMORY.minimumCoreVersion +
                            " 后再使用角色记忆。",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
                    )
                }
            }
        }
        return
    }

    Box(modifier = modifier.fillMaxSize()) {
        val selected = selectedScope
        if (selected == null) {
            MemoryCenterLibrary(
                nodeRunning = nodeState == NodeState.State.RUNNING,
                loading = loading,
                error = error,
                scopes = chatGroups,
                memoryEnabled = memoryEnabled,
                pendingRestart = optionsPendingRestart,
                onMemoryEnabledChange = { enabled ->
                    memoryEnabled = enabled
                    SettingsState.setTavernLongMemory(context, enabled)
                    // The option is read when the service starts, so the switch on its own would
                    // be a silent no-op: it would read as on while nothing was being recorded.
                    // The console shows a restart action for exactly that state and lets the
                    // reader choose when a running conversation is interrupted by it.
                    AppToast.show(
                        context,
                        if (enabled) "已开启记忆，重启核心服务后开始记录" else "已关闭记忆，重启核心服务后停止记录",
                        Toast.LENGTH_SHORT
                    )
                },
                onScan = { scanConfirm = true },
                onOpen = { group -> selectedScope = group.branches.first() },
            )
        } else {
            MemoryCenterWorkspace(
                scope = selected,
                api = api,
                refreshToken = refreshToken,
                onBack = { selectedScope = null },
                onChanged = { refreshToken++ },
                onDeleted = {
                    selectedScope = null
                    refreshScopes()
                },
                onScan = { scanConfirm = true },
            )
        }
    }

    if (scanConfirm) {
        ConfirmDialog(
            title = "收录旧聊天",
            // The word here has to match what the run does. It indexes the chats that predate the
            // switch and writes local summaries; it does not analyse anything, so calling it a
            // tidy-up promised a pass over the memories that never happened.
            message = "系统会读取已有角色聊天和群聊，把消息与本地剧情摘要收进记忆库；这一步只在本地收录，不调用模型，也不会修改聊天原文。长聊天会自动续跑；若开启了向量检索并填了向量服务，参与向量化的消息文本会发送到所填服务。",
            confirmText = if (scanning) "收录中" else "开始收录",
            onConfirm = {
                scanConfirm = false
                scanOldChats()
            },
            onDismiss = { if (!scanning) scanConfirm = false },
        )
    }

    // The report is modal, and a modal with no exit trapped the reader on this page until the run
    // finished. Indexing is a background task like the installs are, so it can be sent to the back
    // and picked up from the task island; a later entry into this tab shows it again.
    var scanReportHidden by remember { mutableStateOf(false) }
    LaunchedEffect(scanRunning) {
        if (scanRunning != null) scanReportHidden = false
    }
    val scanProgress = scanRunning
    if (scanProgress != null && !scanReportHidden) {
        AlertDialog(
            onDismissRequest = {},
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("正在收录", fontWeight = FontWeight.SemiBold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(AppDimens.iconBadgeGlyph),
                        color = LocalAppColors.current.accent,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(AppSpacing.md))
                    Text(scanProgress.text, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                SoftActionButton(
                    text = "后台收录",
                    onClick = { scanReportHidden = true },
                    modifier = Modifier.height(AppDimens.controlHeightCompact),
                )
            },
        )
    }

    val completedScanResult = scanResultText
    if (completedScanResult != null) {
        AlertDialog(
            onDismissRequest = { MemoryScanState.clearResult() },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("收录结果", fontWeight = FontWeight.SemiBold) },
            text = { Text(completedScanResult, style = MaterialTheme.typography.bodySmall, lineHeight = 19.sp) },
            confirmButton = {
                SoftActionButton(
                    text = "知道了",
                    onClick = { MemoryScanState.clearResult() },
                    modifier = Modifier.height(AppDimens.controlHeightCompact),
                )
            },
        )
    }
}

@Composable
private fun MemoryCenterLibrary(
    nodeRunning: Boolean,
    loading: Boolean,
    error: String?,
    scopes: List<MemoryCenterChatGroup>,
    memoryEnabled: Boolean,
    pendingRestart: Boolean,
    onMemoryEnabledChange: (Boolean) -> Unit,
    onScan: () -> Unit,
    onOpen: (MemoryCenterChatGroup) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "记忆中心",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            // No reload control here: the list is read every time this tab is entered, so a
            // button for it only duplicated what arriving already does.
            Switch(
                checked = memoryEnabled,
                onCheckedChange = onMemoryEnabledChange,
                colors = appSwitchColors(),
            )
        }

        Spacer(Modifier.height(AppSpacing.xs))
        Text(
            "功能仍在完善中，结果可能不完整或不稳定",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary),
        )

        Spacer(Modifier.height(AppSpacing.md))
        // The accent hairline made an ordinary status line read as highlighted;
        // the status dot is what carries the state color.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AppRadius.md),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(AppDimens.statusDot)
                            .background(
                                when {
                                    !memoryEnabled -> LocalAppColors.current.warning
                                    pendingRestart -> LocalAppColors.current.warning
                                    nodeRunning -> LocalAppColors.current.success
                                    else -> LocalAppColors.current.error
                                },
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(AppSpacing.sm))
                    Text(
                        when {
                            !memoryEnabled -> "记忆功能未启用"
                            !nodeRunning -> "核心服务未启动"
                            pendingRestart -> "记忆已开启，重启核心服务后生效"
                            else -> "记忆服务正常"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (scopes.isEmpty()) "还没有记忆" else "${scopes.size} 个聊天",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(AppSpacing.md))
                // A disabled full-width button reads as broken UI; explain what to
                // do instead, and only show the action when it can actually run.
                if (nodeRunning && memoryEnabled) {
                    SoftActionButton(
                        text = "收录旧聊天",
                        onClick = onScan,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        if (!memoryEnabled) {
                            "打开上方开关即可开始收录旧聊天"
                        } else {
                            "核心服务启动后才能收录"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(AppSpacing.md))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LocalAppColors.current.accent)
            }
            error != null -> MemoryCenterEmpty(error)
            scopes.isEmpty() -> MemoryCenterEmpty(
                // The empty list is where the confusion starts, so it says which of
                // the two reasons applies instead of one generic sentence.
                when {
                    !memoryEnabled -> "记忆未开启：打开右上角开关后，聊天才会开始记录"
                    pendingRestart -> "记忆已开启，但运行中的核心还在用旧设置：重启后开始记录"
                    else -> "打开核心聊几句，或点「收录旧聊天」把已聊过的内容收进记忆"
                },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                items(scopes, key = { it.chatId }) { group ->
                    MemoryCenterScopeRow(
                        group = group,
                        onClick = { onOpen(group) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

// Every memory scope that belongs to one chat: the main line plus one entry per
// swipe branch. The library shows the chat once and the branches are chosen later.
private data class MemoryCenterChatGroup(
    val chatId: String,
    val branches: List<MemoryCenterScope>,
)

@Composable
private fun MemoryCenterScopeRow(
    group: MemoryCenterChatGroup,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = group.branches.first()
    val counted = group.branches.firstOrNull { it.current } ?: primary
    val metrics = listOf(
        "状态" to counted.factCount,
        "关系" to counted.relationshipCount,
        "场景" to counted.sceneCount,
        "摘要" to counted.summaryCount,
    ).filter { it.second > 0 }.joinToString("，") { "${it.first} ${it.second}" }
    val newest = group.branches.maxOf { it.updatedAt }

    ListRow(
        icon = Icons.Outlined.History,
        title = memoryCenterChatTitle(primary),
        subtitle = buildString {
            memoryCenterChatSuffix(primary).takeIf { it.isNotBlank() }?.let { append("$it，") }
            append("${counted.messageCount} 条消息")
            if (metrics.isNotBlank()) append("，$metrics")
            append("，${memoryCenterTime(newest)}")
        },
        onClick = onClick,
        modifier = modifier,
        trailing = { TrailingChevron() },
    )
}

@Composable
private fun MemoryCenterWorkspace(
    scope: MemoryCenterScope,
    api: MemoryCenterApi,
    refreshToken: Int,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onDeleted: () -> Unit,
    onScan: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var page by remember { mutableStateOf(MemoryCenterPage.OVERVIEW) }
    var overview by remember { mutableStateOf<MemoryCenterOverview?>(null) }
    var structured by remember { mutableStateOf<MemoryCenterStructured?>(null) }
    var book by remember { mutableStateOf<List<MemoryCenterBookEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val memoryTaskRunning by MemoryTaskState.running.collectAsState()
    val memoryTaskFinished by MemoryTaskState.finished.collectAsState()

    fun reload() {
        coroutineScope.launch {
            loading = true
            error = null
            try {
                val result = withContext(Dispatchers.IO) {
                    Triple(
                        api.overview(scope),
                        api.structured(scope),
                        api.book(scope),
                    )
                }
                overview = result.first
                structured = result.second
                book = result.third
            } catch (exception: Exception) {
                error = exception.message ?: "读取记忆失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(scope.scopeKey, refreshToken) {
        reload()
    }

    // Handled on this screen rather than on the settings page: this is the one that stays mounted
    // for the whole pass, so the completion is reported and the reload happens exactly once.
    LaunchedEffect(memoryTaskFinished) {
        val finished = memoryTaskFinished ?: return@LaunchedEffect
        AppToast.show(context, finished.text, Toast.LENGTH_SHORT)
        MemoryTaskState.clearFinished()
        onChanged()
        reload()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    memoryCenterChatTitle(scope),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    memoryCenterTime(scope.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary),
                )
            }
            // The circle beside the chat name reads as "do something with this chat", and a bare
            // reload looked like a dead control because the page's own data rarely changes while
            // it is open. It starts the pass instead, which is what the reader was reaching for;
            // reloading still happens when the pass finishes.
            val runningHere = memoryTaskRunning?.takeIf {
                it.chatTitle == memoryCenterChatTitle(scope)
            }
            if (runningHere != null) {
                IconButton(onClick = {}, enabled = false) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = LocalAppColors.current.accent,
                    )
                }
            } else {
                IconButton(
                    onClick = { startMemoryPass(scope, api, context) },
                    enabled = memoryTaskRunning == null,
                ) {
                    Icon(Icons.Outlined.Refresh, "整理这个聊天", tint = LocalAppColors.current.accent)
                }
            }
        }

        MemoryCenterPageStrip(
            selected = page,
            onSelect = { page = it },
        )
        Spacer(Modifier.height(AppSpacing.md))

        val currentError = error
        when {
            loading && overview == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LocalAppColors.current.accent)
            }
            currentError != null -> MemoryCenterEmpty(currentError)
            else -> when (page) {
                MemoryCenterPage.OVERVIEW -> MemoryCenterOverviewPage(
                    scope = scope,
                    overview = overview,
                    structured = structured,
                    api = api,
                )
                MemoryCenterPage.STORY -> MemoryCenterStoryPage(
                    scope = scope,
                    api = api,
                    summaries = structured?.summaries.orEmpty()
                        .filter { it.level != "model-analysis" },
                    onScan = onScan,
                )
                MemoryCenterPage.PEOPLE -> MemoryCenterPeoplePage(structured)
                MemoryCenterPage.PLACES -> MemoryCenterPlacesPage(
                    structured = structured,
                    book = book,
                )
                MemoryCenterPage.BOOK -> MemoryCenterBookPage(
                    scope = scope,
                    entries = book,
                    api = api,
                    onChanged = { reload(); onChanged() },
                )
                MemoryCenterPage.SETTINGS -> MemoryCenterSettingsPage(
                    scope = scope,
                    api = api,
                    overview = overview,
                    onChanged = { reload(); onChanged() },
                    onDeleted = onDeleted,
                    onScan = onScan,
                )
            }
        }
    }
}

@Composable
private fun MemoryCenterPageStrip(
    selected: MemoryCenterPage,
    onSelect: (MemoryCenterPage) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
    ) {
        MemoryCenterPage.entries.forEach { page ->
            val active = page == selected
            // A fill behind the current page plus a border around it made the strip
            // six coloured blocks; the tab marker below the label is the same one
            // the hub uses, and it leaves the page background alone.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AppDimens.controlHeight)
                    // The strip paints no fill; the marker under the label carries
                    // the selection, so the touch feedback stays square and spans
                    // the whole tab like the console's other rows.
                    .clickable { onSelect(page) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    page.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    color = if (active) {
                        LocalAppColors.current.accent
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary)
                    },
                )
                Spacer(Modifier.height(AppSpacing.xs))
                Box(
                    modifier = Modifier
                        .width(AppDimens.iconLg)
                        .height(AppDimens.progressHairline)
                        .background(
                            if (active) LocalAppColors.current.accent else Color.Transparent,
                            RoundedCornerShape(AppRadius.pill),
                        ),
                )
            }
        }
    }
}

@Composable
private fun MemoryCenterOverviewPage(
    scope: MemoryCenterScope,
    overview: MemoryCenterOverview?,
    structured: MemoryCenterStructured?,
    api: MemoryCenterApi,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var testOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var testRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<MemoryCenterTestResult?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }

    fun runTest() {
        if (query.isBlank() || testRunning) return
        val submittedQuery = query.trim()
        coroutineScope.launch {
            testRunning = true
            testError = null
            testResult = null
            try {
                testResult = withContext(Dispatchers.IO) {
                    api.test(scope, submittedQuery)
                }
            } catch (exception: Exception) {
                testError = exception.message ?: "测试失败"
            } finally {
                testRunning = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        val inferredLocation = structured?.facts.orEmpty()
            .firstOrNull {
                val predicate = it.predicate.lowercase(Locale.getDefault())
                predicate == "location" || predicate.endsWith(".location")
            }
            ?.let { memoryCenterReadableValue(it.value) }
            .orEmpty()
        item {
            MemoryCenterSceneCard(
                location = overview?.sceneLocation.orEmpty()
                    .ifBlank { structured?.sceneLocation.orEmpty() }
                    .ifBlank { inferredLocation },
                date = overview?.sceneDate.orEmpty().ifBlank { structured?.sceneDate.orEmpty() },
                time = overview?.sceneTime.orEmpty().ifBlank { structured?.sceneTime.orEmpty() },
                participants = overview?.participants.orEmpty().ifEmpty { structured?.participants.orEmpty() },
            )
        }
        item {
            MemoryCenterSectionTitle("记忆概览")
        }
        item {
            // Label-and-value rows instead of a six-tile dashboard: the numbers are
            // read one at a time, and a grid of coloured figures is the shape every
            // generated dashboard has.
            ListGroup {
                MemoryCenterMetricRow("剧情节点", overview?.summaries ?: scope.summaryCount)
                MemoryCenterMetricRow("人物状态", overview?.facts ?: scope.factCount)
                MemoryCenterMetricRow("人物关系", overview?.relationships ?: scope.relationshipCount)
                MemoryCenterMetricRow("场景", overview?.scenes ?: scope.sceneCount)
                MemoryCenterMetricRow("记忆本", overview?.memoryEntries ?: 0)
                MemoryCenterMetricRow("已有角色", overview?.entities ?: 0)
            }
        }
        item {
            // The shared console cards carry no outline, and a hairline on only
            // some of the cards on this page made those look framed.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    Text(
                        "回忆测试",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(AppSpacing.xxs))
                    Text(
                        "输入一句问题，查看角色目前能回忆的内容。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                    SoftActionButton(
                        text = "开始测试",
                        onClick = { testOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        item {
            MemoryCenterSectionTitle("最近变化")
        }
        if (overview?.recentSummaries.isNullOrEmpty()) {
            item {
                MemoryCenterInlineEmpty("还没有形成剧情摘要")
            }
        } else {
            items(overview.recentSummaries.take(4), key = { "${it.level}:${it.startSeq}:${it.endSeq}" }) { summary ->
                MemoryCenterSummaryRow(summary, compact = true)
            }
        }
    }

    if (testOpen) {
        AlertDialog(
            onDismissRequest = { if (!testRunning) testOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("回忆测试", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            testResult = null
                            testError = null
                        },
                        label = { Text("角色此刻在想什么？") },
                        placeholder = { Text("例如：她还记得第一次见面的地方吗？") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        colors = appTextFieldColors(),
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                    SoftActionButton(
                        text = if (testRunning) "正在回忆…" else "测试",
                        onClick = { runTest() },
                        enabled = query.isNotBlank() && !testRunning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                    when {
                        testError != null -> Text(
                            testError!!,
                            color = LocalAppColors.current.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        testResult != null -> {
                            Text(
                                "本次问题：${testResult!!.query}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                                modifier = Modifier.padding(bottom = AppSpacing.xs),
                            )
                            Text(
                                if (testResult!!.sourceCount == 0) {
                                    "没有命中历史片段，下面只有始终注入的固定记忆，约 ${testResult!!.tokenEstimate} tokens"
                                } else {
                                    "命中历史片段 ${testResult!!.sourceCount} 条" +
                                        "（消息 ${testResult!!.retrievedMessages}，依据 ${testResult!!.retrievedEvidence}）" +
                                        "，约 ${testResult!!.tokenEstimate} tokens"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (testResult!!.sourceCount == 0) {
                                Text(
                                    "“没有命中”是正常结果：问题里的人名或事件在已整理的聊天里没有出现。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                                    modifier = Modifier.padding(top = AppSpacing.xs),
                                )
                            }
                            Spacer(Modifier.height(AppSpacing.sm))
                            SelectionContainer {
                                Text(
                                    memoryCenterDisplayText(testResult!!.text).ifBlank { "没有找到可用的记忆" },
                                    style = MaterialTheme.typography.bodySmall,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                DialogDismissButton(text = "关闭", onClick = { testOpen = false })
            },
        )
    }
}

@Composable
private fun MemoryCenterSceneCard(
    location: String,
    date: String,
    time: String,
    participants: List<String>,
) {
    val accent = LocalAppColors.current.accent
    // Neutral surface with the accent kept on the label: washing the whole card
    // in the accent made a plain "where are we now" read as a highlighted status.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(AppSpacing.lg)) {
            Text(
                "此刻",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                memoryCenterDisplayText(location).ifBlank { "地点尚未记录" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            val storyTime = listOf(date, time).filter { it.isNotBlank() }.joinToString(" ")
            if (storyTime.isNotBlank()) {
                Spacer(Modifier.height(AppSpacing.xxs))
                Text(
                    memoryCenterDisplayText(storyTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                )
            }
            if (participants.isNotEmpty()) {
                Spacer(Modifier.height(AppSpacing.sm))
                Text(
                    "在场：${participants.joinToString("、")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
                )
            }
        }
    }
}

@Composable
private fun MemoryCenterMetricRow(label: String, value: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AppDimens.controlHeight)
            .padding(horizontal = AppSpacing.page),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MemoryCenterStoryPage(
    scope: MemoryCenterScope,
    api: MemoryCenterApi,
    summaries: List<MemoryCenterSummary>,
    onScan: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var sourceOpen by remember { mutableStateOf(false) }
    var sourceQuery by remember { mutableStateOf("") }
    var sourceLoading by remember { mutableStateOf(false) }
    var sourceMessages by remember { mutableStateOf<List<MemoryCenterMessage>>(emptyList()) }
    var sourceError by remember { mutableStateOf<String?>(null) }

    fun loadSourceMessages() {
        coroutineScope.launch {
            sourceLoading = true
            sourceError = null
            try {
                sourceMessages = withContext(Dispatchers.IO) {
                    api.searchMessages(scope, sourceQuery.trim(), 120)
                }
            } catch (exception: Exception) {
                sourceError = exception.message ?: "读取聊天原文失败"
            } finally {
                sourceLoading = false
            }
        }
    }

    val curated = remember(summaries) {
        memoryCenterCuratedStory(summaries)
    }
    val filtered = remember(summaries, curated, query) {
        val keyword = query.trim()
        if (keyword.isBlank()) curated else summaries.filter {
            it.text.contains(keyword, ignoreCase = true)
                || it.level.contains(keyword, ignoreCase = true)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    label = { Text("搜索剧情摘要") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = appTextFieldColors(),
                )
                Spacer(Modifier.width(AppSpacing.sm))
                TextButton(
                    onClick = {
                        sourceOpen = true
                        sourceQuery = query
                        loadSourceMessages()
                    },
                ) {
                    Text("原文", color = LocalAppColors.current.accent)
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                MemoryCenterInlineEmpty(
                    if (summaries.isEmpty()) "还没有剧情摘要，可以先收录旧聊天" else "没有匹配的摘要",
                )
            }
            item {
                SoftActionButton(
                    text = "收录旧聊天",
                    onClick = onScan,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            items(filtered, key = { "${it.level}:${it.startSeq}:${it.endSeq}" }) { summary ->
                MemoryCenterSummaryRow(summary, compact = false)
            }
        }
    }

    if (sourceOpen) {
        AlertDialog(
            onDismissRequest = { sourceOpen = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("聊天原文", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 470.dp)) {
                    OutlinedTextField(
                        value = sourceQuery,
                        onValueChange = { sourceQuery = it },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        label = { Text("搜索消息") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = appTextFieldColors(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    SoftActionButton(
                        text = if (sourceLoading) "读取中…" else "搜索",
                        onClick = { loadSourceMessages() },
                        enabled = !sourceLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    when {
                        sourceError != null -> Text(
                            sourceError!!,
                            color = LocalAppColors.current.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        sourceLoading -> Box(
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(AppDimens.iconBadgeGlyph),
                                color = LocalAppColors.current.accent,
                                strokeWidth = 2.dp,
                            )
                        }
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                        ) {
                            items(sourceMessages, key = { it.messageUid }) { message ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(AppRadius.sm),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AppAlpha.disabled),
                                ) {
                                    Column(modifier = Modifier.padding(AppSpacing.md)) {
                                        Text(
                                            "#${message.seq + 1} ${message.speaker.ifBlank {
                                                if (message.role == "user") "用户" else "角色"
                                            }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(AppSpacing.xs))
                                        Text(
                                            memoryCenterDisplayText(message.body),
                                            style = MaterialTheme.typography.labelSmall,
                                            lineHeight = 16.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                DialogDismissButton(text = "关闭", onClick = { sourceOpen = false })
            },
        )
    }
}

@Composable
private fun MemoryCenterSummaryRow(
    summary: MemoryCenterSummary,
    compact: Boolean,
) {
    var expanded by remember(summary) { mutableStateOf(false) }
    val motion = LocalFluentMotion.current
    val displayText = memoryCenterReadableSummary(summary.text)
        .ifBlank { "暂无摘要内容" }
    val bodyInset = AppSpacing.lg + AppDimens.iconMd + AppSpacing.md
    Column(modifier = Modifier.fillMaxWidth()) {
        // The toggle is the whole row, so the touch target keeps the same height whether the
        // summary is open or closed. The preview and the body sit outside it: the body is
        // selectable text, and a tap meant for a selection must not collapse the row.
        ListRow(
            icon = Icons.Outlined.History,
            title = memoryCenterLevelTitle(summary.level),
            subtitle = "#${summary.startSeq + 1}-#${summary.endSeq + 1}",
            onClick = { expanded = !expanded },
            trailing = {
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.disabled),
                    modifier = Modifier.size(AppDimens.iconMd),
                )
            },
        )
        if (!expanded) {
            Text(
                displayText,
                modifier = Modifier.padding(start = bodyInset, end = AppSpacing.lg),
                style = MaterialTheme.typography.labelSmall,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                maxLines = if (compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = motion.enterExpand(),
            exit = motion.exitShrink(),
        ) {
            // Whitespace separates the header from the body; the list convention
            // here draws no rules.
            SelectionContainer {
                Text(
                    displayText,
                    modifier = Modifier.padding(start = bodyInset, end = AppSpacing.lg, top = AppSpacing.xs),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
                )
            }
        }
    }
}

@Composable
private fun MemoryCenterPeoplePage(structured: MemoryCenterStructured?) {
    val facts = structured?.facts.orEmpty().filter {
        memoryCenterReadableValue(it.value).isNotBlank()
            && !memoryCenterIsStructuralSubject(it.subject)
    }
    val entities = structured?.entities.orEmpty().filter {
        it.entityType == "character"
            && it.canonicalName.isNotBlank()
            && !memoryCenterIsStructuralSubject(it.canonicalName)
    }
    val groups = remember(facts) {
        facts.groupBy { memoryCenterFriendlySubject(it.subject) }
            .mapValues { entries -> entries.value.distinctBy { it.predicate } }
            .toList()
            .sortedBy { it.first }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        item {
            MemoryCenterSectionTitle("已知角色")
        }
        if (entities.isEmpty()) {
            item { MemoryCenterInlineEmpty("还没有整理出角色") }
        } else {
            // Not keyed on the name: the extracted entities are not deduplicated,
            // and two characters sharing a name make the key repeat, which throws.
            items(entities.take(30)) { entity ->
                MemoryCenterEntityRow(entity)
            }
        }
        item {
            MemoryCenterSectionTitle("角色状态")
        }
        if (groups.isEmpty()) {
            item { MemoryCenterInlineEmpty("还没有整理出人物状态") }
        } else {
            items(groups, key = { it.first }) { (subject, entries) ->
                MemoryCenterPersonCard(subject, entries)
            }
        }
        item {
            MemoryCenterSectionTitle("角色关系")
        }
        val relationships = structured?.relationships.orEmpty()
        val relationFacts = memoryCenterRelationFallbackFacts(structured?.facts.orEmpty())
        if (relationships.isEmpty() && relationFacts.isEmpty()) {
            item { MemoryCenterInlineEmpty("还没有整理出人物关系") }
        } else {
            if (relationships.isEmpty()) {
                item {
                    MemoryCenterInlineEmpty("以下来自状态变量，细节比正式关系记录多")
                }
            }
            // Same reason as the entity list: a triple can repeat across extracts.
            items(relationships) { relation ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(relation.subject, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "  ${memoryCenterFriendlyPredicate(relation.predicate)}  ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(relation.objectValue, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(relationFacts) { fact ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        memoryCenterFriendlySubject(fact.subject),
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "  ${memoryCenterFriendlyPredicate(fact.predicate)}  ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        memoryCenterReadableValue(fact.value),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryCenterEntityRow(entity: MemoryCenterEntity) {
    val aliases = entity.aliases.filter { it != entity.canonicalName }
    ListRow(
        icon = Icons.Outlined.Person,
        title = entity.canonicalName,
        subtitle = aliases.takeIf { it.isNotEmpty() }?.joinToString("、", prefix = "别名："),
    )
}

@Composable
private fun MemoryCenterPersonCard(
    subject: String,
    entries: List<MemoryCenterFact>,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            subject,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(AppSpacing.sm))
        entries.forEachIndexed { index, fact ->
            if (index > 0) Spacer(Modifier.height(AppSpacing.sm))
            Text(
                memoryCenterFriendlyPredicate(fact.predicate),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
            )
            SelectionContainer {
                Text(
                    memoryCenterReadableValue(fact.value),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun MemoryCenterPlacesPage(
    structured: MemoryCenterStructured?,
    book: List<MemoryCenterBookEntry>,
) {
    val sceneLocations = structured?.scenes.orEmpty()
        .filter { it.location.isNotBlank() }
        .map { scene ->
            MemoryCenterBookEntry(
                entryUid = null,
                category = "location",
                title = scene.location,
                content = listOf(scene.date, scene.time, scene.participants.joinToString("、"))
                    .filter { it.isNotBlank() }
                    .joinToString("，"),
                importance = 3,
                pinned = false,
            )
        }
    val factLocations = structured?.facts.orEmpty()
        .filter {
            val predicate = it.predicate.lowercase(Locale.getDefault())
            predicate == "location" || predicate.endsWith(".location")
        }
        .map { fact ->
            MemoryCenterBookEntry(
                entryUid = null,
                category = "location",
                title = memoryCenterReadableValue(fact.value),
                content = memoryCenterFriendlySubject(fact.subject),
                importance = 3,
                pinned = false,
            )
        }
    val recentLocations = (sceneLocations + factLocations)
        .filter { it.title.isNotBlank() }
        .distinctBy { memoryCenterLocationKey(it.title) }
        .take(12)
    val locations = (book.filter { it.category == "location" } + factLocations + sceneLocations)
        .filter { it.title.isNotBlank() }
        .distinctBy { memoryCenterLocationKey(it.title) }
        .take(20)
    val factItems = structured?.facts.orEmpty()
        .filter { memoryCenterFriendlySubject(it.subject).contains("载具") }
        .map { fact ->
            MemoryCenterBookEntry(
                entryUid = null,
                category = "item",
                title = memoryCenterFriendlySubject(fact.subject),
                content = memoryCenterReadableValue(fact.value),
                importance = 3,
                pinned = false,
            )
        }
    val items = (book.filter { it.category == "item" } + factItems)
        .filter { it.title.isNotBlank() && it.content.isNotBlank() }
        .distinctBy { "${it.title}:${it.content}" }
        .take(40)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        item {
            MemoryCenterSceneCard(
                location = structured?.sceneLocation.orEmpty()
                    .ifBlank { factLocations.firstOrNull()?.title.orEmpty() },
                date = structured?.sceneDate.orEmpty(),
                time = structured?.sceneTime.orEmpty(),
                participants = structured?.participants.orEmpty(),
            )
        }
        item {
            MemoryCenterSectionTitle("最近出现的地点")
        }
        if (recentLocations.isEmpty()) {
            item { MemoryCenterInlineEmpty("还没有解析出地点") }
        } else {
            items(recentLocations) { entry ->
                MemoryCenterBookSummaryRow(entry, Icons.Outlined.Place)
            }
        }
        item {
            MemoryCenterSectionTitle("地点记忆")
        }
        if (locations.isEmpty()) {
            item { MemoryCenterInlineEmpty("还没有记录地点") }
        } else {
            items(locations) { entry ->
                MemoryCenterBookSummaryRow(entry, Icons.Outlined.Map)
            }
        }
        item {
            MemoryCenterSectionTitle("物品记忆")
        }
        if (items.isEmpty()) {
            item { MemoryCenterInlineEmpty("还没有记录物品") }
        } else {
            items(items) { entry ->
                MemoryCenterBookSummaryRow(entry, Icons.Outlined.Category)
            }
        }
    }
}

@Composable
private fun MemoryCenterBookSummaryRow(entry: MemoryCenterBookEntry, icon: ImageVector) {
    val pinnedMark: (@Composable () -> Unit)? = if (entry.pinned) {
        { Text("固定", style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.accent) }
    } else {
        null
    }
    ListRow(
        icon = icon,
        title = entry.title,
        subtitle = memoryCenterDisplayText(entry.content),
        trailing = pinnedMark,
    )
}

@Composable
private fun MemoryCenterBookPage(
    scope: MemoryCenterScope,
    entries: List<MemoryCenterBookEntry>,
    api: MemoryCenterApi,
    onChanged: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val categories = listOf("character" to "人物", "location" to "地点", "item" to "物品", "task" to "任务", "event" to "事件", "note" to "备注")
    var category by remember { mutableStateOf("character") }
    var editing by remember { mutableStateOf<MemoryCenterBookEntry?>(null) }
    var draftOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<MemoryCenterBookEntry?>(null) }
    var historyTarget by remember { mutableStateOf<MemoryCenterBookEntry?>(null) }
    var historyItems by remember { mutableStateOf<List<MemoryCenterBookRevision>>(emptyList()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "记忆本",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            IconButton(onClick = {
                editing = MemoryCenterBookEntry(
                    entryUid = null,
                    category = category,
                    title = "",
                    content = "",
                    importance = 3,
                    pinned = false,
                )
                draftOpen = true
            }) {
                Icon(Icons.Outlined.Add, "添加记忆", tint = LocalAppColors.current.accent)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            categories.forEach { (id, label) ->
                FilterChip(
                    selected = category == id,
                    onClick = { category = id },
                    label = { Text(label) },
                    // The console's selection language is an accent plate with a
                    // muted label; chips have no shared factory in AppControls.
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color.Transparent,
                        labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                        selectedContainerColor = LocalAppColors.current.accent.copy(alpha = AppAlpha.tint),
                        selectedLabelColor = LocalAppColors.current.accent,
                    ),
                )
            }
        }
        Spacer(Modifier.height(AppSpacing.md))
        val visible = entries.filter { it.category == category }
        if (visible.isEmpty()) {
            MemoryCenterInlineEmpty("这一类还没有记忆，点右上角添加")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                // Title is only a fallback for a missing uid, so it can repeat.
                items(visible) { entry ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            if (entry.pinned) {
                                Text("固定", style = MaterialTheme.typography.labelSmall, color = LocalAppColors.current.accent)
                            }
                        }
                        Spacer(Modifier.height(AppSpacing.xs))
                        Text(
                            memoryCenterDisplayText(entry.content),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            IconButton(onClick = {
                                historyTarget = entry
                                coroutineScope.launch {
                                    historyItems = withContext(Dispatchers.IO) {
                                        entry.entryUid?.let { api.history(scope, it) }.orEmpty()
                                    }
                                }
                            }) {
                                Icon(Icons.Outlined.History, "历史版本", modifier = Modifier.size(AppDimens.iconMd))
                            }
                            IconButton(onClick = {
                                editing = entry
                                draftOpen = true
                            }) {
                                Icon(Icons.Outlined.Edit, "编辑", modifier = Modifier.size(AppDimens.iconMd))
                            }
                            IconButton(onClick = { deleteTarget = entry }) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    "删除",
                                    tint = LocalAppColors.current.error,
                                    modifier = Modifier.size(AppDimens.iconMd),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (draftOpen && editing != null) {
        MemoryCenterBookEditDialog(
            initial = editing!!,
            onDismiss = { draftOpen = false; editing = null },
            onSave = { value ->
                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) { api.saveBook(scope, value) }
                        draftOpen = false
                        editing = null
                        onChanged()
                    } catch (exception: Exception) {
                        AppToast.show(context, exception.message ?: "保存失败", Toast.LENGTH_SHORT)
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "删除这条记忆",
            message = "只删除记忆本中的“${target.title}”，不会删除聊天原文。",
            confirmText = "删除",
            onConfirm = {
                val uid = target.entryUid
                deleteTarget = null
                if (uid != null) {
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) { api.deleteBook(scope, uid) }
                        onChanged()
                    }
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }

    historyTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { historyTarget = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(AppRadius.lg),
            title = { Text("修改历史", fontWeight = FontWeight.SemiBold) },
            text = {
                if (historyItems.isEmpty()) {
                    Text("还没有历史版本", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    ) {
                        items(historyItems, key = { it.id }) { revision ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(AppRadius.sm),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AppAlpha.disabled),
                            ) {
                                Column(modifier = Modifier.padding(AppSpacing.md)) {
                                    Text(
                                        "${revision.title}，${memoryCenterTime(revision.changedAt)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Spacer(Modifier.height(AppSpacing.xs))
                                    Text(
                                        memoryCenterDisplayText(revision.content),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceSecondary),
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (target.entryUid != null) {
                                        Spacer(Modifier.height(AppSpacing.sm))
                                        TextButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        api.restoreBook(scope, target.entryUid, revision.id)
                                                    }
                                                    historyTarget = null
                                                    onChanged()
                                                }
                                            },
                                        ) {
                                            Text("恢复此版本", color = LocalAppColors.current.accent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                DialogDismissButton(text = "关闭", onClick = { historyTarget = null })
            },
        )
    }
}

@Composable
private fun MemoryCenterBookEditDialog(
    initial: MemoryCenterBookEntry,
    onDismiss: () -> Unit,
    onSave: (MemoryCenterBookEntry) -> Unit,
) {
    var title by remember(initial) { mutableStateOf(initial.title) }
    var content by remember(initial) { mutableStateOf(initial.content) }
    var pinned by remember(initial) { mutableStateOf(initial.pinned) }
    var importance by remember(initial) { mutableIntStateOf(initial.importance) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(AppRadius.lg),
        title = {
            Text(
                if (initial.entryUid == null) "添加记忆" else "编辑记忆",
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = appTextFieldColors(),
                )
                Spacer(Modifier.height(AppSpacing.sm))
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    colors = appTextFieldColors(),
                )
                Spacer(Modifier.height(AppSpacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("固定记忆", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = pinned,
                        onCheckedChange = { pinned = it },
                        colors = appSwitchColors(),
                    )
                }
                Spacer(Modifier.height(AppSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("重要度", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    (1..5).forEach { value ->
                        Box(
                            modifier = Modifier
                                .sizeIn(minWidth = AppDimens.controlHeight, minHeight = AppDimens.controlHeight)
                                .clip(CircleShape)
                                .clickable { importance = value },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (importance == value) AppDimens.iconMd else AppDimens.iconSm)
                                    .background(
                                        if (value <= importance) LocalAppColors.current.accent
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.subtle),
                                        CircleShape,
                                    ),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            SoftActionButton(
                text = "保存",
                onClick = {
                    onSave(initial.copy(
                        title = title.trim(),
                        content = content.trim(),
                        pinned = pinned,
                        importance = importance,
                    ))
                },
                enabled = title.isNotBlank() && content.isNotBlank(),
                modifier = Modifier.height(AppDimens.controlHeightCompact),
            )
        },
        dismissButton = {
            DialogDismissButton(onClick = onDismiss)
        },
    )
}

// The engine answers with the value it now holds. A request the core ignored comes back with the
// old value, and reading that as success is how a switch that never reached the database still
// looks saved; the page reports the mismatch instead.
private fun memorySettingRefusal(field: Boolean?, requested: Boolean, label: String): String? = when {
    field == null -> "「$label」没有保存成功：记忆服务没有返回这一项。在控制台点「重启核心服务」后再试。"
    field != requested -> "「$label」没有保存成功，它还是原来的值。在控制台点「重启核心服务」后再试。"
    else -> null
}

private fun memoryBooleanAnswer(root: JSONObject, key: String): Boolean? =
    if (root.has(key)) root.optBoolean(key) else null

private fun memoryRetrievalAnswer(root: JSONObject, key: String): Boolean? {
    val retrieval = root.optJSONObject("retrieval") ?: return null
    return if (retrieval.has(key)) retrieval.optBoolean(key) else null
}

@Composable
private fun MemoryCenterSettingsPage(
    scope: MemoryCenterScope,
    api: MemoryCenterApi,
    overview: MemoryCenterOverview?,
    onChanged: () -> Unit,
    onDeleted: () -> Unit,
    onScan: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val nodeState by NodeState.state.collectAsState()
    var ai by remember { mutableStateOf(MemoryCenterAiPreferences.load(context)) }
    var aiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var aiModelMenuOpen by remember { mutableStateOf(false) }
    var aiProbing by remember { mutableStateOf(false) }
    var aiProbeMessage by remember { mutableStateOf<String?>(null) }
    var embedding by remember { mutableStateOf(MemoryEmbeddingPreferences.load(context)) }
    var enhancement by remember { mutableStateOf(MemoryEnhancementPreferences.load(context)) }
    var embeddingStatus by remember { mutableStateOf(MemoryCenterEmbeddingStatus()) }
    var embeddingLoading by remember { mutableStateOf(false) }
    var embeddingTesting by remember { mutableStateOf(false) }
    var embeddingSaving by remember { mutableStateOf(false) }
    var embeddingIndexing by remember { mutableStateOf(false) }
    var embeddingProgress by remember { mutableStateOf("") }
    var embeddingError by remember { mutableStateOf<String?>(null) }
    var enhancementSaving by remember { mutableStateOf(false) }
    var enhancementError by remember { mutableStateOf<String?>(null) }
    var rerankError by remember { mutableStateOf<String?>(null) }
    val memoryTaskRunning by MemoryTaskState.running.collectAsState()
    val memoryBusy = memoryTaskRunning != null
    // Null until the engine answers; the switch stays disabled rather than showing a wrong state.
    var autoExtract by remember { mutableStateOf<Boolean?>(null) }
    var autoExtractBusy by remember { mutableStateOf(false) }
    var extraPrompt by remember { mutableStateOf("") }
    var extraPromptBusy by remember { mutableStateOf(false) }
    // Set when the core could not confirm what it holds: a read that failed, or a core that does
    // not know these fields. Without it the page shows defaults that read as saved values.
    var settingsProblem by remember { mutableStateOf<String?>(null) }
    // Null until the engine answers, like the auto-extraction switch above.
    var redact by remember { mutableStateOf<Boolean?>(null) }
    var redactBusy by remember { mutableStateOf(false) }
    var deleteScopeConfirm by remember { mutableStateOf(false) }
    var clearAllConfirm by remember { mutableStateOf(false) }
    var diagnosticsLoading by remember { mutableStateOf(false) }
    var diagnosticsError by remember { mutableStateOf<String?>(null) }
    var diagnosticsLines by remember {
        mutableStateOf<List<MemoryCenterDiagnosticsLine>>(emptyList())
    }
    // Manual memory: one typed fact and one topic to forget.
    var manualFact by remember { mutableStateOf("") }
    var manualTopic by remember { mutableStateOf("") }
    var manualBusy by remember { mutableStateOf(false) }
    // Topics the reader asked to forget. They keep a subject out of every injection, so the
    // list has to be visible and reversible: a rule typed once must not become permanent
    // without showing what it is doing.
    var forgottenTopics by remember { mutableStateOf<List<String>>(emptyList()) }
    // Session sampling override: off unless the reader switched it on. The stored values are
    // re-sent to the service when this page opens, so a restarted core keeps an override the
    // reader had already applied.
    var samplingOverrideEnabled by remember {
        mutableStateOf(SettingsState.samplingOverride(context) != null)
    }
    var samplingOverrideBusy by remember { mutableStateOf(false) }
    var samplingOverrideStatus by remember { mutableStateOf("") }
    var overrideTemperature by remember { mutableStateOf("") }
    var overrideRepetition by remember { mutableStateOf("") }
    var overrideFrequency by remember { mutableStateOf("") }
    var overridePresence by remember { mutableStateOf("") }
    var overrideReasoningEffort by remember { mutableStateOf("") }

    LaunchedEffect(scope.scopeKey) {
        val stored = SettingsState.samplingOverride(context)
        if (stored == null) {
            samplingOverrideStatus = "当前未开启"
            return@LaunchedEffect
        }
        // Show what is stored in the fields again, so the reader edits the running override
        // instead of retyping it from scratch.
        stored.optString("reasoningEffort", "").takeIf { it.isNotEmpty() }?.let {
            overrideReasoningEffort = memoryCenterEffortText(it)
        }
        withContext(Dispatchers.IO) {
            runCatching { api.setSamplingOverride(stored) }
        }
        samplingOverrideStatus = "本会话已覆盖：" + stored.keys().asSequence().joinToString("，") { key ->
            val value = stored.opt(key)
            "$key=${if (value == null || value == org.json.JSONObject.NULL) "off" else value}"
        }
    }

    LaunchedEffect(scope.scopeKey) {
        embeddingLoading = true
        try {
            embeddingStatus = withContext(Dispatchers.IO) {
                api.embeddingStatus(scope, embedding)
            }
            embeddingError = null
        } catch (exception: Exception) {
            embeddingStatus = MemoryCenterEmbeddingStatus()
            if (embedding.retrieval) {
                embeddingError = exception.message ?: "读取向量状态失败"
            }
        } finally {
            embeddingLoading = false
        }
    }

    LaunchedEffect(scope.scopeKey) {
        // A core that is not running answers nothing here; an empty list is the same state the
        // page shows before the first read, and the purge button reports its own failure.
        forgottenTopics = runCatching {
            withContext(Dispatchers.IO) { api.forgottenTopics(scope) }
        }.getOrDefault(emptyList())
        // The engine owns these, so reloading the page must not lose them. Both come from the same
        // read: a second round trip could answer with the other field already moved.
        val settings = runCatching {
            withContext(Dispatchers.IO) { api.extractionSettings() }
        }.getOrNull()
        autoExtract = settings?.optBoolean("autoExtract", true)
        extraPrompt = settings?.optString("extraPrompt", "").orEmpty()
        redact = settings?.optBoolean("redact", false)
        settingsProblem = when {
            settings == null -> "读取整理设置失败，下面的开关与提示词显示的可能不是已保存的值。"
            !settings.has("extraPrompt") || !settings.has("autoExtract") || !settings.has("redact") ->
                "记忆服务没有返回整理设置，下面的开关与提示词可能保存不了。在控制台点「重启核心服务」后再试。"
            else -> null
        }
        // The engine owns the switches, so a page that overwrote them with what it last saved
        // would undo a change made elsewhere. They are read back and written to the local file
        // so the two agree again.
        val retrieval = settings?.optJSONObject("retrieval")
        if (retrieval != null) {
            embedding = embedding.copy(
                retrieval = retrieval.optBoolean("vector", embedding.retrieval),
            )
            enhancement = enhancement.copy(
                rewriteRetrieval = retrieval.optBoolean("rewrite", enhancement.rewriteRetrieval),
                rerankRetrieval = retrieval.optBoolean("rerank", enhancement.rerankRetrieval),
            )
            MemoryEmbeddingPreferences.save(context, embedding)
            MemoryEnhancementPreferences.save(context, enhancement)
        }
        // The providers live in the engine process: a core that restarted since the last visit
        // holds none of them, and would run without what the reader configured.
        withContext(Dispatchers.IO) {
            runCatching {
                api.configureEnhancement(enhancement)
                api.configureEmbedding(embedding)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        item {
            Spacer(Modifier.height(AppSpacing.xs))
            MemoryCenterSectionTitle("整理")
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    settingsProblem?.let { problem ->
                        Text(
                            problem,
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 15.sp,
                            color = LocalAppColors.current.error,
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("每轮自动整理", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (autoExtract == false) {
                                    "已关闭：仅在点「整理当前聊天」时调用模型。"
                                } else {
                                    "每收到一条回复后调用一次模型，把这一轮的人物、物品、事件与备注记进记忆。使用核心中已配置的模型，会消耗对应额度。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = AppAlpha.onSurfaceTertiary),
                            )
                        }
                        Switch(
                            checked = autoExtract ?: false,
                            // A null state means the engine has not answered yet. Showing it as off
                            // would claim a setting the page has not read.
                            enabled = autoExtract != null && !autoExtractBusy,
                            onCheckedChange = { enabled ->
                                autoExtractBusy = true
                                coroutineScope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            api.updateExtractionSettings(enabled)
                                        }
                                        val answer = memoryBooleanAnswer(result, "autoExtract")
                                        memorySettingRefusal(answer, enabled, "每轮自动整理")
                                            ?.let { settingsProblem = it }
                                        if (answer != null) autoExtract = answer
                                    } catch (exception: Exception) {
                                        AppToast.show(
                                            context,
                                            exception.message ?: "设置失败",
                                            Toast.LENGTH_SHORT,
                                        )
                                    } finally {
                                        autoExtractBusy = false
                                    }
                                }
                            },
                            colors = appSwitchColors(),
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.md))
                    OutlinedTextField(
                        value = extraPrompt,
                        onValueChange = { extraPrompt = it },
                        label = { Text("附加提示词") },
                        placeholder = { Text("留空表示不追加") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        // A cap, not a preference: an unbounded field grows taller than the space
                        // above the keyboard, and then no amount of scrolling can put the caret
                        // there. Bounded, the field scrolls inside itself and follows the caret.
                        maxLines = 8,
                        colors = appTextFieldColors(),
                    )
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        "这一段会追加在整理抽取与查询重写所用系统提示词之后，只对这台设备上的记忆整理生效；留空表示不追加。",
                        style = MaterialTheme.typography.labelSmall,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                            .copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SoftActionButton(
                            text = if (extraPromptBusy) "正在保存…" else "保存附加提示词",
                            onClick = {
                                coroutineScope.launch {
                                    extraPromptBusy = true
                                    try {
                                        val sent = extraPrompt.trim()
                                        val result = withContext(Dispatchers.IO) {
                                            api.updateExtraPrompt(sent)
                                        }
                                        // The core answers with what it stored. Confirming that
                                        // answer instead of the request is what keeps a save the
                                        // engine dropped from being reported as done.
                                        val stored = if (result.has("extraPrompt")) {
                                            result.optString("extraPrompt")
                                        } else {
                                            null
                                        }
                                        when {
                                            stored == null -> {
                                                settingsProblem = "这一段没有保存：记忆服务没有" +
                                                    "返回附加提示词。在控制台点「重启核心服务」后再试。"
                                            }
                                            // Trimming and the 8000-character cap happen on the
                                            // engine side; showing what it kept beats reporting a
                                            // mismatch the reader cannot see.
                                            stored != sent -> {
                                                extraPrompt = stored
                                                settingsProblem = "只保存了前 8000 字，输入框已按" +
                                                    "存下的内容更新。"
                                            }
                                            else -> {
                                                settingsProblem = null
                                                extraPrompt = stored
                                                AppToast.show(
                                                    context,
                                                    "附加提示词已保存",
                                                    Toast.LENGTH_SHORT,
                                                )
                                            }
                                        }
                                    } catch (exception: Exception) {
                                        AppToast.show(
                                            context,
                                            exception.message ?: "保存失败",
                                            Toast.LENGTH_SHORT,
                                        )
                                    } finally {
                                        extraPromptBusy = false
                                    }
                                }
                            },
                            enabled = !extraPromptBusy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("脱敏后送出", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "把露骨词句换成中性占位再交给模型。聊天记录本身不受影响，被替换的只是发出去的那一份；模型因内容拒答或被截断时可开启。",
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = AppAlpha.onSurfaceTertiary),
                            )
                        }
                        Switch(
                            checked = redact ?: false,
                            enabled = redact != null && !redactBusy,
                            onCheckedChange = { enabled ->
                                redactBusy = true
                                coroutineScope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) {
                                            api.updateRedaction(enabled)
                                        }
                                        val answer = memoryBooleanAnswer(result, "redact")
                                        memorySettingRefusal(answer, enabled, "脱敏后送出")
                                            ?.let { settingsProblem = it }
                                        if (answer != null) redact = answer
                                    } catch (exception: Exception) {
                                        AppToast.show(
                                            context,
                                            exception.message ?: "设置失败",
                                            Toast.LENGTH_SHORT,
                                        )
                                    } finally {
                                        redactBusy = false
                                    }
                                }
                            },
                            colors = appSwitchColors(),
                        )
                    }
                }
            }
        }
        item {
            MemoryCenterSectionTitle("模型服务")
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("整理使用这个服务", style = MaterialTheme.typography.bodySmall)
                            Text("整理总要用一个模型：关闭时用核心已配置的那个，打开后填好地址与模型，就改用下面这个。",
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                            )
                        }
                        Switch(
                            checked = ai.useForAnalysis,
                            onCheckedChange = {
                                ai = ai.copy(useForAnalysis = it)
                                // Stored on the spot: the other switches on this page save as they
                                // move, and this one deciding whether a service is used at all
                                // cannot wait for a save button that is not always on screen.
                                MemoryCenterAiPreferences.save(context, ai)
                            },
                            colors = appSwitchColors(),
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        memoryAnalysisTierText(ai.useForAnalysis, ai.endpoint, ai.model),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.accent,
                    )
                    if (ai.useForAnalysis || enhancement.rewriteRetrieval) {
                        Spacer(Modifier.height(AppSpacing.md))
                        OutlinedTextField(
                            value = ai.endpoint,
                            onValueChange = {
                                ai = ai.copy(endpoint = it)
                                aiModels = emptyList()
                                aiProbeMessage = null
                            },
                            label = { Text("服务地址") },
                            placeholder = { Text("https://api.example.com/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = ai.apiKey,
                            onValueChange = {
                                ai = ai.copy(apiKey = it)
                                aiModels = emptyList()
                                aiProbeMessage = null
                            },
                            label = { Text("API Key") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = ai.model,
                                onValueChange = { ai = ai.copy(model = it) },
                                label = { Text("模型名称") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = appTextFieldColors(),
                            )
                            if (aiModels.isNotEmpty()) {
                                Box {
                                    IconButton(onClick = { aiModelMenuOpen = true }) {
                                        Icon(
                                            Icons.Outlined.ArrowDropDown,
                                            contentDescription = "选择模型",
                                            tint = LocalAppColors.current.accent,
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = aiModelMenuOpen,
                                        onDismissRequest = { aiModelMenuOpen = false },
                                    ) {
                                        aiModels.forEach { name ->
                                            DropdownMenuItem(
                                                text = { Text(name) },
                                                onClick = {
                                                    ai = ai.copy(model = name)
                                                    aiModelMenuOpen = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(AppSpacing.sm))
                        SoftActionButton(
                            text = if (aiProbing) "正在测试…" else "测试连接并获取模型",
                            onClick = {
                                coroutineScope.launch {
                                    aiProbing = true
                                    aiProbeMessage = null
                                    try {
                                        val models = withContext(Dispatchers.IO) {
                                            api.probeModels(ai.endpoint, ai.apiKey)
                                        }
                                        aiModels = models
                                        aiProbeMessage = if (models.isEmpty()) {
                                            "连接成功，但这个地址没有返回模型列表，请手动填写模型名称"
                                        } else {
                                            "连接成功，找到 ${models.size} 个模型，点右侧箭头选择"
                                        }
                                    } catch (exception: Exception) {
                                        aiModels = emptyList()
                                        aiProbeMessage = exception.message ?: "连接失败"
                                    } finally {
                                        aiProbing = false
                                    }
                                }
                            },
                            enabled = !aiProbing && ai.endpoint.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        aiProbeMessage?.let { message ->
                            Spacer(Modifier.height(AppSpacing.xs))
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                            )
                        }
                        Spacer(Modifier.height(AppSpacing.sm))
                        Text(
                            "这一栏可以留空，整理与查询重写默认都用核心已配置的模型。填好后点下面的保存；整理是否改用它由上面的开关决定，查询重写由「检索」一节里的开关决定。",
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        // Next to the fields it stores: a save button further down read as if it
                        // belonged to whichever control happened to be above it.
                        SoftActionButton(
                            text = if (enhancementSaving) "正在保存…" else "保存模型服务",
                            onClick = {
                                coroutineScope.launch {
                                    enhancementSaving = true
                                    enhancementError = null
                                    val savedEnhancement = enhancement.copy(
                                        rewriteEndpoint = ai.endpoint.trim(),
                                        rewriteModel = ai.model.trim(),
                                        rewriteApiKey = ai.apiKey.trim(),
                                    )
                                    try {
                                        MemoryCenterAiPreferences.save(context, ai)
                                        MemoryEnhancementPreferences.save(context, savedEnhancement)
                                        withContext(Dispatchers.IO) {
                                            api.configureEnhancement(savedEnhancement)
                                        }
                                        enhancement = savedEnhancement
                                        AppToast.show(context, "模型服务已保存", Toast.LENGTH_SHORT)
                                    } catch (exception: Exception) {
                                        enhancementError = exception.message ?: "保存失败"
                                        AppToast.show(context, enhancementError ?: "保存失败", Toast.LENGTH_SHORT)
                                    } finally {
                                        enhancementSaving = false
                                    }
                                }
                            },
                            // Saving an address that is not complete is a valid state: the switch keeps
                            // its value and extraction keeps running on the model the core has.
                            enabled = !enhancementSaving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        enhancementError?.let { message ->
                            Spacer(Modifier.height(AppSpacing.xs))
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(AppSpacing.xs))
            MemoryCenterSectionTitle("检索")
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("查询重写", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                "关闭时用提问的原话检索。开启后默认使用本地改写（切词、同义与指代）；「模型服务」中填好地址与模型后改用该服务。",
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                            )
                        }
                        Switch(
                            checked = enhancement.rewriteRetrieval,
                            onCheckedChange = { enabled ->
                                enhancement = enhancement.copy(rewriteRetrieval = enabled)
                                enhancementError = null
                                coroutineScope.launch {
                                    try {
                                        MemoryEnhancementPreferences.save(context, enhancement)
                                        val result = withContext(Dispatchers.IO) {
                                            api.updateRetrievalModes(rewrite = enabled)
                                        }
                                        val answer = memoryRetrievalAnswer(result, "rewrite")
                                        memorySettingRefusal(answer, enabled, "查询重写")?.let {
                                            enhancementError = it
                                            enhancement = enhancement.copy(
                                                rewriteRetrieval = answer ?: !enabled,
                                            )
                                            MemoryEnhancementPreferences.save(context, enhancement)
                                        }
                                    } catch (exception: Exception) {
                                        enhancementError = exception.message ?: "设置失败"
                                    }
                                }
                            },
                            colors = appSwitchColors(),
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        memoryRewriteTierText(
                            enhancement.rewriteRetrieval,
                            enhancement.rewriteEndpoint,
                            enhancement.rewriteModel,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.accent,
                    )
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("向量检索", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                "关闭时完全不走向量。开启后先用设备本地向量；填好下面的地址与模型并测试通过，就改用这个向量服务。",
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                            )
                        }
                        Switch(
                            checked = embedding.retrieval,
                            onCheckedChange = { enabled ->
                                embedding = embedding.copy(retrieval = enabled)
                                embeddingError = null
                                if (!enabled) embeddingProgress = ""
                                coroutineScope.launch {
                                    try {
                                        MemoryEmbeddingPreferences.save(context, embedding)
                                        val result = withContext(Dispatchers.IO) {
                                            val modes = api.updateRetrievalModes(vector = enabled)
                                            api.configureEmbedding(embedding)
                                            embeddingStatus = api.embeddingStatus(scope, embedding)
                                            modes
                                        }
                                        val answer = memoryRetrievalAnswer(result, "vector")
                                        memorySettingRefusal(answer, enabled, "本地向量检索")?.let {
                                            embeddingError = it
                                            embedding = embedding.copy(retrieval = answer ?: !enabled)
                                            MemoryEmbeddingPreferences.save(context, embedding)
                                        }
                                    } catch (exception: Exception) {
                                        embeddingError = exception.message ?: "设置失败"
                                    }
                                }
                            },
                            colors = appSwitchColors(),
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        memoryVectorTierText(
                            embeddingStatus.tier,
                            embeddingStatus.model,
                            embeddingStatus.indexed,
                            embeddingStatus.total,
                            embeddingStatus.dimensions,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.accent,
                    )
                    if (embedding.retrieval) {
                        Spacer(Modifier.height(AppSpacing.md))
                        OutlinedTextField(
                            value = embedding.endpoint,
                            onValueChange = {
                                embedding = embedding.copy(endpoint = it)
                                embeddingError = null
                            },
                            label = { Text("Embedding 服务地址") },
                            placeholder = { Text("https://api.example.com/v1") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = embedding.model,
                            onValueChange = {
                                embedding = embedding.copy(model = it)
                                embeddingError = null
                            },
                            label = { Text("Embedding 模型") },
                            placeholder = { Text("text-embedding-3-small") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = embedding.apiKey,
                            onValueChange = {
                                embedding = embedding.copy(apiKey = it)
                                embeddingError = null
                            },
                            label = { Text("API Key（本地服务可留空）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("自动索引新消息", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "后台生成向量，不阻塞聊天。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                                )
                            }
                            Switch(
                                checked = embedding.autoIndex,
                                onCheckedChange = { embedding = embedding.copy(autoIndex = it) },
                                colors = appSwitchColors(),
                            )
                        }
                        if (embeddingLoading) {
                            Spacer(Modifier.height(AppSpacing.xs))
                            Text(
                                "正在读取向量索引状态…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                            )
                        }
                        if (embeddingProgress.isNotBlank()) {
                            Spacer(Modifier.height(AppSpacing.xs))
                            Text(
                                embeddingProgress,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalAppColors.current.accent,
                            )
                        }
                        embeddingError?.let { message ->
                            Spacer(Modifier.height(AppSpacing.xs))
                            Text(
                                message,
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(AppSpacing.sm))
                        SoftActionButton(
                            text = if (embeddingTesting) "正在测试连接…" else "测试连接",
                            onClick = {
                                coroutineScope.launch {
                                    embeddingTesting = true
                                    embeddingError = null
                                    try {
                                        val dimensions = withContext(Dispatchers.IO) {
                                            api.configureEmbedding(embedding)
                                            api.testEmbedding(embedding)
                                        }
                                        MemoryEmbeddingPreferences.save(context, embedding)
                                        embeddingProgress = "连接正常，返回 $dimensions 维向量"
                                        AppToast.show(context, "向量服务连接正常", Toast.LENGTH_SHORT)
                                    } catch (exception: Exception) {
                                        embeddingProgress = ""
                                        embeddingError = exception.message ?: "连接失败"
                                    } finally {
                                        embeddingTesting = false
                                    }
                                }
                            },
                            enabled = !embeddingTesting
                                && embedding.endpoint.isNotBlank()
                                && embedding.model.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Fields and their save button appear together: a save button under an empty
                    // card reads as a control that does nothing.
                    if (embedding.retrieval) {
                        Spacer(Modifier.height(AppSpacing.sm))
                        SoftActionButton(
                            text = if (embeddingSaving) "正在保存…" else "保存向量设置",
                            onClick = {
                                coroutineScope.launch {
                                    embeddingSaving = true
                                    embeddingError = null
                                    try {
                                        MemoryEmbeddingPreferences.save(context, embedding)
                                        val saved = withContext(Dispatchers.IO) {
                                            api.configureEmbedding(embedding)
                                            api.embeddingStatus(scope, embedding)
                                        }
                                        embeddingStatus = saved
                                        embeddingProgress = "向量设置已保存"
                                        AppToast.show(context, "向量设置已保存", Toast.LENGTH_SHORT)
                                    } catch (exception: Exception) {
                                        embeddingProgress = ""
                                        embeddingError = exception.message ?: "保存失败"
                                        AppToast.show(
                                            context,
                                            exception.message ?: "保存失败",
                                            Toast.LENGTH_SHORT,
                                        )
                                    } finally {
                                        embeddingSaving = false
                                    }
                                }
                            },
                            // Saving an incomplete address is a valid state: the switch stays on
                            // and the engine keeps running its local vectors.
                            enabled = !embeddingSaving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (embedding.retrieval) {
                        Spacer(Modifier.height(AppSpacing.sm))
                        SoftActionButton(
                            text = if (embeddingIndexing) "正在构建向量索引…" else "构建或更新向量索引",
                            onClick = {
                                coroutineScope.launch {
                                    embeddingIndexing = true
                                    embeddingProgress = "正在准备索引…"
                                    embeddingError = null
                                    try {
                                        MemoryEmbeddingPreferences.save(context, embedding)
                                        var processed = 0
                                        var iterations = 0
                                        while (iterations < 200) {
                                            val page = withContext(Dispatchers.IO) {
                                                api.configureEmbedding(embedding)
                                                api.indexEmbedding(
                                                    scope,
                                                    embedding,
                                                    maxBatches = 4,
                                                )
                                            }
                                            val count = page.optInt("processed", 0)
                                            val complete = page.optBoolean("complete", false)
                                            processed += count
                                            embeddingProgress = "已处理 $processed 条"
                                            if (complete || count <= 0) {
                                                break
                                            }
                                            iterations += 1
                                        }
                                        embeddingStatus = withContext(Dispatchers.IO) {
                                            api.embeddingStatus(scope, embedding)
                                        }
                                        embeddingProgress = if (processed == 0) {
                                            "索引已是最新状态"
                                        } else {
                                            "已处理 $processed 条，索引已更新"
                                        }
                                        AppToast.show(context, "向量索引已更新", Toast.LENGTH_SHORT)
                                    } catch (exception: Exception) {
                                        embeddingProgress = ""
                                        embeddingError = exception.message ?: "构建失败"
                                        AppToast.show(
                                            context,
                                            exception.message ?: "构建失败",
                                            Toast.LENGTH_SHORT,
                                        )
                                    } finally {
                                        embeddingIndexing = false
                                    }
                                }
                            },
                            enabled = !embeddingIndexing && embedding.providerEnabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("结果重排", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                "关闭时保持检索得到的顺序。开启后先用本地重排；填好下面的地址与模型就改用这个重排服务。",
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                            )
                        }
                        Switch(
                            checked = enhancement.rerankRetrieval,
                            onCheckedChange = { enabled ->
                                enhancement = enhancement.copy(rerankRetrieval = enabled)
                                rerankError = null
                                coroutineScope.launch {
                                    try {
                                        MemoryEnhancementPreferences.save(context, enhancement)
                                        val result = withContext(Dispatchers.IO) {
                                            api.updateRetrievalModes(rerank = enabled)
                                        }
                                        val answer = memoryRetrievalAnswer(result, "rerank")
                                        memorySettingRefusal(answer, enabled, "结果重排")?.let {
                                            rerankError = it
                                            enhancement = enhancement.copy(
                                                rerankRetrieval = answer ?: !enabled,
                                            )
                                            MemoryEnhancementPreferences.save(context, enhancement)
                                        }
                                    } catch (exception: Exception) {
                                        rerankError = exception.message ?: "设置失败"
                                    }
                                }
                            },
                            colors = appSwitchColors(),
                        )
                    }
                    Spacer(Modifier.height(AppSpacing.xs))
                    Text(
                        memoryRerankTierText(
                            enhancement.rerankRetrieval,
                            enhancement.rerankEndpoint,
                            enhancement.rerankModel,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.accent,
                    )
                    if (enhancement.rerankRetrieval) {
                        Spacer(Modifier.height(AppSpacing.md))
                        OutlinedTextField(
                            value = enhancement.rerankEndpoint,                            onValueChange = {
                                enhancement = enhancement.copy(rerankEndpoint = it)
                                rerankError = null
                            },
                            label = { Text("Rerank 服务地址") },
                            placeholder = { Text("https://api.example.com/v1/rerank") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = enhancement.rerankModel,
                            onValueChange = {
                                enhancement = enhancement.copy(rerankModel = it)
                                rerankError = null
                            },
                            label = { Text("Rerank 模型") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = enhancement.rerankApiKey,
                            onValueChange = {
                                enhancement = enhancement.copy(rerankApiKey = it)
                                rerankError = null
                            },
                            label = { Text("API Key（本地服务可留空）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        SoftActionButton(
                            text = if (enhancementSaving) "正在保存…" else "保存重排设置",
                            onClick = {
                                coroutineScope.launch {
                                    enhancementSaving = true
                                    rerankError = null
                                    try {
                                        MemoryEnhancementPreferences.save(context, enhancement)
                                        withContext(Dispatchers.IO) {
                                            api.configureEnhancement(enhancement)
                                        }
                                        AppToast.show(context, "重排设置已保存", Toast.LENGTH_SHORT)
                                    } catch (exception: Exception) {
                                        rerankError = exception.message ?: "保存失败"
                                        AppToast.show(context, rerankError ?: "保存失败", Toast.LENGTH_SHORT)
                                    } finally {
                                        enhancementSaving = false
                                    }
                                }
                            },
                            // An incomplete address is a valid state: the switch stays on and the
                            // engine reorders locally.
                            enabled = !enhancementSaving,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    rerankError?.let { message ->
                        Spacer(Modifier.height(AppSpacing.xs))
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(AppSpacing.xs))
            MemoryCenterSectionTitle("兼容性检查")
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    Text(
                        "检查采样参数冲突与记忆功能重叠。",
                        style = MaterialTheme.typography.labelSmall,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                    SoftActionButton(
                        text = if (diagnosticsLoading) "正在检查…" else "检查",
                        onClick = {
                            coroutineScope.launch {
                                diagnosticsLoading = true
                                diagnosticsError = null
                                diagnosticsLines = emptyList()
                                try {
                                    if (nodeState != NodeState.State.RUNNING) {
                                        diagnosticsError = "核心服务未启动，暂时无法使用记忆中心"
                                    } else {
                                        val root = withContext(Dispatchers.IO) { api.diagnostics() }
                                        diagnosticsLines = memoryCenterDiagnosticsLines(root)
                                    }
                                } catch (exception: Exception) {
                                    diagnosticsError = exception.message ?: "检查失败"
                                } finally {
                                    diagnosticsLoading = false
                                }
                            }
                        },
                        enabled = !diagnosticsLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    diagnosticsError?.let { message ->
                        Spacer(Modifier.height(AppSpacing.xs))
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    diagnosticsLines.forEach { line ->
                        Spacer(Modifier.height(AppSpacing.sm))
                        Text(
                            line.text,
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 15.sp,
                            color = if (line.isWarning) {
                                LocalAppColors.current.warning
                            } else {
                                MaterialTheme.colorScheme.onSurface
                                    .copy(alpha = AppAlpha.onSurfaceSecondary)
                            },
                        )
                    }
                }
            }
        }
        item {
            MemoryCenterSectionTitle("会话级采样覆盖")
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    Text(
                        "默认关闭。开启后只在本会话内改写这些采样参数，预设本身不会被修改；"
                            + "留空表示不动，填 off 表示把这个参数整个去掉。",
                        style = MaterialTheme.typography.labelSmall,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "本会话覆盖采样参数",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "关闭时完全不碰请求里的采样参数。",
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                            )
                        }
                        Switch(
                            checked = samplingOverrideEnabled,
                            onCheckedChange = { enabled ->
                                samplingOverrideEnabled = enabled
                                if (!enabled) {
                                    coroutineScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                api.setSamplingOverride(null)
                                                SettingsState.setSamplingOverride(context, null)
                                            }
                                            samplingOverrideStatus = "当前未开启"
                                            AppToast.show(context, "已关闭会话覆盖", Toast.LENGTH_SHORT)
                                            onChanged()
                                        } catch (exception: Exception) {
                                            AppToast.show(context, exception.message ?: "关闭失败", Toast.LENGTH_SHORT)
                                        }
                                    }
                                }
                            },
                            colors = appSwitchColors(),
                        )
                    }
                    if (samplingOverrideEnabled) {
                        Spacer(Modifier.height(AppSpacing.md))
                        OutlinedTextField(
                            value = overrideTemperature,
                            onValueChange = { overrideTemperature = it },
                            label = { Text("温度") },
                            placeholder = { Text("例如 0.8，留空不动") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = overrideRepetition,
                            onValueChange = { overrideRepetition = it },
                            label = { Text("重复惩罚") },
                            placeholder = { Text("off = 去掉，留空不动") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = overrideFrequency,
                            onValueChange = { overrideFrequency = it },
                            label = { Text("频率惩罚") },
                            placeholder = { Text("例如 0，留空不动") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = overridePresence,
                            onValueChange = { overridePresence = it },
                            label = { Text("存在惩罚") },
                            placeholder = { Text("例如 0，留空不动") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        OutlinedTextField(
                            value = overrideReasoningEffort,
                            onValueChange = { overrideReasoningEffort = it },
                            label = { Text("推理强度") },
                            placeholder = { Text("低 / 中 / 高，off = 不发送，留空不动") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = appTextFieldColors(),
                        )
                        Spacer(Modifier.height(AppSpacing.md))
                        SoftActionButton(
                            text = if (samplingOverrideBusy) "正在应用…" else "应用",
                            onClick = {
                                val override = memoryCenterSamplingOverride(
                                    overrideTemperature,
                                    overrideRepetition,
                                    overrideFrequency,
                                    overridePresence,
                                    overrideReasoningEffort,
                                )
                                if (override == null) {
                                    AppToast.show(
                                        context,
                                        "至少填一项；数值项要填数字或 off，推理强度填 低/中/高",
                                        Toast.LENGTH_SHORT,
                                    )
                                    return@SoftActionButton
                                }
                                coroutineScope.launch {
                                    samplingOverrideBusy = true
                                    try {
                                        // The core echoes the override it now holds. Reporting the
                                        // override as applied off the request alone claims a state
                                        // nothing has confirmed.
                                        val stored = withContext(Dispatchers.IO) {
                                            val answer = api.setSamplingOverride(override)
                                            answer.optJSONObject("override") != null
                                        }
                                        if (stored) {
                                            SettingsState.setSamplingOverride(context, override)
                                            AppToast.show(context, "已在本会话生效", Toast.LENGTH_SHORT)
                                            onChanged()
                                        } else {
                                            AppToast.show(
                                                context,
                                                "核心没有接受这组参数，本次没有覆盖",
                                                Toast.LENGTH_SHORT,
                                            )
                                        }
                                    } catch (exception: Exception) {
                                        AppToast.show(context, exception.message ?: "应用失败", Toast.LENGTH_SHORT)
                                    } finally {
                                        samplingOverrideBusy = false
                                    }
                                }
                            },
                            enabled = !samplingOverrideBusy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(AppSpacing.sm))
                        Text(
                            samplingOverrideStatus,
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                        )
                    }
                }
            }
        }
        item {
            MemoryCenterSectionTitle("手动记忆")
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    Text(
                        "写下来的内容与自动整理的记忆一起参与注入。事实写成「主体.谓词 = 值」。",
                        style = MaterialTheme.typography.labelSmall,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                    )
                    Spacer(Modifier.height(AppSpacing.md))
                    OutlinedTextField(
                        value = manualFact,
                        onValueChange = { manualFact = it },
                        label = { Text("新增事实") },
                        placeholder = { Text("例如：阿戊.location = 北门客栈") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = appTextFieldColors(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    SoftActionButton(
                        text = "写入事实",
                        onClick = {
                            val parsed = memoryCenterParseFact(manualFact)
                            if (parsed == null) {
                                AppToast.show(context, "按「主体.谓词 = 值」填写", Toast.LENGTH_SHORT)
                                return@SoftActionButton
                            }
                            coroutineScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        api.writeFact(scope, parsed.subject, parsed.predicate, parsed.value)
                                    }
                                    manualFact = ""
                                    AppToast.show(context, "已写入", Toast.LENGTH_SHORT)
                                    onChanged()
                                } catch (exception: Exception) {
                                    AppToast.show(context, exception.message ?: "写入失败", Toast.LENGTH_SHORT)
                                }
                            }
                        },
                        enabled = manualFact.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.lg))
                    OutlinedTextField(
                        value = manualTopic,
                        onValueChange = { manualTopic = it },
                        label = { Text("忘掉一个话题") },
                        placeholder = { Text("例如：北门客栈") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = appTextFieldColors(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    SoftActionButton(
                        text = if (manualBusy) "正在遗忘…" else "按话题遗忘",
                        onClick = {
                            coroutineScope.launch {
                                manualBusy = true
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        api.purgeTopic(scope, manualTopic.trim())
                                    }
                                    val facts = result.optInt("facts", 0)
                                    val entries = result.optInt("entries", 0)
                                    forgottenTopics = memoryCenterTopicList(result.optJSONArray("topics"))
                                    val forgotten = manualTopic.trim()
                                    manualTopic = ""
                                    AppToast.show(
                                        context,
                                        if (facts + entries == 0) {
                                            "已忘记「$forgotten」"
                                        } else {
                                            "已忘记「$forgotten」：$facts 条事实、$entries 条条目不再进入记忆"
                                        },
                                        Toast.LENGTH_SHORT,
                                    )
                                    onChanged()
                                } catch (exception: Exception) {
                                    AppToast.show(context, exception.message ?: "清除失败", Toast.LENGTH_SHORT)
                                } finally {
                                    manualBusy = false
                                }
                            }
                        },
                        enabled = manualTopic.trim().length >= 2 && !manualBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(AppSpacing.sm))
                    Text(
                        "只影响注入：手写与整理出的条目不会被删除，解除遗忘后重新整理即可补回",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (forgottenTopics.isNotEmpty()) {
                        Spacer(Modifier.height(AppSpacing.md))
                        Text(
                            "已遗忘的话题",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
                        )
                        forgottenTopics.forEach { topic ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    topic,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            try {
                                                val remaining = withContext(Dispatchers.IO) {
                                                    api.restoreTopic(scope, topic)
                                                }
                                                forgottenTopics = remaining
                                                AppToast.show(context, "已恢复「$topic」", Toast.LENGTH_SHORT)
                                                onChanged()
                                            } catch (exception: Exception) {
                                                AppToast.show(context, exception.message ?: "恢复失败", Toast.LENGTH_SHORT)
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = "恢复 $topic",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            MemoryCenterSectionTitle("整理与维护")
        }
        item {
            SoftActionButton(
                text = when (val task = memoryTaskRunning) {
                    null -> "整理当前聊天"
                    // A batch is one model call and can take a minute; the helper says what the
                    // first one is doing instead of counting from zero.
                    else -> "正在整理 " + memoryTaskProgressText(task.batches, task.processed)
                },
                onClick = { startMemoryPass(scope, api, context) },
                enabled = !memoryBusy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            TextButton(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                Text("整理所有旧聊天", color = LocalAppColors.current.accent)
            }
        }
        item {
            TextButton(
                onClick = { deleteScopeConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("删除当前聊天记忆", color = LocalAppColors.current.error)
            }
        }
        item {
            TextButton(
                onClick = { clearAllConfirm = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("清空全部聊天记忆", color = LocalAppColors.current.error)
            }
        }
        item {
            Text(
                "当前聊天已有 ${overview?.messages ?: scope.messageCount} 条消息进入记忆索引。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
            )
        }
    }

    if (deleteScopeConfirm) {
        ConfirmDialog(
            title = "删除当前聊天记忆",
            message = "会删除当前分支已有记忆，后续同步不会恢复这些内容；之后的新消息仍会继续记录。聊天原文不受影响。",
            confirmText = "删除",
            onConfirm = {
                deleteScopeConfirm = false
                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) { api.deleteScope(scope) }
                        onDeleted()
                    } catch (exception: Exception) {
                        AppToast.show(context, exception.message ?: "删除失败", Toast.LENGTH_SHORT)
                    }
                }
            },
            onDismiss = { deleteScopeConfirm = false },
        )
    }

    if (clearAllConfirm) {
        ConfirmDialog(
            title = "清空全部聊天记忆",
            message = "会删除所有聊天的已有记忆，后续同步不会恢复这些内容；之后的新消息仍会继续记录。聊天原文、角色卡和核心设置不受影响。",
            confirmText = "全部清空",
            onConfirm = {
                clearAllConfirm = false
                coroutineScope.launch {
                    try {
                        withContext(Dispatchers.IO) { api.clearAll() }
                        onDeleted()
                    } catch (exception: Exception) {
                        AppToast.show(context, exception.message ?: "清空失败", Toast.LENGTH_SHORT)
                    }
                }
            },
            onDismiss = { clearAllConfirm = false },
        )
    }
}

@Composable
private fun MemoryCenterSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun MemoryCenterInlineEmpty(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.sm),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = AppAlpha.disabled),
    ) {
        Text(
            text,
            modifier = Modifier.padding(AppSpacing.md),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = AppAlpha.onSurfaceTertiary),
        )
    }
}

@Composable
private fun MemoryCenterEmpty(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            modifier = Modifier.padding(AppSpacing.xxl),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = AppAlpha.onSurfaceTertiary),
        )
    }
}

/**
 * Re-sends what the reader configured to a core that has just started.
 *
 * The engine keeps the vector, rewrite and rerank providers in process memory, so a core that
 * was restarted — by a backup, by a core update, or by the reader — comes up without them and
 * quietly runs the local layers until the memory page happens to be opened. The switch state
 * lives in the engine's own database and survives, but the providers do not, which is why both
 * are sent together on every start. Failures are swallowed on purpose: a core that is not ready
 * yet must not be the reason a start reports a problem.
 */
internal suspend fun pushMemoryProviderSettings(context: Context, port: Int) {
    val embedding = MemoryEmbeddingPreferences.load(context)
    val enhancement = MemoryEnhancementPreferences.load(context)
    val api = MemoryCenterApi(port)
    runCatching { api.configureEnhancement(enhancement) }
    runCatching { api.configureEmbedding(embedding) }
    runCatching {
        api.updateRetrievalModes(
            vector = embedding.retrieval,
            rewrite = enhancement.rewriteRetrieval,
            rerank = enhancement.rerankRetrieval,
        )
    }
}
