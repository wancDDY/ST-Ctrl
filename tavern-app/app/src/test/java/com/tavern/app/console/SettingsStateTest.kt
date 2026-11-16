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
        // Each startup-only switch on its own is a difference, otherwise the notice would never
        // appear for it. Named arguments on purpose: a positional call kept compiling when the
        // parameter list changed, and quietly pointed at the wrong flags.
        val base = SettingsState.restartSignature(charImport = true, longMemory = false)
        val moved = listOf(
            SettingsState.restartSignature(charImport = false, longMemory = false),
            SettingsState.restartSignature(charImport = true, longMemory = true),
        )
        for (variant in moved) {
            assertNotEquals(base, variant)
        }
        assertEquals(moved.size, moved.toSet().size)
    }

    @Test
    fun `the memory budget choices are values the engine keeps`() {
        // The engine clamps to 200..8000 and reads zero as "size it from the prompt window". A
        // choice outside that range would come back clamped, and the row would show a number the
        // reader never picked.
        assertTrue(SettingsState.MEMORY_BUDGET_CHOICES.contains(0))
        for (chars in SettingsState.MEMORY_BUDGET_CHOICES) {
            assertTrue(
                "$chars is outside the range the engine keeps",
                chars == 0 || chars in 200..SettingsState.MEMORY_BUDGET_MAX,
            )
        }
    }

    @Test
    fun `the restart signature is only the boot-time switches`() {
        // Two fields, and a test that says so: a memory switch that creeps back onto this list
        // would start asking for restarts that apply nothing, because the core reads those from
        // the options file on every call.
        val signature = SettingsState.restartSignature(charImport = true, longMemory = false)
        assertEquals(2, signature.split("|").size)
    }
}

class MemoryBackupSettingsTest {

    @Test
    fun `the archive carries every memory switch the console offers`() {
        // A key the export writes but the restore ignores is a setting the archive appears to have
        // and does not put back. Both sides read the names from the same function, so this pins the
        // set of names itself.
        val fields = SettingsState.memoryBackupFields(emptyMap<String, Any>())
        assertEquals(
            setOf(
                "tavern_chat_memory",
                "tavern_chat_memory_ghost",
                "tavern_chat_memory_budget",
                "tavern_chat_memory_agent",
                "tavern_chat_memory_pending",
            ),
            fields.keys,
        )
    }

    @Test
    fun `the memory switch restores even when the archive predates the summariser key`() {
        // The switch is restored on its own key, not inside another setting's block: an archive
        // from a build that never knew that other key still restores the memory switch.
        val settings = org.json.JSONObject()
            .put("tavern_chat_memory", false)
            .put("tavern_chat_memory_agent", true)
            .put("tavern_chat_memory_pending", 12)
        assertFalse(settings.has("tavern_long_memory"))

        val writes = SettingsState.memoryBackupWrites(settings)
        assertEquals(false, writes["tavern_chat_memory"])
        assertEquals(true, writes["tavern_chat_memory_agent"])
        // The archive's name for this one is shorter than the preference it lands on; the map is
        // keyed by the preference, which is what the editor writes.
        assertEquals(12, writes["tavern_chat_memory_pending_threshold"])
    }

    @Test
    fun `an absent key is left alone rather than reset`() {
        val writes = SettingsState.memoryBackupWrites(org.json.JSONObject().put("tavern_chat_memory", true))
        assertEquals(setOf("tavern_chat_memory"), writes.keys)
    }

    @Test
    fun `a hand-edited archive cannot set a number the console refuses`() {
        val writes = SettingsState.memoryBackupWrites(
            org.json.JSONObject()
                .put("tavern_chat_memory_pending", 99999)
                .put("tavern_chat_memory_budget", -5),
        )
        assertEquals(500, writes["tavern_chat_memory_pending_threshold"])
        assertEquals(0, writes["tavern_chat_memory_budget"])
    }
}
