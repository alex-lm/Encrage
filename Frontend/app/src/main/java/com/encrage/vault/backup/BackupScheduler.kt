package com.encrage.vault.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.encrage.vault.crypto.MasterKeyManager
import com.encrage.vault.data.local.MediaDao
import com.encrage.vault.data.preferences.AppPreferences
import com.encrage.vault.data.preferences.BackupPreferences
import com.encrage.vault.data.remote.SessionRepository
import com.encrage.vault.data.remote.SessionState
import com.encrage.vault.data.remote.SessionTokenStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
) {
    fun scheduleReconciliation(preferences: BackupPreferences = BackupPreferences()) {
        val constraints = constraintsFor(preferences, requireNetwork = false)
        val request = PeriodicWorkRequestBuilder<ReconciliationWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_SCAN, ExistingPeriodicWorkPolicy.UPDATE, request,
        )
        if (preferences.automaticBackup && preferences.backgroundBackup) {
            requestBackup(force = false, preferences = preferences)
        }
    }

    fun requestImmediateScan() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_SCAN,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReconciliationWorker>().build(),
        )
    }

    fun requestBackup(force: Boolean = false, preferences: BackupPreferences? = null) {
        val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        enqueueBackup(force, preferences, policy)
    }

    /** Called by BackupWorker when more items remain after the current run finishes. */
    fun continueBackup(force: Boolean = false, preferences: BackupPreferences? = null) {
        enqueueBackup(force, preferences, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    suspend fun requestBackupWithStoredPreferences(force: Boolean = false) {
        requestBackup(force = force, preferences = preferences.backup.first())
    }

    fun hasFullLibraryAccess(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun enqueueBackup(
        force: Boolean,
        preferences: BackupPreferences?,
        policy: ExistingWorkPolicy,
    ) {
        val prefs = preferences ?: BackupPreferences()
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraintsFor(prefs, requireNetwork = true, force = force))
            .setInputData(workDataOf(BackupWorker.KEY_FORCE to force))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BACKUP_UPLOAD, policy, request)
    }

    private fun constraintsFor(
        preferences: BackupPreferences,
        requireNetwork: Boolean = false,
        force: Boolean = false,
    ): Constraints {
        val builder = Constraints.Builder()
        if (!force) {
            builder.setRequiresCharging(preferences.onlyWhileCharging)
        }
        if (requireNetwork) {
            builder.setRequiredNetworkType(
                when {
                    force || preferences.useMobileData -> NetworkType.CONNECTED
                    else -> NetworkType.UNMETERED
                },
            )
        }
        return builder.build()
    }

    private companion object {
        const val PERIODIC_SCAN = "encrage.media.reconciliation"
        const val IMMEDIATE_SCAN = "encrage.media.scan.now"
        const val BACKUP_UPLOAD = "encrage.media.backup.upload"
    }
}

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mediaDao: MediaDao,
    private val uploader: MediaBackupUploader,
    private val preferences: AppPreferences,
    private val sessions: SessionRepository,
    private val tokens: SessionTokenStore,
    private val masterKeys: MasterKeyManager,
    private val scheduler: BackupScheduler,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val prefs = preferences.backup.first()
        if (!prefs.automaticBackup && !force) return Result.success()
        if (!prefs.backgroundBackup && !force) return Result.success()
        if (!masterKeys.hasKey()) return Result.success()
        if (tokens.accessToken() == null && tokens.refreshToken() == null) return Result.success()
        if (sessions.state.value is SessionState.SignedOut) return Result.success()

        var processed = 0
        var retryableFailure = false
        val skippedIds = linkedSetOf<Long>()
        while (processed < MAX_ITEMS_PER_RUN) {
            val batch = mediaDao.pendingBackups(BATCH_SIZE + skippedIds.size)
                .asSequence()
                .filter { it.localId !in skippedIds }
                .take(BATCH_SIZE)
                .toList()
            if (batch.isEmpty()) break
            for (item in batch) {
                when (val outcome = uploader.upload(item, prefs)) {
                    UploadOutcome.BackedUp -> processed += 1
                    UploadOutcome.Skipped -> {
                        skippedIds += item.localId
                        processed += 1
                    }
                    is UploadOutcome.Failed -> {
                        skippedIds += item.localId
                        processed += 1
                        if (outcome.retryable) retryableFailure = true
                    }
                    is UploadOutcome.Fatal -> return if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
                if (processed >= MAX_ITEMS_PER_RUN) break
            }
        }

        val stillPending = mediaDao.pendingBackups(skippedIds.size + 1)
            .any { it.localId !in skippedIds }
        if (stillPending) {
            scheduler.continueBackup(force = force, preferences = prefs)
        }
        return when {
            retryableFailure && processed == 0 -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        private const val BATCH_SIZE = 3
        private const val MAX_ITEMS_PER_RUN = 15
    }
}
