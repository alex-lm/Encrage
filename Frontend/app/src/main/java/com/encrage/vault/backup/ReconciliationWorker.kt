package com.encrage.vault.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.encrage.vault.data.preferences.AppPreferences
import com.encrage.vault.media.MediaStoreScanner
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class ReconciliationWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: MediaStoreScanner,
    private val scheduler: BackupScheduler,
    private val preferences: AppPreferences,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        scanner.scan(scheduler.hasFullLibraryAccess())
        val prefs = preferences.backup.first()
        if (prefs.automaticBackup) {
            scheduler.requestBackup(force = false, preferences = prefs)
        }
        Result.success()
    } catch (_: SecurityException) {
        Result.success() // Permission state is reflected in UI; retrying cannot grant it.
    } catch (_: Exception) {
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
