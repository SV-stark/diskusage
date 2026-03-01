package com.google.android.diskusage

import org.junit.Assert.assertNotNull
import org.junit.Test

class FileSystemEntryTest {

    @Test
    fun testSizeStringCalculation() {
        // Test basic size string calculation
        val result = com.google.android.diskusage.filesystem.entity.FileSystemEntry.calcSizeString(1024f)
        assertNotNull(result)
    }

    @Test
    fun testSizeStringCalculationKB() {
        val result = com.google.android.diskusage.filesystem.entity.FileSystemEntry.calcSizeString(1024f * 1024f)
        assertNotNull(result)
    }

    @Test
    fun testSizeStringCalculationMB() {
        val result = com.google.android.diskusage.filesystem.entity.FileSystemEntry.calcSizeString(1024f * 1024f * 100f)
        assertNotNull(result)
    }

    @Test
    fun testSizeStringCalculationGB() {
        val result = com.google.android.diskusage.filesystem.entity.FileSystemEntry.calcSizeString(1024f * 1024f * 1024f * 2f)
        assertNotNull(result)
    }
}
