package com.encrage.vault.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.encrage.vault.crypto.BlobType
import com.encrage.vault.crypto.ChunkDecryptor
import com.encrage.vault.crypto.ChunkProtocolV1
import com.encrage.vault.crypto.EncryptedChunk
import com.encrage.vault.crypto.MasterKeyManager
import com.encrage.vault.data.local.BackupStatus
import com.encrage.vault.data.local.LocalState
import com.encrage.vault.data.local.MediaDao
import com.encrage.vault.data.local.MediaEntity
import com.encrage.vault.data.local.MediaType
import com.encrage.vault.data.local.ThumbnailState
import com.encrage.vault.data.remote.EncrageApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

data class DownloadResult(val downloaded: Int, val failures: List<String>)

/**
 * Reads authenticated original chunks on demand. Only one plaintext chunk is retained at a time,
 * which keeps photo decoding, downloads, and video seeking bounded for large media.
 */
private class CloudChunkReader(
    private val api: EncrageApi,
    private val decryptor: ChunkDecryptor,
    private val masterKeys: MasterKeyManager,
    private val mediaId: String,
) : AutoCloseable {
    private var cachedIndex = -1
    private var cachedPlaintext: ByteArray? = null

    fun chunk(index: Int): ByteArray {
        require(index >= 0)
        cachedPlaintext?.takeIf { cachedIndex == index }?.let { return it }
        clearCachedChunk()

        val encoded = runBlocking(Dispatchers.IO) {
            api.chunk(mediaId, index).use { body ->
                val maximum = ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES + ENCRYPTION_OVERHEAD
                val declared = body.contentLength()
                require(declared == -1L || declared <= maximum) { "Encrypted media chunk exceeds the protocol limit" }
                body.byteStream().use { input -> readBounded(input, maximum) }
            }
        }
        val encrypted = try {
            EncryptedChunk.decode(encoded)
        } finally {
            encoded.fill(0)
        }
        val master = masterKeys.unwrap()
        val plaintext = try {
            decryptor.decrypt(master, mediaId, BlobType.ORIGINAL, index, encrypted)
        } finally {
            master.fill(0)
            encrypted.nonce.fill(0)
            encrypted.ciphertext.fill(0)
        }
        cachedIndex = index
        cachedPlaintext = plaintext
        return plaintext
    }

    override fun close() = clearCachedChunk()

    private fun clearCachedChunk() {
        cachedPlaintext?.fill(0)
        cachedPlaintext = null
        cachedIndex = -1
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maximum, 128 * 1024))
        val buffer = ByteArray(32 * 1024)
        return try {
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= maximum) { "Encrypted media chunk exceeds the protocol limit" }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } finally {
            buffer.fill(0)
            output.close()
        }
    }

    private companion object {
        const val ENCRYPTION_OVERHEAD = 1 + ChunkProtocolV1.NONCE_BYTES + ChunkProtocolV1.TAG_BYTES
    }
}

private class CloudOriginalInputStream(
    private val reader: CloudChunkReader,
    private val plaintextSize: Long,
) : InputStream() {
    private var position = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (length == 0) return 0
        if (position >= plaintextSize) return -1
        val index = (position / ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES).toInt()
        val chunkOffset = (position % ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES).toInt()
        val chunk = reader.chunk(index)
        val copied = minOf(length.toLong(), (chunk.size - chunkOffset).toLong(), plaintextSize - position).toInt()
        check(copied > 0) { "Cloud media ended before its declared size" }
        chunk.copyInto(buffer, offset, chunkOffset, chunkOffset + copied)
        position += copied
        return copied
    }

    override fun close() = reader.close()
}

@Singleton
class CloudOriginalMedia @Inject constructor(
    private val api: EncrageApi,
    private val decryptor: ChunkDecryptor,
    private val masterKeys: MasterKeyManager,
    private val contentResolver: ContentResolver,
    private val mediaDao: MediaDao,
) {
    suspend fun loadPhoto(item: MediaEntity): Bitmap = withContext(Dispatchers.IO) {
        val mediaId = requireNotNull(item.mediaId)
        require(item.mediaType == MediaType.IMAGE)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(item.width, item.height, MAX_DECODED_EDGE)
        }
        CloudChunkReader(api, decryptor, masterKeys, mediaId).use { reader ->
            BufferedInputStream(CloudOriginalInputStream(reader, item.sizeBytes), STREAM_BUFFER_BYTES).use { input ->
                BitmapFactory.decodeStream(input, null, options)
                    ?: error("Cloud original is not a supported image")
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun videoDataSourceFactory(item: MediaEntity): DataSource.Factory {
        require(item.mediaType == MediaType.VIDEO)
        val mediaId = requireNotNull(item.mediaId)
        return DataSource.Factory {
            CloudOriginalDataSource(api, decryptor, masterKeys, mediaId, item.sizeBytes)
        }
    }

    suspend fun download(items: List<MediaEntity>): DownloadResult = withContext(Dispatchers.IO) {
        val failures = mutableListOf<String>()
        var downloaded = 0
        items.forEach { item ->
            if (item.localState != LocalState.CLOUD_ONLY || item.mediaId == null) {
                failures += "${item.localId}: item is not cloud-only"
                return@forEach
            }
            runCatching { downloadOne(item) }
                .onSuccess { downloaded++ }
                .onFailure { failures += "${item.localId}: ${it.message ?: "download failed"}" }
        }
        DownloadResult(downloaded, failures)
    }

    private suspend fun downloadOne(item: MediaEntity) {
        mediaDao.updateBackupStatus(item.localId, BackupStatus.DOWNLOADING, null, System.currentTimeMillis())
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.mediaType == MediaType.VIDEO) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else if (item.mediaType == MediaType.VIDEO) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val displayName = downloadName(item)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Encrage")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = contentResolver.insert(collection, values) ?: error("Could not create the MediaStore item")
        try {
            contentResolver.openOutputStream(uri, "w")?.use { output -> writeOriginal(item, output) }
                ?: error("Could not open the MediaStore item")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            val now = System.currentTimeMillis()
            mediaDao.upsert(
                item.copy(
                    mediaStoreUri = uri.toString(),
                    mediaStoreId = runCatching { ContentUris.parseId(uri) }.getOrNull(),
                    localState = LocalState.LOCAL_AND_CLOUD,
                    backupStatus = BackupStatus.BACKED_UP,
                    thumbnailState = ThumbnailState.LOCAL,
                    lastError = null,
                    updatedAtEpochMs = now,
                ),
            )
        } catch (error: Throwable) {
            runCatching { contentResolver.delete(uri, null, null) }
            mediaDao.upsert(
                item.copy(
                    backupStatus = BackupStatus.BACKED_UP,
                    lastError = (error.message ?: "Download failed").take(500),
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            throw error
        }
    }

    private fun writeOriginal(item: MediaEntity, output: OutputStream) {
        val mediaId = requireNotNull(item.mediaId)
        CloudChunkReader(api, decryptor, masterKeys, mediaId).use { reader ->
            var remaining = item.sizeBytes
            var index = 0
            while (remaining > 0) {
                val chunk = reader.chunk(index++)
                val count = minOf(remaining, chunk.size.toLong()).toInt()
                check(count > 0) { "Cloud media ended before its declared size" }
                output.write(chunk, 0, count)
                remaining -= count
            }
            output.flush()
        }
    }

    private fun downloadName(item: MediaEntity): String {
        val timestamp = Instant.ofEpochMilli(item.dateTimeTakenEpochMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType)
            ?: if (item.mediaType == MediaType.VIDEO) "mp4" else "jpg"
        return "ENCRAGE_${timestamp}_${item.mediaId?.take(8)}.$extension"
    }

    private fun sampleSize(width: Int, height: Int, targetEdge: Int): Int {
        var sample = 1
        val longest = max(width, height)
        while (longest / (sample * 2) >= targetEdge) sample *= 2
        return sample
    }

    private companion object {
        const val MAX_DECODED_EDGE = 4096
        const val STREAM_BUFFER_BYTES = 64 * 1024
    }
}

@OptIn(UnstableApi::class)
private class CloudOriginalDataSource(
    private val api: EncrageApi,
    private val decryptor: ChunkDecryptor,
    private val masterKeys: MasterKeyManager,
    private val mediaId: String,
    private val plaintextSize: Long,
) : BaseDataSource(true) {
    private var reader: CloudChunkReader? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false
    private var currentUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        require(dataSpec.position in 0..plaintextSize)
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            plaintextSize - position
        } else {
            minOf(dataSpec.length, plaintextSize - position)
        }
        currentUri = dataSpec.uri
        reader = CloudChunkReader(api, decryptor, masterKeys, mediaId)
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val index = (position / ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES).toInt()
        val chunkOffset = (position % ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES).toInt()
        val chunk = checkNotNull(reader).chunk(index)
        val copied = minOf(length.toLong(), (chunk.size - chunkOffset).toLong(), remaining).toInt()
        check(copied > 0) { "Cloud video ended before its declared size" }
        chunk.copyInto(buffer, offset, chunkOffset, chunkOffset + copied)
        position += copied
        remaining -= copied
        bytesTransferred(copied)
        return copied
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        reader?.close()
        reader = null
        currentUri = null
        if (opened) transferEnded()
        opened = false
    }
}
