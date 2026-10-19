package com.tavern.app.node

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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

    // False while the current phase has no measurable fraction, so the UI shows
    // an indeterminate bar instead of a number it would have to invent.
    private val _progressKnown = MutableStateFlow(true)
    val progressKnown: StateFlow<Boolean> = _progressKnown.asStateFlow()

    // Timestamp of the current STARTING boot, to drive the "enter early"
    // prompt at 2/3 of the startup timeout.
    private val _bootStartedAt = MutableStateFlow(0L)
    val bootStartedAt: StateFlow<Long> = _bootStartedAt.asStateFlow()

    // Wall-clock time the service reached RUNNING. Drives the uptime readout;
    // zero whenever the service is not running.
    private val _runningSince = MutableStateFlow(0L)
    val runningSince: StateFlow<Long> = _runningSince.asStateFlow()

    private val _startInFlight = MutableStateFlow(false)
    val startInFlight: StateFlow<Boolean> = _startInFlight.asStateFlow()

    // Cross-process: primary (:node) sends, secondary (main) receives

    private var ctxRef: java.lang.ref.WeakReference<Context>? = null

    /** Called by TavernForegroundService (:node process) — enables broadcast sending */
    fun initAsPrimary(ctx: Context) {
        ctxRef = java.lang.ref.WeakReference(ctx.applicationContext)
    }

    /** Called by MainActivity (main process) — registers receiver to sync local state */
    fun initAsSecondary(ctx: Context): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val incoming = try { State.valueOf(intent.getStringExtra("state") ?: "IDLE") }
                    catch (_: Exception) { State.IDLE }
                val intentPort = intent.getIntExtra("port", 8000)
                // A stop that finished after this process began a start reports IDLE
                // for a service that is already coming up. Applying it here made the
                // status page offer "start" mid-boot, and the start lock then dropped
                // every press of it; the attempt owns the state until it ends.
                if (_startInFlight.value && (incoming == State.IDLE || incoming == State.STOPPING)) {
                    return
                }
                // A broadcast from before a port change carries the old port. Applying it
                // while this process is booting the new one made the page load the old
                // address — and the service, still running the old port, agreed.
                if (_startInFlight.value && incoming == State.RUNNING &&
                    intentPort != com.tavern.app.console.SettingsState.nodePort()
                ) {
                    return
                }
                val previous = _state.value
                // The payload is applied before the state itself. StateFlow resumes a
                // collector on the main dispatcher inline, so anything that reacts to
                // RUNNING — a page reload, for one — reads these fields at the moment
                // the state is set. Setting the state first made such a reader see the
                // previous port, and the page then loaded the port the service had just
                // stopped listening on.
                _port.value = intent.getIntExtra("port", 8000)
                _progress.value = intent.getFloatExtra("progress", 0f)
                _phaseText.value = intent.getStringExtra("phase") ?: ""
                _progressKnown.value = intent.getBooleanExtra("progressKnown", true)
                _lastError.value = intent.getStringExtra("error")
                _bootStartedAt.value = intent.getLongExtra("bootStartedAt", 0L)
                _runningSince.value = intent.getLongExtra("runningSince", 0L)
                _state.value = incoming
                if (previous == State.ERROR && _state.value == State.RUNNING) {
                    com.tavern.app.log.TavernLog.boot('i', "服务随后启动成功，错误状态已恢复")
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx,
            receiver,
            IntentFilter("com.tavern.app.NODE_STATE"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
                putExtra("progressKnown", _progressKnown.value)
                putExtra("bootStartedAt", _bootStartedAt.value)
                putExtra("runningSince", _runningSince.value)
                _lastError.value?.let { putExtra("error", it) }
            }
            ctx.sendBroadcast(intent)
        }
    }

    // State setters

    fun setStarting() {
        _state.value = State.STARTING
        _lastError.value = null
        _progress.value = 0f
        _progressKnown.value = true
        _phaseText.value = ""
        _bootStartedAt.value = System.currentTimeMillis()
        broadcast()
    }

    fun setProgress(progress: Float, phase: String) {
        _progress.value = progress.coerceIn(0f, 1f)
        _progressKnown.value = true
        _phaseText.value = phase
        broadcast()
    }

    /**
     * Names the port the boot is aiming at before it is up. A port change makes the
     * previous run's value wrong for the whole boot, and the console shows this
     * field, so it has to move with the settings rather than with the last run.
     * [setRunning] overwrites it with the port the service actually bound.
     */
    fun setPort(port: Int) {
        _port.value = port
        broadcast()
    }

    /**
     * Reports a phase whose length cannot be measured. The caption still moves
     * as real work happens, but the bar goes indeterminate rather than parking
     * on a fraction nobody computed. [progress] stays as the position the bar
     * resumes from once the phase ends.
     */
    fun setUnmeasuredProgress(progress: Float, phase: String) {
        _progress.value = progress.coerceIn(0f, 1f)
        _progressKnown.value = false
        _phaseText.value = phase
        broadcast()
    }

    fun setRunning(port: Int) {
        val wasRunning = _state.value == State.RUNNING
        _state.value = State.RUNNING
        _port.value = port
        _lastError.value = null
        _progress.value = 1f
        _progressKnown.value = true
        _phaseText.value = ""
        _bootStartedAt.value = 0L
        // Uptime starts when the tavern first answers on /api/ping. setRunning is
        // called again by the health-check fallback, the heartbeat and broadcast
        // re-syncs; resetting here made the displayed uptime restart each time.
        if (!wasRunning) {
            _runningSince.value = System.currentTimeMillis()
        }
        broadcast()
    }

    fun setStopping() {
        _state.value = State.STOPPING
        broadcast()
    }

    /**
     * Re-sends the state this process already holds without changing it.
     *
     * The state is per-process: a service that is already serving keeps its own RUNNING
     * while a main process that was killed and recreated starts from IDLE. The service
     * used to answer a repeated start request by returning early, so the new main
     * process never learned the service was up and waited out the whole startup timeout.
     */
    fun rebroadcast() {
        broadcast()
    }

    fun setIdle() {
        _state.value = State.IDLE
        _lastError.value = null
        _bootStartedAt.value = 0L
        _runningSince.value = 0L
        broadcast()
    }

    /**
     * True while this process is running a start attempt.
     *
     * The state flow cannot carry this: every setter broadcasts, and an IDLE that
     * arrives late — the service finishing a stop after the attempt began — overwrites
     * STARTING in this process. The status page then offers "start" during a boot, the
     * user presses it, and the start lock drops the press without a trace.
     */
    fun beginStart() {
        _startInFlight.value = true
    }

    fun endStart() {
        _startInFlight.value = false
    }

    fun setError(error: String) {
        _state.value = State.ERROR
        _lastError.value = error
        _bootStartedAt.value = 0L
        _runningSince.value = 0L
        broadcast()
    }
}
