package com.encrage.vault.ui

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FormatTest {
    private lateinit var previousLocale: Locale

    @Before
    fun pinLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() = Locale.setDefault(previousLocale)

    @Test
    fun quotaSetInConsoleAsGibibytesReadsTheSame() {
        assertEquals("100.0 GB", formatBytes(100L * 1024 * 1024 * 1024))
        assertEquals("50.0 GB", formatBytes(50L * 1024 * 1024 * 1024))
    }

    @Test
    fun unitBoundariesUseBinaryMultiples() {
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("1.00 TB", formatBytes(1024L * 1024 * 1024 * 1024))
        assertEquals("1000.0 GB", formatBytes(1000L * 1024 * 1024 * 1024))
    }
}
