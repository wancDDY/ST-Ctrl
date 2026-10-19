package com.tavern.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodicUpdateCheckerTest {

    @Test
    fun pendingUpdatesDiscardInstalledVersions() {
        val pending = PeriodicUpdateChecker.PendingUpdates(
            appVersion = "2.0.0",
            coreVersion = "1.18.0",
        )

        val validated = pending.validated(
            currentAppVersion = "2.0.0",
            currentCoreVersion = "1.18.0",
        )

        assertFalse(validated.hasUpdate)
        assertEquals(null, validated.appVersion)
        assertEquals(null, validated.coreVersion)
    }

    @Test
    fun pendingUpdatesKeepOnlyNewerVersions() {
        val pending = PeriodicUpdateChecker.PendingUpdates(
            appVersion = "2.0.1",
            coreVersion = "1.18.0",
        )

        val validated = pending.validated(
            currentAppVersion = "2.0.0",
            currentCoreVersion = "1.18.0",
        )

        assertTrue(validated.hasUpdate)
        assertEquals("2.0.1", validated.appVersion)
        assertEquals(null, validated.coreVersion)
    }

    /**
     * The announcement carries the release notes published with the version, because the
     * update page shows them when the reader arrives from the console island. Notes must
     * therefore follow their version: kept while it is pending, dropped with it.
     */
    @Test
    fun releaseNotesFollowTheirVersion() {
        val pending = PeriodicUpdateChecker.PendingUpdates(
            appVersion = "2.0.1",
            coreVersion = "1.18.0",
            appChangelog = "app notes",
            coreChangelog = "core notes",
        )

        val stillPending = pending.validated(
            currentAppVersion = "2.0.0",
            currentCoreVersion = "1.18.0",
        )
        assertEquals("app notes", stillPending.appChangelog)
        assertEquals(null, stillPending.coreChangelog)

        val installed = pending.validated(
            currentAppVersion = "2.0.1",
            currentCoreVersion = "1.18.0",
        )
        assertEquals(null, installed.appVersion)
        assertEquals(null, installed.appChangelog)
    }
}
