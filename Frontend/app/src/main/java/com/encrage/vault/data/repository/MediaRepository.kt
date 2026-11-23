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

    /** Capture times in gallery order, used to build date sections without loading full rows. */
    fun timeline(mediaType: MediaType? = null, localState: LocalState? = null): Flow<List<Long>> =
        mediaDao.observeTimeline(mediaType, localState)

    fun recentFailureReasons(): Flow<List<String>> = mediaDao.observeRecentFailureReasons()

    /**
     * Walks every cursor page from the newest item and merges it into Room. Bounded so a misbehaving
     * server cannot keep the loop alive forever. Room remains the only gallery source.
     */
    suspend fun syncAllCloudPages(maxPages: Int = MAX_SYNC_PAGES) {
        var reset = true
        repeat(maxPages) {
            if (!syncNextCloudPage(reset)) return
            reset = false
        }
    }

    /** Pulls one cursor page and merges it into Room. Returns true while more pages remain. */
    suspend fun syncNextCloudPage(reset: Boolean = false): Boolean {
        val savedCursor = if (reset) null else transferDao.cursor()
        if (savedCursor?.endReached == true) return false
        val page = api.media(savedCursor?.nextCursor, limit = SYNC_PAGE_SIZE)
        val now = System.currentTimeMillis()
        val merged = page.items.mapNotNull { remote ->
            // Prefer the row that already owns this cloud record, so the unique mediaId index can't conflict.
            val local = mediaDao.byMediaId(remote.mediaId) ?: mediaDao.byFingerprint(remote.fingerprint)
            val next = MediaEntity(
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
            // Skip unchanged rows: rewriting thousands of identical rows invalidates the gallery on every launch.
            next.takeUnless { local != null && local.copy(updatedAtEpochMs = 0) == next.copy(updatedAtEpochMs = 0) }
        }
        if (merged.isNotEmpty()) mediaDao.upsertAll(merged)
        transferDao.upsertCursor(RemoteCursorEntity(nextCursor = page.nextCursor, endReached = page.nextCursor == null, updatedAtEpochMs = now))
        return page.nextCursor != null && page.items.isNotEmpty()
    }

    private fun parseEpochMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private companion object {
        const val SYNC_PAGE_SIZE = 200 // the server's maximum page size
        const val MAX_SYNC_PAGES = 500
    }
}

data class DeleteResult(val deleted: Int, val failures: List<String>)
