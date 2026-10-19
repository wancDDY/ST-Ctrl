package com.tavern.app.webview

/** Outcome of letting the tavern page drive the memory extraction batches. */
data class MemoryDrainState(
    val ok: Boolean,
    val done: Boolean,
    val batches: Int,
    val processed: Int,
    val reason: String = "",
)

/**
 * The memory engine writes the extraction prompt, but running it means calling `generateRaw`,
 * which only exists inside the tavern page. The console owns no WebView, so the activity
 * registers a runner here while a page is loaded and clears it on teardown; a missing runner
 * is reported as `no-page` rather than thrown, because the reader can reach the memory centre
 * without ever opening the tavern.
 *
 * [onProgress] is called with the batch and message counts as they advance, so a caller that
 * survives longer than one screen can show the pass moving instead of a frozen label.
 */
object MemoryDrainBridge {
    @Volatile
    var runner: (suspend (Int, (Int, Int) -> Unit) -> MemoryDrainState)? = null

    val available: Boolean get() = runner != null

    suspend fun drain(
        maxBatches: Int,
        onProgress: (batches: Int, processed: Int) -> Unit = { _, _ -> },
    ): MemoryDrainState =
        runner?.invoke(maxBatches, onProgress) ?: MemoryDrainState(
            ok = false,
            done = false,
            batches = 0,
            processed = 0,
            reason = "no-page",
        )
}
