package com.tavern.app.update

import com.tavern.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppVersionSummaryTest {
    /**
     * The update page looks the summary up by installed versionName. A key that
     * does not match the shipped versionName is invisible: the page silently falls
     * back to the remote changelog or to "no changelog", with nothing failing.
     */
    @Test
    fun installedVersionHasASummary() {
        val summary = AppVersionSummary.summaryFor(BuildConfig.VERSION_NAME)

        assertNotNull(
            "no built-in summary for ${BuildConfig.VERSION_NAME}",
            summary,
        )
        assertEquals("1.0.2", summary!!.fromVersion)
        assertEquals("从 v1.0.2 升级", summary.migrationTitle)
        assertEquals(AppVersionSummary.intro, summary.intro)
    }

    @Test
    fun unknownVersionHasNoSummary() {
        assertNull(AppVersionSummary.summaryFor("9.9.9"))
    }

    @Test
    fun noticeTextCarriesTheVersion() {
        assertEquals("v2.0.0 更新日志", AppVersionSummary.noticeText("2.0.0"))
    }
}
