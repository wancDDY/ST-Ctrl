package com.tavern.app.backup

import org.junit.Test
import org.junit.Assert.*

class BackupMetadataTest {

    @Test
    fun `data class equality works`() {
        val a = BackupMetadata(
            timestamp = "2025-01-01",
            appVersion = "1.0",
            coreVersion = "1.18.0",
            fileCount = 10,
            totalSizeBytes = 1024
        )
        val b = BackupMetadata(
            timestamp = "2025-01-01",
            appVersion = "1.0",
            coreVersion = "1.18.0",
            fileCount = 10,
            totalSizeBytes = 1024
        )
        assertEquals(a, b)
    }

    @Test
    fun `data class with different fields not equal`() {
        val a = BackupMetadata(timestamp = "2025-01-01", appVersion = "1.0", coreVersion = "1.0", fileCount = 10, totalSizeBytes = 1024)
        val b = BackupMetadata(timestamp = "2025-01-02", appVersion = "1.0", coreVersion = "1.0", fileCount = 10, totalSizeBytes = 1024)
        assertNotEquals(a, b)
    }

    @Test
    fun `default source is empty string`() {
        val meta = BackupMetadata(timestamp = "2025", appVersion = "1.0", coreVersion = "1.0", fileCount = 0, totalSizeBytes = 0)
        assertEquals("", meta.source)
    }

    @Test
    fun `default version is 1`() {
        val meta = BackupMetadata(timestamp = "t", appVersion = "a", coreVersion = "c", fileCount = 0, totalSizeBytes = 0)
        assertEquals(1, meta.version)
    }

    @Test
    fun `file count and size stored correctly`() {
        val meta = BackupMetadata(timestamp = "t", appVersion = "a", coreVersion = "c", fileCount = 42, totalSizeBytes = 999999)
        assertEquals(42, meta.fileCount)
        assertEquals(999999L, meta.totalSizeBytes)
    }
}
