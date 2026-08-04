package com.tavern.app.console

import org.junit.Test
import org.junit.Assert.*

class DesignTokensTest {

    @Test
    fun `all colors are fully opaque`() {
        // Verify that no design token has accidental transparency
        assertEquals(1.0f, DesignTokens.DeepVoid.alpha, 0.0f)
        assertEquals(1.0f, DesignTokens.AmberGlow.alpha, 0.0f)
        assertEquals(1.0f, DesignTokens.WarmWhite.alpha, 0.0f)
    }

    @Test
    fun `background colors differ from foreground`() {
        assertNotEquals(DesignTokens.DeepVoid, DesignTokens.WarmWhite)
        assertNotEquals(DesignTokens.VoidSurface, DesignTokens.WarmWhite)
    }

    @Test
    fun `amber glow is distinguishable from warm white`() {
        assertNotEquals(DesignTokens.AmberGlow, DesignTokens.WarmWhite)
    }

    @Test
    fun `error red has maximum red channel`() {
        assertTrue("ErrorRed should have red > 0.5", DesignTokens.ErrorRed.red > 0.5f)
    }
}
