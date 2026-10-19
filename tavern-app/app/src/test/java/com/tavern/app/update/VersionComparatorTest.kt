package com.tavern.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun comparesNumericSegments() {
        assertTrue(VersionComparator.isNewer("2.0.10", "2.0.3"))
        assertFalse(VersionComparator.isNewer("2.0.3", "2.0.3"))
        assertFalse(VersionComparator.isNewer("2.0.2", "2.0.3"))
    }

    @Test
    fun ignoresTagPrefixAndSuffix() {
        assertTrue(VersionComparator.isNewer("v1.2.0", "1.1.9"))
        assertFalse(VersionComparator.isNewer("1.2.0-beta", "1.2.0"))
    }
}
