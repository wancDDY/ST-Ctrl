package com.tavern.app.console

import org.junit.Test
import org.junit.Assert.*

class SettingsStateTest {

    @Test
    fun `OptTier enums have valid factor ranges`() {
        for (tier in OptTier.entries) {
            assertTrue("${tier.label} factor should be > 0", tier.factor > 0.0)
            assertTrue("${tier.label} factor should be <= 1.0", tier.factor <= 1.0)
        }
    }

    @Test
    fun `OptTier labels are non-empty`() {
        for (tier in OptTier.entries) {
            assertTrue("${tier.name} should have a non-empty label", tier.label.isNotEmpty())
        }
    }

    @Test
    fun `preference keys are consistent`() {
        assertEquals("tavern_console_prefs", SettingsState.PREFS_NAME)
    }

    @Test
    fun `the restart notice compares the values a start would read, not the writes`() {
        // The running service is on the values the console holds.
        assertFalse(
            SettingsState.optionsPendingRestart(
                recorded = "true|false|false|-|-",
                current = "true|false|false|-|-",
            ),
        )
        // A startup-only switch moved.
        assertTrue(
            SettingsState.optionsPendingRestart(
                recorded = "true|false|false|-|-",
                current = "true|true|false|-|-",
            ),
        )
        // Nothing was ever recorded: a build that wrote no signature cannot say what the
        // running service saw, so a restart notice would be a guess.
        assertFalse(SettingsState.optionsPendingRestart(recorded = null, current = "true|true"))
    }

    @Test
    fun `the restart signature covers the switches a start reads, and only those it can apply`() {
        // Each startup-only switch on its own is a difference, otherwise the notice would
        // never appear for it.
        val base = SettingsState.restartSignature(true, false)
        val moved = listOf(
            SettingsState.restartSignature(false, false),
            SettingsState.restartSignature(true, true),
        )
        for (variant in moved) {
            assertNotEquals(base, variant)
        }
        // Distinct signatures: a run of values that collided would hide a real restart.
        assertEquals(moved.size, moved.toSet().size)
    }

}
