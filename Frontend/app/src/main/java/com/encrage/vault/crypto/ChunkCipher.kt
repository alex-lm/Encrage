package com.encrage.vault.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

class IntegrityException(message: String) : SecurityException(message)

interface XChaChaEngine {
    fun encrypt(plaintext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray
    @Throws(IntegrityException::class)
    fun decrypt(ciphertext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray
}

@Singleton
class LibsodiumXChaChaEngine @Inject constructor() : XChaChaEngine {
    private val sodium = LazySodiumAndroid(SodiumAndroid())

    override fun encrypt(plaintext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(key.size == AEAD.XCHACHA20POLY1305_IETF_KEYBYTES)
        require(nonce.size == AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        val output = ByteArray(plaintext.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val length = longArrayOf(0)
        check(sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            output, length, plaintext, plaintext.size.toLong(), aad, aad.size.toLong(), null, nonce, key,
        )) { "libsodium encryption failed" }
        return output.copyOf(length[0].toInt())
    }

    override fun decrypt(ciphertext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(ciphertext.size >= AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val output = ByteArray(ciphertext.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val length = longArrayOf(0)
        val authenticated = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            output, length, null, ciphertext, ciphertext.size.toLong(), aad, aad.size.toLong(), nonce, key,
        )
        if (!authenticated) {
            output.fill(0)
            throw IntegrityException("Chunk authentication failed; plaintext was discarded")
        }
        return output.copyOf(length[0].toInt())
    }
}

data class EncryptedChunk(val version: Byte, val nonce: ByteArray, val ciphertext: ByteArray) {
    fun encode(): ByteArray = byteArrayOf(version) + nonce + ciphertext

    companion object {
        fun decode(bytes: ByteArray): EncryptedChunk {
            require(bytes.size >= 1 + ChunkProtocolV1.NONCE_BYTES + ChunkProtocolV1.TAG_BYTES)
            require(bytes[0] == ChunkProtocolV1.VERSION) { "Unsupported encryption version" }
            return EncryptedChunk(bytes[0], bytes.copyOfRange(1, 25), bytes.copyOfRange(25, bytes.size))
        }
    }
}

class ChunkEncryptor @Inject constructor(
    private val engine: XChaChaEngine,
    private val keys: MediaKeyDerivation,
) {
    private val random = SecureRandom()

    fun encrypt(masterKey: ByteArray, mediaId: String, blobType: BlobType, index: Int, plaintext: ByteArray): EncryptedChunk {
        require(plaintext.size <= ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES)
        val domain = when (blobType) {
            BlobType.ORIGINAL -> KeyDomain.ORIGINAL
            BlobType.THUMBNAIL -> KeyDomain.THUMBNAIL
            BlobType.PRIVATE_METADATA -> KeyDomain.PRIVATE_METADATA
        }
        val key = keys.derive(masterKey, mediaId, domain)
        val nonce = ByteArray(ChunkProtocolV1.NONCE_BYTES).also(random::nextBytes)
        return try {
            EncryptedChunk(ChunkProtocolV1.VERSION, nonce, engine.encrypt(plaintext, ChunkProtocolV1.aad(mediaId, blobType, index), nonce, key))
        } finally {
            key.fill(0)
        }
    }
}

class ChunkDecryptor @Inject constructor(
    private val engine: XChaChaEngine,
    private val keys: MediaKeyDerivation,
) {
    fun decrypt(masterKey: ByteArray, mediaId: String, blobType: BlobType, index: Int, chunk: EncryptedChunk): ByteArray {
        require(chunk.version == ChunkProtocolV1.VERSION)
        val domain = when (blobType) {
            BlobType.ORIGINAL -> KeyDomain.ORIGINAL
            BlobType.THUMBNAIL -> KeyDomain.THUMBNAIL
            BlobType.PRIVATE_METADATA -> KeyDomain.PRIVATE_METADATA
        }
        val key = keys.derive(masterKey, mediaId, domain)
        return try {
            engine.decrypt(chunk.ciphertext, ChunkProtocolV1.aad(mediaId, blobType, index), chunk.nonce, key)
        } finally {
            key.fill(0)
        }
    }
}
