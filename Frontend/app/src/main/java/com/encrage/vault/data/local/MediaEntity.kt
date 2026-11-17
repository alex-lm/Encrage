package com.encrage.vault.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaType { IMAGE, VIDEO }
enum class LocalState { LOCAL_ONLY, LOCAL_AND_CLOUD, CLOUD_ONLY, MISSING }
enum class CloudState { NONE, QUEUED, PRESENT, DELETED }
enum class BackupStatus { WAITING, PREPARING, ENCRYPTING, UPLOADING, BACKED_UP, DOWNLOADING, FAILED }
enum class ThumbnailState { LOCAL, NEEDED, UPLOADED, CLOUD_ONLY, FAILED }

@Entity(
    tableName = "media",
    indices = [
        Index(value = ["mediaStoreUri"], unique = true),
        Index(value = ["mediaId"], unique = true),
        Index(value = ["mediaFingerprint"]),
        Index(value = ["dateTimeTakenEpochMs"]),
        Index(value = ["backupStatus"]),
    ],
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val mediaId: String? = null,
    val mediaFingerprint: String? = null,
    val mediaStoreUri: String? = null,
    val mediaStoreId: Long? = null,
    val mediaStoreGeneration: Long? = null,
    val mimeType: String,
    val mediaType: MediaType,
    val dateTimeTakenEpochMs: Long,
    val width: Int,
    val height: Int,
    val durationSeconds: Long? = null,
    val sizeBytes: Long,
    val localState: LocalState,
    val cloudState: CloudState,
    val backupStatus: BackupStatus,
    val thumbnailState: ThumbnailState,
    val lastError: String? = null,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "upload_sessions",
    indices = [Index(value = ["localMediaId"], unique = true)],
)
data class UploadSessionEntity(
    @PrimaryKey val uploadId: String,
    val localMediaId: Long,
    val plaintextChunkSize: Int = 4 * 1024 * 1024,
    val totalChunks: Int,
    val uploadedChunkIndexes: String = "",
    val updatedAtEpochMs: Long,
)

@Entity(tableName = "remote_cursor")
data class RemoteCursorEntity(
    @PrimaryKey val feed: String = "media",
    val nextCursor: String?,
    val endReached: Boolean,
    val updatedAtEpochMs: Long,
)

