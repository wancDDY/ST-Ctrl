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
}
