package com.encrage.vault.crypto

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

enum class KeyDomain(val label: String) {
    ORIGINAL("original"), THUMBNAIL("thumbnail"), PRIVATE_METADATA("private-metadata"), FINGERPRINT("fingerprint")
}

/** HKDF-SHA-256 with explicit protocol/domain/media separation. */
class MediaKeyDerivation @Inject constructor() {
    fun derive(masterKey: ByteArray, mediaId: String, domain: KeyDomain): ByteArray {
        require(masterKey.size == 32)
        val salt = "encrage-hkdf-salt-v1".encodeToByteArray()
        val info = "encrage:v1:${domain.label}:$mediaId".encodeToByteArray()
        val prk = hmac(salt, masterKey)
        return hmac(prk, info + byteArrayOf(1)).copyOf(32).also { prk.fill(0) }
    }

    fun fingerprintKey(masterKey: ByteArray): ByteArray = derive(masterKey, "account", KeyDomain.FINGERPRINT)

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(input)
    }
}

enum class BlobType(val wireName: String) { ORIGINAL("original"), THUMBNAIL("thumbnail"), PRIVATE_METADATA("private-metadata") }

object ChunkProtocolV1 {
    const val VERSION: Byte = 1
    const val PLAINTEXT_CHUNK_BYTES = 4 * 1024 * 1024
    const val NONCE_BYTES = 24
    const val TAG_BYTES = 16

    fun aad(mediaId: String, blobType: BlobType, chunkIndex: Int): ByteArray {
        require(chunkIndex >= 0)
        val media = mediaId.encodeToByteArray()
        val blob = blobType.wireName.encodeToByteArray()
        return ByteBuffer.allocate(1 + 2 + media.size + 1 + blob.size + 4)
            .put(VERSION).putShort(media.size.toShort()).put(media)
            .put(blob.size.toByte()).put(blob).putInt(chunkIndex).array()
    }
}

