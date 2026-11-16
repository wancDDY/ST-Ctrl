package com.tavern.app.console.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The 3D map is a cylinder: one height per layer, one shared radius per layer, and a crowded
 * layer wrapping onto inner rings instead of piling its dots on one circle. These tests pin the
 * three properties the shape depends on — height, shared radius, and no two dots in one spot.
 */
class MemoryLayout3DTest {

    private fun node(id: String, layer: Int, phase: Float): MemoryNode = MemoryNode(
        id = id,
        label = id,
        kind = MemoryNodeKind.ENTRY,
        layer = layer,
        x = 0.5f + 0.3f * cos(phase),
        y = 0.5f + 0.3f * sin(phase),
    )

    private fun radiusOf(point: FloatArray): Float = hypot(point[0], point[2])

    @Test
    fun everyLayerKeepsItsOwnHeight() {
        val graph = MemoryGraph(
            nodes = listOf(node("hub", 0, 0f), node("one", 1, 1f), node("two", 2, 2f)),
            edges = emptyList(),
        )
        val positions = MemoryLayout3D.positions(graph)
        assertEquals(0f, positions.getValue("hub")[1], 1e-4f)
        assertEquals(MemoryLayout3D.LAYER_HEIGHT, positions.getValue("one")[1], 1e-4f)
        assertEquals(2f * MemoryLayout3D.LAYER_HEIGHT, positions.getValue("two")[1], 1e-4f)
    }

    @Test
    fun aQuietLayerSitsOnTheOuterRadius() {
        val nodes = (0 until 5).map { node("n$it", 1, it * 1.1f) }
        val positions = MemoryLayout3D.positions(MemoryGraph(nodes, emptyList()))
        nodes.forEach { entry ->
            assertEquals(
                MemoryLayout3D.LAYER_RADIUS,
                radiusOf(positions.getValue(entry.id)),
                1e-3f,
            )
        }
    }

    @Test
    fun aCrowdedLayerWrapsOntoInnerRings() {
        val nodes = (0 until 40).map { node("n$it", 1, it * 0.31f) }
        val positions = MemoryLayout3D.positions(MemoryGraph(nodes, emptyList()))
        val radii = nodes.map { radiusOf(positions.getValue(it.id)) }
        assertTrue("some dots stay on the outer ring", radii.any { it > MemoryLayout3D.LAYER_RADIUS - 1e-3f })
        assertTrue("the rest step inward", radii.any { it < MemoryLayout3D.LAYER_RADIUS - 1e-3f })
    }

    @Test
    fun aSubRingStepsInwardByALittle() {
        val nodes = (0 until 40).map { node("n$it", 1, it * 0.31f) }
        val positions = MemoryLayout3D.positions(MemoryGraph(nodes, emptyList()))
        val radii = nodes.map { radiusOf(positions.getValue(it.id)) }
        // A visible step turns the cylinder into a wedding cake: the inner ring has to stay
        // within a twentieth of the outer one.
        val inner = radii.min()
        assertTrue("inner ring too far in: $inner", inner > MemoryLayout3D.LAYER_RADIUS * 0.9f)
    }

    @Test
    fun dotsKeepTheAngleTheFlatMapGaveThem() {
        val nodes = (0 until 8).map { node("n$it", 2, it * 0.7f) }
        val positions = MemoryLayout3D.positions(MemoryGraph(nodes, emptyList()))
        nodes.forEach { entry ->
            val expected = atan2(entry.y - 0.5f, entry.x - 0.5f)
            val point = positions.getValue(entry.id)
            assertEquals(expected, atan2(point[2], point[0]), 1e-3f)
        }
    }

    @Test
    fun theRootSitsOnTheAxis() {
        val nodes = listOf(node("hub", 0, 0.4f)) + (0 until 4).map { node("n$it", 1, it * 1.2f) }
        val positions = MemoryLayout3D.positions(MemoryGraph(nodes, emptyList()))
        val hub = positions.getValue("hub")
        // Every layer connects to the root; on the rim its edges drew a skirt out to one side.
        assertEquals(0f, radiusOf(hub), 1e-4f)
        nodes.drop(1).forEach { entry ->
            assertEquals(
                MemoryLayout3D.LAYER_RADIUS,
                radiusOf(positions.getValue(entry.id)),
                1e-3f,
            )
        }
    }

    @Test
    fun theSameGraphAlwaysProducesTheSameCylinder() {
        val nodes = (0 until 12).map { node("n$it", it % 3, it * 0.41f) }
        val graph = MemoryGraph(nodes, emptyList())
        val first = MemoryLayout3D.positions(graph)
        val second = MemoryLayout3D.positions(graph)
        nodes.forEach { entry ->
            val a = first.getValue(entry.id)
            val b = second.getValue(entry.id)
            assertEquals(a[0], b[0], 0f)
            assertEquals(a[1], b[1], 0f)
            assertEquals(a[2], b[2], 0f)
        }
    }
}
