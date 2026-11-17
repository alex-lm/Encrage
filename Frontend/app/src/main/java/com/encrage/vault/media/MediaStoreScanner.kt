package com.encrage.vault.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import com.encrage.vault.data.local.BackupStatus
import com.encrage.vault.data.local.CloudState
import com.encrage.vault.data.local.LocalState
import com.encrage.vault.data.local.MediaDao
import com.encrage.vault.data.local.MediaEntity
import com.encrage.vault.data.local.MediaType
import com.encrage.vault.data.local.ThumbnailState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ScanResult(val discovered: Int, val inaccessible: Boolean)

@Singleton
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaDao: MediaDao,
) {
    suspend fun scan(fullLibraryAccess: Boolean): ScanResult = withContext(Dispatchers.IO) {
        val observed = ArrayList<String>()
        val discovered = ArrayList<MediaEntity>()
        var inaccessible = false
        for ((collection, type) in collections()) {
            try {
                context.contentResolver.query(
                    collection,
                    projection(type),
                    "${MediaStore.MediaColumns.SIZE} > 0",
                    null,
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val takenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    val addedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
                    val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val durationIndex = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                    val generationIndex = cursor.getColumnIndex(MediaStore.MediaColumns.GENERATION_ADDED)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIndex)
                        val uri = ContentUris.withAppendedId(collection, id).toString()
                        observed += uri
                        val existing = mediaDao.byMediaStoreUri(uri)
                        val now = System.currentTimeMillis()
                        discovered += MediaEntity(
                            localId = existing?.localId ?: 0,
                            mediaId = existing?.mediaId,
                            mediaFingerprint = existing?.mediaFingerprint,
                            mediaStoreUri = uri,
                            mediaStoreId = id,
                            mediaStoreGeneration = generationIndex.takeIf { it >= 0 }?.let(cursor::getLong),
                            mimeType = cursor.getString(mimeIndex) ?: if (type == MediaType.IMAGE) "image/*" else "video/*",
                            mediaType = type,
                            dateTimeTakenEpochMs = cursor.getLong(takenIndex).takeIf { it > 0 }
                                ?: (cursor.getLong(addedIndex) * 1000L).takeIf { it > 0 } ?: now,
                            width = cursor.getInt(widthIndex),
                            height = cursor.getInt(heightIndex),
                            durationSeconds = if (type == MediaType.VIDEO && durationIndex >= 0) cursor.getLong(durationIndex) / 1000 else null,
                            sizeBytes = cursor.getLong(sizeIndex),
                            localState = if (existing?.mediaId != null) LocalState.LOCAL_AND_CLOUD else LocalState.LOCAL_ONLY,
                            cloudState = existing?.cloudState ?: CloudState.NONE,
                            backupStatus = existing?.backupStatus ?: BackupStatus.WAITING,
                            thumbnailState = ThumbnailState.LOCAL,
                            lastError = existing?.lastError,
                            createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                            updatedAtEpochMs = now,
                        )
                    }
                }
            } catch (_: SecurityException) {
                inaccessible = true
            }
        }
        if (discovered.isNotEmpty()) mediaDao.upsertAll(discovered)
        if (fullLibraryAccess && !inaccessible) {
            val now = System.currentTimeMillis()
            if (observed.isEmpty()) mediaDao.markAllLocalUnobserved(now)
            else mediaDao.markUnobservedMissing(observed, now)
        }
        ScanResult(discovered.size, inaccessible)
    }

    private fun projection(type: MediaType) = buildList {
        add(MediaStore.MediaColumns._ID)
        add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.Images.ImageColumns.DATE_TAKEN)
        add(MediaStore.MediaColumns.DATE_ADDED)
        add(MediaStore.MediaColumns.WIDTH)
        add(MediaStore.MediaColumns.HEIGHT)
        add(MediaStore.MediaColumns.SIZE)
        if (type == MediaType.VIDEO) add(MediaStore.Video.VideoColumns.DURATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.GENERATION_ADDED)
    }.toTypedArray()

    private fun collections() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to MediaType.IMAGE,
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) to MediaType.VIDEO,
    ) else listOf(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to MediaType.IMAGE,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI to MediaType.VIDEO,
    )
}

class ThumbnailGenerator @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun load512(uri: android.net.Uri): Bitmap = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(512, 512), null) }.getOrElse { frameBitmap(uri) }
        } else {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: error("Unable to decode media thumbnail")
            }.getOrElse { frameBitmap(uri) }
        }
    }

    /** Returns a JPEG-encoded ~512px thumbnail suitable for encrypted upload. */
    suspend fun jpeg512(uri: android.net.Uri, quality: Int = 85): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = load512(uri)
        try {
            val scaled = scaleMax(bitmap, 512)
            try {
                java.io.ByteArrayOutputStream().use { out ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG encode failed" }
                    out.toByteArray()
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun frameBitmap(uri: android.net.Uri): Bitmap {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(-1) ?: error("Unable to extract video frame")
        } finally {
            retriever.release()
        }
    }

    private fun scaleMax(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(source.width, source.height).coerceAtLeast(1)
        if (longest <= maxEdge) return source
        val scale = maxEdge.toFloat() / longest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}
