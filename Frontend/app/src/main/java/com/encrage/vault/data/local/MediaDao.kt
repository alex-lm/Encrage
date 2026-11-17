package com.encrage.vault.data.local

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class BackupCounts(
    val waiting: Int,
    val active: Int,
    val failed: Int,
    val backedUp: Int,
    val bytesRemaining: Long,
    val totalLocalBytes: Long,
)

@Dao
interface MediaDao {
    @Query(
        """SELECT * FROM media
        WHERE localState != 'MISSING'
          AND (:mediaType IS NULL OR mediaType = :mediaType)
          AND (:localState IS NULL OR localState = :localState)
        ORDER BY dateTimeTakenEpochMs DESC, localId DESC""",
    )
    fun pagingSource(mediaType: MediaType?, localState: LocalState?): PagingSource<Int, MediaEntity>

    @Query("SELECT * FROM media WHERE localId = :id")
    suspend fun byId(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE mediaStoreUri = :uri LIMIT 1")
    suspend fun byMediaStoreUri(uri: String): MediaEntity?

    @Query("SELECT * FROM media WHERE mediaFingerprint = :fingerprint LIMIT 1")
    suspend fun byFingerprint(fingerprint: String): MediaEntity?

    @Upsert
    suspend fun upsert(item: MediaEntity): Long

    @Upsert
    suspend fun upsertAll(items: List<MediaEntity>)

    @Query("DELETE FROM media WHERE localId = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE media SET backupStatus = :status, lastError = :error, updatedAtEpochMs = :now WHERE localId = :id")
    suspend fun updateBackupStatus(id: Long, status: BackupStatus, error: String?, now: Long)

    @Query(
        """SELECT
        COALESCE(SUM(CASE WHEN backupStatus IN ('WAITING','PREPARING') THEN 1 ELSE 0 END), 0) AS waiting,
        COALESCE(SUM(CASE WHEN backupStatus IN ('ENCRYPTING','UPLOADING','DOWNLOADING') THEN 1 ELSE 0 END), 0) AS active,
        COALESCE(SUM(CASE WHEN backupStatus = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed,
        COALESCE(SUM(CASE WHEN backupStatus = 'BACKED_UP' THEN 1 ELSE 0 END), 0) AS backedUp,
        COALESCE(SUM(CASE WHEN localState IN ('LOCAL_ONLY','LOCAL_AND_CLOUD') AND backupStatus != 'BACKED_UP' THEN sizeBytes ELSE 0 END), 0) AS bytesRemaining,
        COALESCE(SUM(CASE WHEN localState IN ('LOCAL_ONLY','LOCAL_AND_CLOUD') THEN sizeBytes ELSE 0 END), 0) AS totalLocalBytes
        FROM media""",
    )
    fun observeBackupCounts(): Flow<BackupCounts>

    @Query(
        """SELECT * FROM media
        WHERE backupStatus IN ('WAITING','FAILED','PREPARING','ENCRYPTING','UPLOADING')
          AND mediaStoreUri IS NOT NULL
          AND localState != 'MISSING'
        ORDER BY
          CASE backupStatus
            WHEN 'UPLOADING' THEN 0
            WHEN 'ENCRYPTING' THEN 1
            WHEN 'PREPARING' THEN 2
            WHEN 'FAILED' THEN 3
            ELSE 4
          END,
          dateTimeTakenEpochMs
        LIMIT :limit""",
    )
    suspend fun pendingBackups(limit: Int): List<MediaEntity>

    @Query(
        """UPDATE media SET
        localState = CASE WHEN cloudState = 'PRESENT' THEN 'CLOUD_ONLY' ELSE 'MISSING' END,
        mediaStoreUri = CASE WHEN cloudState = 'PRESENT' THEN NULL ELSE mediaStoreUri END,
        mediaStoreId = CASE WHEN cloudState = 'PRESENT' THEN NULL ELSE mediaStoreId END,
        mediaStoreGeneration = CASE WHEN cloudState = 'PRESENT' THEN NULL ELSE mediaStoreGeneration END,
        thumbnailState = CASE WHEN cloudState = 'PRESENT' THEN 'CLOUD_ONLY' ELSE thumbnailState END,
        updatedAtEpochMs = :now
        WHERE mediaStoreUri IS NOT NULL AND mediaStoreUri NOT IN (:observedUris)""",
    )
    suspend fun markUnobservedMissing(observedUris: List<String>, now: Long)

    @Query(
        """UPDATE media SET
        localState = CASE WHEN cloudState = 'PRESENT' THEN 'CLOUD_ONLY' ELSE 'MISSING' END,
        mediaStoreUri = CASE WHEN cloudState = 'PRESENT' THEN NULL ELSE mediaStoreUri END,
        mediaStoreId = CASE WHEN cloudState = 'PRESENT' THEN NULL ELSE mediaStoreId END,
        mediaStoreGeneration = CASE WHEN cloudState = 'PRESENT' THEN NULL ELSE mediaStoreGeneration END,
        thumbnailState = CASE WHEN cloudState = 'PRESENT' THEN 'CLOUD_ONLY' ELSE thumbnailState END,
        updatedAtEpochMs = :now
        WHERE mediaStoreUri IS NOT NULL""",
    )
    suspend fun markAllLocalUnobserved(now: Long)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM upload_sessions WHERE localMediaId = :localMediaId LIMIT 1")
    suspend fun uploadForMedia(localMediaId: Long): UploadSessionEntity?

    @Upsert
    suspend fun upsertUpload(upload: UploadSessionEntity)

    @Query("DELETE FROM upload_sessions WHERE uploadId = :uploadId")
    suspend fun deleteUpload(uploadId: String)

    @Query("SELECT * FROM remote_cursor WHERE feed = :feed")
    suspend fun cursor(feed: String = "media"): RemoteCursorEntity?

    @Upsert
    suspend fun upsertCursor(cursor: RemoteCursorEntity)
}
