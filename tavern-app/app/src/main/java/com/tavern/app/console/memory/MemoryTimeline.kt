package com.tavern.app.console.memory

import com.tavern.app.console.AgentClient

/**
 * One section of the memory timeline: a scene when one was recorded, otherwise a floor window.
 *
 * Listing entries flat, newest first, leaves a row that names its floor with no place to say when
 * that was or where. A scene carries the place, the in-story date and the people; grouping the
 * rows under it puts those on every line the reader sees.
 */
data class MemoryTimelineGroup(
    val from: Int,
    val to: Int,
    val scene: AgentClient.MemoryScene?,
    val entries: List<AgentClient.MemoryEntry>,
)

object MemoryTimeline {
    /** Window used only when the chat has no scene rows yet, so the list is still grouped. */
    const val BUCKET = 20

    fun group(
        entries: List<AgentClient.MemoryEntry>,
        scenes: List<AgentClient.MemoryScene>,
        bucket: Int = BUCKET,
    ): List<MemoryTimelineGroup> {
        val step = bucket.coerceAtLeast(1)
        val knownScenes = scenes.filter { it.from >= 0 }.sortedBy { it.from }
        val placed = HashMap<Int, MutableList<AgentClient.MemoryEntry>>()
        val loose = mutableListOf<AgentClient.MemoryEntry>()

        for (entry in entries.filter { it.from >= 0 }.sortedBy { it.from }) {
            val index = knownScenes.indexOfLast { scene ->
                scene.from <= entry.from && (scene.to < 0 || entry.from <= scene.to)
            }
            if (index >= 0) {
                placed.getOrPut(index) { mutableListOf() }.add(entry)
            } else {
                loose.add(entry)
            }
        }

        val groups = mutableListOf<MemoryTimelineGroup>()
        knownScenes.forEachIndexed { index, scene ->
            // Every scene shows, even one whose facts were all retired: the stretch happened.
            groups += MemoryTimelineGroup(
                from = scene.from,
                to = if (scene.to >= scene.from) scene.to else scene.from,
                scene = scene,
                // Inside a scene the rows read in the order they happened.
                entries = placed[index].orEmpty().sortedBy { it.from },
            )
        }

        // A scene covers only what the pass saw; rows it did not claim fall into floor windows so
        // nothing silently disappears from the page.
        val buckets = LinkedHashMap<Int, MutableList<AgentClient.MemoryEntry>>()
        for (entry in loose) {
            val start = (entry.from / step) * step
            buckets.getOrPut(start) { mutableListOf() }.add(entry)
        }
        buckets.forEach { (start, rows) ->
            groups += MemoryTimelineGroup(
                from = start,
                to = start + step - 1,
                scene = null,
                entries = rows.sortedBy { it.from },
            )
        }

        val floatless = entries.filter { it.from < 0 }
        if (floatless.isNotEmpty()) {
            groups += MemoryTimelineGroup(from = -1, to = -1, scene = null, entries = floatless)
        }
        return groups.sortedByDescending { it.from }
    }
}
