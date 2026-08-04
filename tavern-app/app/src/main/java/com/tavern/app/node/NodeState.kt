package com.tavern.app.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NodeState {

    enum class State { IDLE, STARTING, RUNNING, STOPPING, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _port = MutableStateFlow(8000)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _phaseText = MutableStateFlow("")
    val phaseText: StateFlow<String> = _phaseText.asStateFlow()

    // ── Cross-process: primary (:node) sends, secondary (main) receives ──

    private var ctxRef: java.lang.ref.WeakReference<Context>? = null

    /** Called by TavernForegroundService (:node process) — enables broadcast sending */
    fun initAsPrimary(ctx: Context) {
        ctxRef = java.lang.ref.WeakReference(ctx.applicationContext)
    }

    /** Called by MainActivity (main process) — registers receiver to sync local state */
    @Suppress("DEPRECATION")
    fun initAsSecondary(ctx: Context): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                _state.value = try { State.valueOf(intent.getStringExtra("state") ?: "IDLE") }
                    catch (_: Exception) { State.IDLE }
                _port.value = intent.getIntExtra("port", 8000)
                _progress.value = intent.getFloatExtra("progress", 0f)
                _phaseText.value = intent.getStringExtra("phase") ?: ""
                _lastError.value = intent.getStringExtra("error")
            }
        }
        ctx.registerReceiver(receiver, IntentFilter("com.tavern.app.NODE_STATE"),
            Context.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    private fun broadcast() {
        ctxRef?.get()?.let { ctx ->
            val intent = Intent("com.tavern.app.NODE_STATE").apply {
                setPackage(ctx.packageName)
                putExtra("state", _state.value.name)
                putExtra("port", _port.value)
                putExtra("progress", _progress.value)
                putExtra("phase", _phaseText.value)
                _lastError.value?.let { putExtra("error", it) }
            }
            ctx.sendBroadcast(intent)
        }
    }

    // ── State setters ──

    fun setStarting() {
        _state.value = State.STARTING
        _lastError.value = null
        _progress.value = 0f
        _phaseText.value = ""
        broadcast()
    }

    fun setProgress(progress: Float, phase: String) {
        _progress.value = progress.coerceIn(0f, 1f)
        _phaseText.value = phase
        broadcast()
    }

    fun setRunning(port: Int) {
        _state.value = State.RUNNING
        _port.value = port
        _lastError.value = null
        _progress.value = 1f
        _phaseText.value = ""
        broadcast()
    }

    fun setStopping() {
        _state.value = State.STOPPING
        broadcast()
    }

    fun setIdle() {
        _state.value = State.IDLE
        _lastError.value = null
        broadcast()
    }

    fun setError(error: String) {
        _state.value = State.ERROR
        _lastError.value = error
        broadcast()
    }
}
