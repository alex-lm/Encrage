package com.encrage.vault.data.repository

import android.content.ContentResolver
import android.net.Uri
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.encrage.vault.media.CloudOriginalMedia
import com.encrage.vault.data.local.BackupStatus
import com.encrage.vault.data.local.CloudState
import com.encrage.vault.data.local.LocalState
import com.encrage.vault.data.local.MediaDao
import com.encrage.vault.data.local.MediaEntity
import com.encrage.vault.data.local.MediaType
import com.encrage.vault.data.local.RemoteCursorEntity
import com.encrage.vault.data.local.ThumbnailState
import com.encrage.vault.data.local.TransferDao
import com.encrage.vault.data.remote.EncrageApi
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val mediaDao: MediaDao,
    private val transferDao: TransferDao,
    private val api: EncrageApi,
    private val cloudOriginals: CloudOriginalMedia,
    private val contentResolver: ContentResolver,
) {
    fun gallery(mediaType: MediaType? = null, localState: LocalState? = null): Flow<PagingData<MediaEntity>> = Pager(
        config = PagingConfig(
            pageSize = 90,
            initialLoadSize = 180,
            prefetchDistance = 120,
            enablePlaceholders = true,
            maxSize = 720,
        ),
        pagingSourceFactory = { mediaDao.pagingSource(mediaType, localState) },
    ).flow

    fun backupCounts() = mediaDao.observeBackupCounts()

    suspend fun downloadCloudOnly(items: List<MediaEntity>) = cloudOriginals.download(items)

    suspend fun deleteMedia(items: List<MediaEntity>, localAlreadyDeleted: Boolean): DeleteResult {
        var deleted = 0
        val failures = mutableListOf<String>()
        items.forEach { item ->
            val localDeleted = item.mediaStoreUri == null || localAlreadyDeleted || runCatching {
                contentResolver.delete(Uri.parse(item.mediaStoreUri), null, null)
                true
            }.getOrDefault(false)
            val cloudDeleted = if (!localDeleted) {
                false
            } else if (item.mediaId == null) {
                true
            } else {
                runCatching { api.deleteMedia(item.mediaId) }.fold(
                    onSuccess = { response -> response.isSuccessful || response.code() == 404 },
                    onFailure = { false },
                )
            }
            val now = System.currentTimeMillis()
            when {
                localDeleted && cloudDeleted -> {
                    mediaDao.deleteById(item.localId)
                    deleted++
                }
                localDeleted -> {
                    mediaDao.upsert(
                        item.copy(
                            mediaStoreUri = null,
                            mediaStoreId = null,
                            mediaStoreGeneration = null,
                            localState = LocalState.CLOUD_ONLY,
                            thumbnailState = ThumbnailState.CLOUD_ONLY,
                            lastError = "Cloud deletion failed",
                            updatedAtEpochMs = now,
                        ),
                    )
                    failures += "${item.localId}: cloud deletion failed"
                }
                cloudDeleted -> {
                    mediaDao.upsert(
                        item.copy(
                            mediaId = null,
                            mediaFingerprint = null,
                            localState = LocalState.LOCAL_ONLY,
                            cloudState = CloudState.DELETED,
                            backupStatus = BackupStatus.WAITING,
                            lastError = "Phone deletion failed",
                            updatedAtEpochMs = now,
                        ),
                    )
                    failures += "${item.localId}: phone deletion failed"
                }
                else -> failures += "${item.localId}: deletion failed"
            }
        }
        return DeleteResult(deleted, failures)
    }

    /** Pulls one cursor page and merges it into Room. Room remains the only gallery source. */
    suspend fun syncNextCloudPage(reset: Boolean = false) {
        val savedCursor = if (reset) null else transferDao.cursor()
        if (savedCursor?.endReached == true) return
        val page = api.media(savedCursor?.nextCursor)
        val now = System.currentTimeMillis()
        val merged = page.items.map { remote ->
            val local = mediaDao.byFingerprint(remote.fingerprint)
            MediaEntity(
                localId = local?.localId ?: 0,
                mediaId = remote.mediaId,
                mediaFingerprint = remote.fingerprint,
                mediaStoreUri = local?.mediaStoreUri,
                mediaStoreId = local?.mediaStoreId,
                mediaStoreGeneration = local?.mediaStoreGeneration,
                mimeType = remote.mimeType,
                mediaType = if (remote.mediaType.equals("video", true)) MediaType.VIDEO else MediaType.IMAGE,
                dateTimeTakenEpochMs = parseEpochMs(remote.datetimeTaken) ?: local?.dateTimeTakenEpochMs ?: now,
                width = remote.width ?: local?.width ?: 0,
                height = remote.height ?: local?.height ?: 0,
                durationSeconds = remote.durationSeconds?.toLong() ?: local?.durationSeconds,
                sizeBytes = remote.sizeBytes,
                localState = if (local?.mediaStoreUri != null) LocalState.LOCAL_AND_CLOUD else LocalState.CLOUD_ONLY,
                cloudState = CloudState.PRESENT,
                backupStatus = BackupStatus.BACKED_UP,
                thumbnailState = if (local?.mediaStoreUri != null) ThumbnailState.LOCAL else ThumbnailState.CLOUD_ONLY,
                lastError = null,
                createdAtEpochMs = local?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
            )
        }
        mediaDao.upsertAll(merged)
        transferDao.upsertCursor(RemoteCursorEntity(nextCursor = page.nextCursor, endReached = page.nextCursor == null, updatedAtEpochMs = now))
    }

    private fun parseEpochMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

data class DeleteResult(val deleted: Int, val failures: List<String>)
