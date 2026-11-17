package com.encrage.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaKeyDerivationTest {
    private val derivation = MediaKeyDerivation()
    private val master = ByteArray(32) { it.toByte() }

    @Test fun `derivation is deterministic`() {
        assertArrayEquals(
            derivation.derive(master, "media-a", KeyDomain.ORIGINAL),
            derivation.derive(master, "media-a", KeyDomain.ORIGINAL),
        )
    }

    @Test fun `different media derive different keys`() {
        assertFalse(
            derivation.derive(master, "media-a", KeyDomain.ORIGINAL)
                .contentEquals(derivation.derive(master, "media-b", KeyDomain.ORIGINAL)),
        )
    }

    @Test fun `thumbnail and original use different keys`() {
        assertFalse(
            derivation.derive(master, "media-a", KeyDomain.ORIGINAL)
                .contentEquals(derivation.derive(master, "media-a", KeyDomain.THUMBNAIL)),
        )
    }
}

