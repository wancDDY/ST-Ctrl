package com.tavern.app.console.pages

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Extension install state that outlives the extensions page.
 *
 * The install used to run in the page's own composition scope, so switching hub tabs or
 * leaving the page cancelled the download — while the dialog's "background" button only
 * hid the report, which read as an install that kept running. Holding the job and its
 * progress here keeps the work alive and lets any surface show it.
 *
 * Replacing an installed extension goes through here as well. It is the same download and the
 * same unpack, and running it in the page meant an update the reader walked away from was
 * cancelled without a word — the one operation where leaving was most likely.
 */
object ExtensionInstall {

    /**
     * One update from the install pipeline. [log] is set when the step is worth a line of
     * its own in the terminal; routine progress carries numbers instead, so a long transfer
     * does not push everything else out of the pane.
     */
    data class Step(
        val phase: String,
        val fraction: Float? = null,
        val downloaded: Long = 0L,
        /** Zero when the server reports no length, which a generated archive does not. */
        val total: Long = 0L,
        val log: String? = null,
    )

    data class Progress(
        val url: String,
        val name: String,
        val phase: String,
        /** Measured fraction, or null while the phase cannot be measured. */
        val fraction: Float?,
        val startedAt: Long,
        val downloaded: Long = 0L,
        val total: Long = 0L,
        /** The version this run replaces, when it replaces one. */
        val updateFrom: String? = null,
        val updateTo: String? = null,
    ) {
        /** The operation as it would be typed, which is what the terminal opens with. */
        val command: String
            get() = when {
                updateFrom == null -> "安装 $name"
                updateTo == null -> "更新 $name（当前 v$updateFrom）"
                else -> "更新 $name  v$updateFrom → v$updateTo"
            }
    }

    sealed interface Result {
        data class Done(
            val name: String,
            val updateFrom: String? = null,
            val updateTo: String? = null,
        ) : Result

        data class Failed(val message: String) : Result
        data object Cancelled : Result
    }

    // Independent of any composition: the install must survive navigating away.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** Output lines of the run in [progress], oldest first. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    private var job: Job? = null

    // A terminal that scrolls is a terminal nobody reads from the top, so only the tail is kept.
    private const val LOG_LIMIT = 200

    val isRunning: Boolean get() = job?.isActive == true

    fun start(
        ctx: Context,
        url: String,
        coreDir: File,
        displayName: String,
        updateFrom: String? = null,
        updateTo: String? = null,
    ) {
        if (isRunning) return
        val appContext = ctx.applicationContext
        _result.value = null
        _log.value = emptyList()
        _progress.value = Progress(
            url,
            displayName,
            "正在准备…",
            0f,
            System.currentTimeMillis(),
            updateFrom = updateFrom,
            updateTo = updateTo,
        )
        job = scope.launch {
            val outcome = installExtension(appContext, url, coreDir) { step ->
                _progress.value = _progress.value?.copy(
                    phase = step.phase,
                    fraction = step.fraction,
                    downloaded = step.downloaded,
                    total = step.total,
                )
                step.log?.let { line -> _log.value = (_log.value + line).takeLast(LOG_LIMIT) }
            }
            outcome.fold(
                onSuccess = { name ->
                    _progress.value = null
                    _result.value = Result.Done(name, updateFrom, updateTo)
                    // The tavern keeps its own extension list; the page has to reload to
                    // pick the new one up whether or not it is on screen right now.
                    com.tavern.app.webview.TavernRefresh.request()
                },
                onFailure = { error ->
                    _progress.value = null
                    _result.value = Result.Failed(error.message ?: "安装失败")
                },
            )
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _progress.value = null
        _result.value = Result.Cancelled
    }

    fun dismissResult() {
        _result.value = null
    }
}
