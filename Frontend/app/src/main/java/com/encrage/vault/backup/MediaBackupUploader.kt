package com.encrage.vault.backup

import android.content.ContentResolver
import android.net.Uri
import com.encrage.vault.crypto.BlobType
import com.encrage.vault.crypto.ChunkEncryptor
import com.encrage.vault.crypto.ChunkProtocolV1
import com.encrage.vault.crypto.MasterKeyManager
import com.encrage.vault.crypto.MediaFingerprintGenerator
import com.encrage.vault.data.local.BackupStatus
import com.encrage.vault.data.local.CloudState
import com.encrage.vault.data.local.LocalState
import com.encrage.vault.data.local.MediaDao
import com.encrage.vault.data.local.MediaEntity
import com.encrage.vault.data.local.MediaType
import com.encrage.vault.data.local.ThumbnailState
import com.encrage.vault.data.local.TransferDao
import com.encrage.vault.data.local.UploadSessionEntity
import com.encrage.vault.data.preferences.BackupPreferences
import com.encrage.vault.data.remote.EncrageApi
import com.encrage.vault.data.remote.UploadCreateRequest
import com.encrage.vault.media.ThumbnailGenerator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UploadOutcome {
    data object BackedUp : UploadOutcome
    data object Skipped : UploadOutcome
    data class Failed(val message: String, val retryable: Boolean) : UploadOutcome
    data class Fatal(val message: String) : UploadOutcome
}

@Singleton
class MediaBackupUploader @Inject constructor(
    private val contentResolver: ContentResolver,
    private val mediaDao: MediaDao,
    private val transferDao: TransferDao,
    private val api: EncrageApi,
    private val fingerprints: MediaFingerprintGenerator,
    private val encryptor: ChunkEncryptor,
    private val masterKeys: MasterKeyManager,
    private val thumbnails: ThumbnailGenerator,
) {
    suspend fun upload(item: MediaEntity, preferences: BackupPreferences): UploadOutcome {
        if (item.mediaStoreUri.isNullOrBlank()) return UploadOutcome.Skipped
        if (item.mediaType == MediaType.IMAGE && !preferences.backupPhotos) return UploadOutcome.Skipped
        if (item.mediaType == MediaType.VIDEO && !preferences.backupVideos) return UploadOutcome.Skipped
        if (!masterKeys.hasKey()) return UploadOutcome.Failed("Recovery Key is not configured", retryable = false)

        val uri = Uri.parse(item.mediaStoreUri)
        return try {
            mediaDao.updateBackupStatus(item.localId, BackupStatus.PREPARING, null, System.currentTimeMillis())
            val fingerprint = item.mediaFingerprint ?: fingerprints.fingerprint(uri)
            val current = (mediaDao.byId(item.localId) ?: item).copy(
                mediaFingerprint = fingerprint,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            mediaDao.upsert(current)

            val totalChunks = totalChunksFor(current.sizeBytes)
            val existingSession = transferDao.uploadForMedia(current.localId)
            val create = api.createUpload(buildCreateRequest(current, fingerprint, totalChunks))

            if (create.alreadyExists) {
                existingSession?.let { transferDao.deleteUpload(it.uploadId) }
                markBackedUp(current, create.mediaId, fingerprint)
                return UploadOutcome.BackedUp
            }

            val uploadId = create.uploadId
                ?: return UploadOutcome.Failed("Server did not return an upload_id", retryable = true)

            mediaDao.upsert(
                current.copy(
                    mediaId = create.mediaId,
                    cloudState = CloudState.QUEUED,
                    backupStatus = BackupStatus.UPLOADING,
                    lastError = null,
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )

            val status = api.uploadStatus(uploadId)
            val uploaded = status.uploadedChunks.toMutableSet()
            var thumbnailUploaded = status.thumbnailUploaded
            persistSession(uploadId, current.localId, totalChunks, uploaded)

            val master = masterKeys.unwrap()
            try {
                mediaDao.updateBackupStatus(current.localId, BackupStatus.ENCRYPTING, null, System.currentTimeMillis())
                streamChunks(
                    localId = current.localId,
                    uri = uri,
                    uploadId = uploadId,
                    mediaId = create.mediaId,
                    master = master,
                    totalChunks = totalChunks,
                    uploaded = uploaded,
                )
                if (!thumbnailUploaded) {
                    uploadThumbnail(uri, uploadId, create.mediaId, master)
                }
            } finally {
                master.fill(0)
            }

            mediaDao.updateBackupStatus(current.localId, BackupStatus.UPLOADING, null, System.currentTimeMillis())
            api.completeUpload(uploadId)
            transferDao.deleteUpload(uploadId)
            markBackedUp(current, create.mediaId, fingerprint)
            UploadOutcome.BackedUp
        } catch (error: HttpException) {
            val message = httpMessage(error)
            when (error.code()) {
                401, 403 -> UploadOutcome.Fatal(message)
                413 -> {
                    fail(item.localId, message)
                    UploadOutcome.Failed(message, retryable = false)
                }
                in 500..599, 408, 429 -> {
                    fail(item.localId, message)
                    UploadOutcome.Failed(message, retryable = true)
                }
                else -> {
                    fail(item.localId, message)
                    UploadOutcome.Failed(message, retryable = false)
                }
            }
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            fail(item.localId, message)
            UploadOutcome.Failed(message, retryable = true)
        }
    }

    private fun buildCreateRequest(
        current: MediaEntity,
        fingerprint: String,
        totalChunks: Int,
    ) = UploadCreateRequest(
        mediaFingerprint = fingerprint,
        mimeType = normalizeMime(current.mimeType, current.mediaType),
        mediaType = if (current.mediaType == MediaType.VIDEO) "video" else "image",
        plaintextSizeBytes = current.sizeBytes,
        datetimeTaken = Instant.ofEpochMilli(current.dateTimeTakenEpochMs).toString(),
        width = current.width.takeIf { it > 0 },
        height = current.height.takeIf { it > 0 },
        durationSeconds = current.durationSeconds?.toDouble(),
        encryptionVersion = ChunkProtocolV1.VERSION.toInt(),
        totalChunks = totalChunks,
    )

    private fun normalizeMime(mime: String, type: MediaType): String {
        val trimmed = mime.trim().lowercase()
        val expectedPrefix = if (type == MediaType.VIDEO) "video/" else "image/"
        val valid = MIME_PATTERN.matches(trimmed) && trimmed.startsWith(expectedPrefix)
        return when {
            valid -> trimmed
            type == MediaType.VIDEO -> "video/mp4"
            else -> "image/jpeg"
        }
    }

    private suspend fun streamChunks(
        localId: Long,
        uri: Uri,
        uploadId: String,
        mediaId: String,
        master: ByteArray,
        totalChunks: Int,
        uploaded: MutableSet<Int>,
    ) {
        val chunkSize = ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(chunkSize)
            try {
                for (index in 0 until totalChunks) {
                    var offset = 0
                    while (offset < chunkSize) {
                        val read = input.read(buffer, offset, chunkSize - offset)
                        if (read < 0) break
                        offset += read
                    }
                    if (index in uploaded) continue
                    check(offset > 0) { "Unexpected EOF before chunk $index" }

                    val plaintext = buffer.copyOf(offset)
                    try {
                        mediaDao.updateBackupStatus(localId, BackupStatus.ENCRYPTING, null, System.currentTimeMillis())
                        val encrypted = encryptor.encrypt(master, mediaId, BlobType.ORIGINAL, index, plaintext).encode()
                        try {
                            mediaDao.updateBackupStatus(localId, BackupStatus.UPLOADING, null, System.currentTimeMillis())
                            api.uploadChunk(uploadId, index, encrypted.toRequestBody(OCTET_STREAM))
                        } finally {
                            encrypted.fill(0)
                        }
                    } finally {
                        plaintext.fill(0)
                    }
                    uploaded += index
                    persistSession(uploadId, localId, totalChunks, uploaded)
                }
            } finally {
                buffer.fill(0)
            }
        } ?: error("MediaStore item is no longer readable")
    }

    private suspend fun uploadThumbnail(uri: Uri, uploadId: String, mediaId: String, master: ByteArray) {
        val jpeg = thumbnails.jpeg512(uri)
        try {
            require(jpeg.size <= ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES) { "Thumbnail plaintext exceeds chunk limit" }
            val encrypted = encryptor.encrypt(master, mediaId, BlobType.THUMBNAIL, 0, jpeg).encode()
            try {
                api.uploadThumbnail(uploadId, encrypted.toRequestBody(OCTET_STREAM))
            } finally {
                encrypted.fill(0)
            }
        } finally {
            jpeg.fill(0)
        }
    }

    private suspend fun markBackedUp(item: MediaEntity, mediaId: String, fingerprint: String) {
        mediaDao.upsert(
            item.copy(
                mediaId = mediaId,
                mediaFingerprint = fingerprint,
                localState = LocalState.LOCAL_AND_CLOUD,
                cloudState = CloudState.PRESENT,
                backupStatus = BackupStatus.BACKED_UP,
                thumbnailState = ThumbnailState.UPLOADED,
                lastError = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private suspend fun fail(localId: Long, message: String) {
        mediaDao.updateBackupStatus(localId, BackupStatus.FAILED, message.take(500), System.currentTimeMillis())
    }

    private suspend fun persistSession(
        uploadId: String,
        localMediaId: Long,
        totalChunks: Int,
        uploaded: Set<Int>,
    ) {
        transferDao.upsertUpload(
            UploadSessionEntity(
                uploadId = uploadId,
                localMediaId = localMediaId,
                plaintextChunkSize = ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES,
                totalChunks = totalChunks,
                uploadedChunkIndexes = uploaded.sorted().joinToString(","),
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun totalChunksFor(sizeBytes: Long): Int {
        val size = sizeBytes.coerceAtLeast(1)
        return ((size + ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES - 1) / ChunkProtocolV1.PLAINTEXT_CHUNK_BYTES).toInt()
    }

    private fun httpMessage(error: HttpException): String {
        val body = runCatching { error.response()?.errorBody()?.string() }.getOrNull()
        return body?.takeIf { it.isNotBlank() } ?: (error.message() ?: "HTTP ${error.code()}")
    }

    private companion object {
        val OCTET_STREAM = "application/octet-stream".toMediaType()
        val MIME_PATTERN = Regex("^(image|video)/[A-Za-z0-9][A-Za-z0-9.+-]{0,110}$")
    }
}
