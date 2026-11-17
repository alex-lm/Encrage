package com.encrage.vault.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MediaDaoInstrumentedTest {
    private lateinit var db: EncrageDatabase
    private lateinit var dao: MediaDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), EncrageDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.mediaDao()
    }

    @After fun close() = db.close()

    @Test fun stateTransitionIsPersisted() = runTest {
        val id = dao.upsert(sample())
        dao.updateBackupStatus(id, BackupStatus.UPLOADING, null, 2)
        assertEquals(BackupStatus.UPLOADING, dao.byId(id)?.backupStatus)
    }

    @Test fun localCloudMergeRetainsSingleRecord() = runTest {
        val id = dao.upsert(sample())
        dao.upsert(sample().copy(localId = id, mediaId = "cloud-1", cloudState = CloudState.PRESENT, localState = LocalState.LOCAL_AND_CLOUD))
        assertEquals("cloud-1", dao.byMediaStoreUri("content://media/1")?.mediaId)
    }

    @Test fun removedLocalCopyRemainsAsCloudOnly() = runTest {
        val id = dao.upsert(
            sample().copy(
                mediaId = "cloud-1",
                cloudState = CloudState.PRESENT,
                localState = LocalState.LOCAL_AND_CLOUD,
                backupStatus = BackupStatus.BACKED_UP,
                thumbnailState = ThumbnailState.UPLOADED,
            ),
        )

        dao.markAllLocalUnobserved(now = 3)

        val item = dao.byId(id)
        assertEquals(LocalState.CLOUD_ONLY, item?.localState)
        assertEquals(ThumbnailState.CLOUD_ONLY, item?.thumbnailState)
        assertNull(item?.mediaStoreUri)
        assertEquals("cloud-1", item?.mediaId)
    }

    @Test fun removedUnbackedCopyBecomesMissing() = runTest {
        val id = dao.upsert(sample())

        dao.markUnobservedMissing(listOf("content://media/another"), now = 3)

        assertEquals(LocalState.MISSING, dao.byId(id)?.localState)
    }

    private fun sample() = MediaEntity(
        mediaStoreUri = "content://media/1", mimeType = "image/jpeg", mediaType = MediaType.IMAGE,
        dateTimeTakenEpochMs = 1, width = 10, height = 10, sizeBytes = 10,
        localState = LocalState.LOCAL_ONLY, cloudState = CloudState.NONE,
        backupStatus = BackupStatus.WAITING, thumbnailState = ThumbnailState.LOCAL,
        createdAtEpochMs = 1, updatedAtEpochMs = 1,
    )
}
