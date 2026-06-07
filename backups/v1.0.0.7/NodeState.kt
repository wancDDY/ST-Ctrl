package com.tavern.app.node

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object NodeState {

    enum class State {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING,
        ERROR
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _port = MutableStateFlow(8000)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    // Progress tracking for phased startup
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _phaseText = MutableStateFlow("")
    val phaseText: StateFlow<String> = _phaseText.asStateFlow()

    fun setStarting() {
        _state.value = State.STARTING
        _lastError.value = null
        _progress.value = 0f
        _phaseText.value = ""
    }

    fun setProgress(progress: Float, phase: String) {
        _progress.value = progress.coerceIn(0f, 1f)
        _phaseText.value = phase
    }

    fun setRunning(port: Int) {
        _state.value = State.RUNNING
        _port.value = port
        _progress.value = 1f
        _phaseText.value = ""
    }

    fun setStopping() {
        _state.value = State.STOPPING
    }

    fun setIdle() {
        _state.value = State.IDLE
    }

    fun setError(error: String) {
        _state.value = State.ERROR
        _lastError.value = error
    }
}
