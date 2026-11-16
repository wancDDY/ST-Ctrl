package com.tavern.app.console

import com.tavern.app.console.AgentClient.MemoryTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The memory jobs the runtime reports, for the chip in the top bar.
 *
 * A rebuild and an index build start on the runtime's side, so this app never sees the moment
 * they begin: without asking, the only work the island could name was a pass this app dispatched
 * itself. Polling is the price of that — one localhost request every few seconds while the console
 * is open, which is cheaper than the alternative of the island being silently wrong.
 */
object MemoryRemoteTasks {

    private val _tasks = MutableStateFlow<List<MemoryTask>>(emptyList())
    val tasks: StateFlow<List<MemoryTask>> = _tasks

    suspend fun poll(port: Int) {
        _tasks.value = AgentClient.memoryTasks(port) ?: emptyList()
    }

    fun clear() {
        _tasks.value = emptyList()
    }

    /** The chip's label: one job names its chat, several count. */
    fun label(tasks: List<MemoryTask>): String {
        if (tasks.isEmpty()) return ""
        if (tasks.size > 1) return "${tasks.size} 个记忆任务"
        val task = tasks.first()
        val verb = when (task.kind) {
            "rebuild" -> "重新整理"
            "index" -> "建立索引"
            else -> "整理记忆"
        }
        return verb + " " + task.chatId.substringBefore(" - ")
    }
}
