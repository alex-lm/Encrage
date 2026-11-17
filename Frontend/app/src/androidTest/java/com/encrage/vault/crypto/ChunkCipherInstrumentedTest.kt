package com.encrage.vault.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkCipherInstrumentedTest {
    private val engine = LibsodiumXChaChaEngine()
    private val derivation = MediaKeyDerivation()
    private val encryptor = ChunkEncryptor(engine, derivation)
    private val decryptor = ChunkDecryptor(engine, derivation)
    private val master = ByteArray(32) { it.toByte() }

    @Test fun roundTripAndNonceUniqueness() {
        val plaintext = "authenticated media chunk".encodeToByteArray()
        val first = encryptor.encrypt(master, "m1", BlobType.ORIGINAL, 0, plaintext)
        val second = encryptor.encrypt(master, "m1", BlobType.ORIGINAL, 0, plaintext)
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertArrayEquals(plaintext, decryptor.decrypt(master, "m1", BlobType.ORIGINAL, 0, first))
    }

    @Test fun encodedThumbnailRoundTripsWithThumbnailDomain() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val encoded = encryptor.encrypt(master, "cloud-photo-1", BlobType.THUMBNAIL, 0, jpeg).encode()

        val decoded = EncryptedChunk.decode(encoded)

        assertArrayEquals(jpeg, decryptor.decrypt(master, "cloud-photo-1", BlobType.THUMBNAIL, 0, decoded))
    }

    @Test fun tamperedCiphertextFailsAuthentication() {
        val chunk = encryptor.encrypt(master, "m1", BlobType.ORIGINAL, 0, byteArrayOf(1, 2, 3))
        chunk.ciphertext[0] = (chunk.ciphertext[0].toInt() xor 1).toByte()
        assertThrows(IntegrityException::class.java) {
            decryptor.decrypt(master, "m1", BlobType.ORIGINAL, 0, chunk)
        }
    }

    @Test fun wrongKeyAndAadFailAuthentication() {
        val chunk = encryptor.encrypt(master, "m1", BlobType.ORIGINAL, 3, byteArrayOf(1, 2, 3))
        assertThrows(IntegrityException::class.java) {
            decryptor.decrypt(ByteArray(32) { 7 }, "m1", BlobType.ORIGINAL, 3, chunk)
        }
        assertThrows(IntegrityException::class.java) {
            decryptor.decrypt(master, "m1", BlobType.ORIGINAL, 4, chunk)
        }
    }
}
