package com.encrage.vault.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.encrage.vault.crypto.BlobType
import com.encrage.vault.crypto.ChunkDecryptor
import com.encrage.vault.crypto.ChunkProtocolV1
import com.encrage.vault.crypto.EncryptedChunk
import com.encrage.vault.crypto.MasterKeyManager
import com.encrage.vault.data.remote.EncrageApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** Downloads and decrypts a cloud thumbnail without writing plaintext to disk. */
class CloudThumbnailLoader @Inject constructor(
    private val api: EncrageApi,
    private val decryptor: ChunkDecryptor,
    private val masterKeys: MasterKeyManager,
) {
    suspend fun load(mediaId: String): Bitmap = withContext(Dispatchers.IO) {
        require(mediaId.isNotBlank())
        val encoded = api.thumbnail(mediaId).use(::readBounded)
        val chunk = try {
            EncryptedChunk.decode(encoded)
        } finally {
            encoded.fill(0)
        }
        val masterKey = masterKeys.unwrap()
        val plaintext = try {
            decryptor.decrypt(masterKey, mediaId, BlobType.THUMBNAIL, 0, chunk)
        } finally {
            masterKey.fill(0)
            chunk.nonce.fill(0)
            chunk.ciphertext.fill(0)
        }
        try {
            BitmapFactory.decodeByteArray(plaintext, 0, plaintext.size)
                ?: error("Cloud thumbnail is not a supported image")
        } finally {
            plaintext.fill(0)
        }
    }

    private fun readBounded(body: ResponseBody): ByteArray {
        val declaredLength = body.contentLength()
        require(declaredLength <= MAX_ENCRYPTED_THUMBNAIL_BYTES || declaredLength == -1L) {
            "Encrypted thumbnail exceeds the protocol limit"
        }
        val buffer = ByteArray(16 * 1024)
        return try {
            body.byteStream().use { input ->
                ByteArrayOutputStream(
                    declaredLength.takeIf { it in 1L..MAX_ENCRYPTED_THUMBNAIL_BYTES.toLong() }
                        ?.toInt() ?: DEFAULT_CAPACITY,
                ).use { output ->
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_ENCRYPTED_THUMBNAIL_BYTES) {
                            "Encrypted thumbnail exceeds the protocol limit"
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
        } finally {
            buffer.fill(0)
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 128 * 1024
        const val MAX_ENCRYPTED_THUMBNAIL_BYTES =
            ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES + 1 + ChunkProtocolV1.NONCE_BYTES + ChunkProtocolV1.TAG_BYTES
    }
}
