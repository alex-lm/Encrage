package com.encrage.vault.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.media3.datasource.DataSource
import com.encrage.vault.backup.BackupScheduler
import com.encrage.vault.crypto.MasterKeyManager
import com.encrage.vault.data.local.BackupCounts
import com.encrage.vault.data.local.LocalState
import com.encrage.vault.data.local.MediaType
import com.encrage.vault.data.local.MediaEntity
import com.encrage.vault.data.preferences.AppPreferences
import com.encrage.vault.data.preferences.BackupPreferences
import com.encrage.vault.data.remote.EncrageApi
import com.encrage.vault.data.remote.ChangePasswordRequest
import com.encrage.vault.data.remote.ServerReachability
import com.encrage.vault.data.remote.SessionRepository
import com.encrage.vault.data.repository.MediaRepository
import com.encrage.vault.media.MediaStoreScanner
import com.encrage.vault.media.CloudThumbnailLoader
import com.encrage.vault.media.CloudOriginalMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class AuthUiState(val busy: Boolean = false, val error: String? = null)

@HiltViewModel
class AppViewModel @Inject constructor(
    private val sessions: SessionRepository,
    private val masterKeys: MasterKeyManager,
    private val scanner: MediaStoreScanner,
    private val scheduler: BackupScheduler,
) : ViewModel() {
    val session = sessions.state
    private val mutableAuthUi = MutableStateFlow(AuthUiState())
    val authUi = mutableAuthUi.asStateFlow()
    private val mutableHasMasterKey = MutableStateFlow(masterKeys.hasKey())
    val hasMasterKey = mutableHasMasterKey.asStateFlow()
    private val mutableGeneratedKey = MutableStateFlow(masterKeys.pendingRecoveryKey())
    val generatedKey = mutableGeneratedKey.asStateFlow()

    init { viewModelScope.launch { sessions.restore() } }

    fun login(username: String, password: String) = viewModelScope.launch {
        mutableAuthUi.value = AuthUiState(busy = true)
        mutableAuthUi.value = try {
            sessions.login(username, password)
            scheduler.requestBackupWithStoredPreferences(force = true)
            AuthUiState()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            AuthUiState(error = UserMessages.signIn(t))
        }
    }

    fun clearAuthError() {
        if (mutableAuthUi.value.error != null) mutableAuthUi.value = mutableAuthUi.value.copy(error = null)
    }

    fun generateRecoveryKey() {
        mutableGeneratedKey.value = masterKeys.generate()
    }

    fun acknowledgeGeneratedRecoveryKey() {
        masterKeys.acknowledgeGeneratedKey()
        mutableHasMasterKey.value = true
        mutableGeneratedKey.value = null
        viewModelScope.launch { scheduler.requestBackupWithStoredPreferences(force = true) }
    }

    fun importRecoveryKey(value: String) {
        // Keys are displayed in groups of four; accept pasted or typed spacing and line breaks.
        runCatching { masterKeys.import(value.filterNot(Char::isWhitespace)) }
            .onSuccess {
                mutableHasMasterKey.value = true
                mutableAuthUi.value = AuthUiState()
                viewModelScope.launch { scheduler.requestBackupWithStoredPreferences(force = true) }
            }
            .onFailure { mutableAuthUi.value = AuthUiState(error = "That Recovery Key isn’t valid. Check for missing or extra characters.") }
    }

    fun scanLibrary() = viewModelScope.launch {
        runCatching { scanner.scan(scheduler.hasFullLibraryAccess()) }
        scheduler.requestBackupWithStoredPreferences(force = false)
    }
}

enum class MediaFilter(val mediaType: MediaType?) {
    ALL(null), PHOTOS(MediaType.IMAGE), VIDEOS(MediaType.VIDEO),
}

enum class BackupLocationFilter(val localState: LocalState?) {
    ALL(null), PHONE_ONLY(LocalState.LOCAL_ONLY), CLOUD_ONLY(LocalState.CLOUD_ONLY),
    PHONE_AND_CLOUD(LocalState.LOCAL_AND_CLOUD),
}

data class PhotosFilters(
    val media: MediaFilter = MediaFilter.ALL,
    val location: BackupLocationFilter = BackupLocationFilter.ALL,
)

data class MediaActionUi(
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

/** One calendar day of the gallery. Media indices refer to positions in the paged gallery list. */
data class DaySection(val day: LocalDate, val firstMediaIndex: Int, val count: Int)

/** Sections are calendar days, or calendar months ([monthly]) for dense grids where days would be mostly empty rows. */
data class GalleryTimeline(
    val sections: List<DaySection> = emptyList(),
    val loaded: Boolean = false,
    val monthly: Boolean = false,
) {
    val totalCount: Int get() = sections.lastOrNull()?.let { it.firstMediaIndex + it.count } ?: 0
}

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val cloudThumbnails: CloudThumbnailLoader,
    private val cloudOriginals: CloudOriginalMedia,
    private val scanner: MediaStoreScanner,
    private val scheduler: BackupScheduler,
    private val preferences: AppPreferences,
    reachability: ServerReachability,
) : ViewModel() {
    private val mutableFilters = MutableStateFlow(PhotosFilters())
    private val cloudThumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    val filters = mutableFilters.asStateFlow()
    private val mutableActionUi = MutableStateFlow(MediaActionUi())
    val actionUi = mutableActionUi.asStateFlow()
    private val mutableRefreshing = MutableStateFlow(false)
    val refreshing = mutableRefreshing.asStateFlow()
    val serverUnreachableSince = reachability.unreachableSince
    val columns = preferences.galleryColumns.stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences.DEFAULT_COLUMNS)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val gallery = mutableFilters.flatMapLatest { filters ->
        repository.gallery(filters.media.mediaType, filters.location.localState)
    }.cachedIn(viewModelScope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val timeline: StateFlow<GalleryTimeline> = combine(
        mutableFilters,
        columns.map { it >= MONTHLY_GROUPING_COLUMNS }.distinctUntilChanged(),
    ) { filters, monthly -> filters to monthly }.flatMapLatest { (filters, monthly) ->
        repository.timeline(filters.media.mediaType, filters.location.localState).map { buildTimeline(it, monthly) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryTimeline())

    fun selectMediaFilter(filter: MediaFilter) {
        mutableFilters.value = mutableFilters.value.copy(media = filter)
    }

    fun selectLocationFilter(filter: BackupLocationFilter) {
        mutableFilters.value = mutableFilters.value.copy(location = filter)
    }

    fun setColumns(count: Int) = viewModelScope.launch { preferences.setGalleryColumns(count) }

    suspend fun cloudThumbnail(mediaId: String): Bitmap? {
        cloudThumbnailCache.get(mediaId)?.let { return it }
        return runCatching { cloudThumbnails.load(mediaId) }.getOrNull()?.also {
            cloudThumbnailCache.put(mediaId, it)
        }
    }

    suspend fun cloudPhoto(item: MediaEntity): Bitmap? = try {
        cloudOriginals.loadPhoto(item)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled // a page that scrolled away is not a failure
    } catch (error: Exception) {
        if (com.encrage.vault.BuildConfig.DEBUG) {
            android.util.Log.w("Encrage", "Cloud original failed: ${error.javaClass.simpleName}")
        }
        null
    }

    fun cloudVideoDataSource(item: MediaEntity): DataSource.Factory =
        cloudOriginals.videoDataSourceFactory(item)

    fun downloadSelected(items: List<MediaEntity>) {
        val downloadable = items.filter { it.localState == LocalState.CLOUD_ONLY && it.mediaId != null }
        if (downloadable.isEmpty()) {
            mutableActionUi.value = MediaActionUi(error = "Those items are already on this phone")
            return
        }
        viewModelScope.launch {
            mutableActionUi.value = MediaActionUi(busy = true, message = "Saving ${plural(downloadable.size, "item")} to this phone…")
            val result = runCatching { repository.downloadCloudOnly(downloadable) }.getOrElse {
                mutableActionUi.value = MediaActionUi(error = UserMessages.action(it, "Download failed"))
                return@launch
            }
            mutableActionUi.value = if (result.failures.isEmpty()) {
                MediaActionUi(message = "Saved ${plural(result.downloaded, "item")} to DCIM/Encrage")
            } else {
                MediaActionUi(error = "Saved ${result.downloaded}; ${result.failures.size} couldn’t be downloaded")
            }
        }
    }

    fun deleteSelected(items: List<MediaEntity>, localAlreadyDeleted: Boolean) = viewModelScope.launch {
        mutableActionUi.value = MediaActionUi(busy = true)
        val result = runCatching { repository.deleteMedia(items, localAlreadyDeleted) }.getOrElse {
            mutableActionUi.value = MediaActionUi(error = UserMessages.action(it, "Deletion failed"))
            return@launch
        }
        mutableActionUi.value = if (result.failures.isEmpty()) {
            MediaActionUi(message = "Deleted ${plural(result.deleted, "item")}")
        } else {
            MediaActionUi(error = "Deleted ${result.deleted}; ${result.failures.size} couldn’t be deleted")
        }
    }

    fun reportActionError(message: String) { mutableActionUi.value = MediaActionUi(error = message) }
    fun clearActionMessage() { mutableActionUi.value = mutableActionUi.value.copy(message = null, error = null) }

    fun clearCloudThumbnailCache() = cloudThumbnailCache.evictAll()

    fun syncCloudMetadata() = viewModelScope.launch { runCatching { repository.syncAllCloudPages() } }

    /** Pull-to-refresh: rescan the phone, pull cloud changes, and nudge the backup queue. */
    fun refresh() {
        if (mutableRefreshing.value) return
        viewModelScope.launch {
            mutableRefreshing.value = true
            try {
                runCatching { scanner.scan(scheduler.hasFullLibraryAccess()) }
                val synced = runCatching { repository.syncAllCloudPages() }
                synced.exceptionOrNull()?.let {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    mutableActionUi.value = MediaActionUi(error = UserMessages.action(it, "Couldn’t refresh from the cloud"))
                }
                scheduler.requestBackupWithStoredPreferences(force = false)
            } finally {
                mutableRefreshing.value = false
            }
        }
    }

    override fun onCleared() {
        clearCloudThumbnailCache()
        super.onCleared()
    }

    private fun buildTimeline(times: List<Long>, monthly: Boolean): GalleryTimeline {
        val zone = ZoneId.systemDefault()
        val sections = ArrayList<DaySection>()
        var currentDay: LocalDate? = null
        var start = 0
        times.forEachIndexed { index, epochMs ->
            val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
            val day = if (monthly) date.withDayOfMonth(1) else date
            if (day != currentDay) {
                currentDay?.let { sections += DaySection(it, start, index - start) }
                currentDay = day
                start = index
            }
        }
        currentDay?.let { sections += DaySection(it, start, times.size - start) }
        return GalleryTimeline(sections, loaded = true, monthly = monthly)
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 24 * 1024 * 1024
        const val MONTHLY_GROUPING_COLUMNS = 5
    }
}

internal fun plural(count: Int, noun: String) = "$count ${if (count == 1) noun else "${noun}s"}"

/** Everything the Backup screen shows, combined so the screen renders one consistent snapshot. */
data class BackupUi(
    val counts: BackupCounts = BackupCounts(0, 0, 0, 0, 0, 0),
    val settings: BackupPreferences = BackupPreferences(),
    val failureReasons: List<String> = emptyList(),
    val serverUnreachableSince: Long? = null,
    val hasLibraryAccess: Boolean = true,
)

@HiltViewModel
class BackupViewModel @Inject constructor(
    repository: MediaRepository,
    private val preferences: AppPreferences,
    private val scheduler: BackupScheduler,
    reachability: ServerReachability,
) : ViewModel() {
    val counts = repository.backupCounts().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupCounts(0, 0, 0, 0, 0, 0),
    )
    val settings = preferences.backup.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupPreferences(),
    )
    val ui: StateFlow<BackupUi> = combine(
        counts,
        settings,
        repository.recentFailureReasons(),
        reachability.unreachableSince,
    ) { counts, settings, reasons, unreachable ->
        BackupUi(counts, settings, reasons.map(UserMessages::backupFailure).distinct(), unreachable)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupUi())

    fun update(transform: (BackupPreferences) -> BackupPreferences) = viewModelScope.launch {
        preferences.updateBackup(transform)
        val next = preferences.backup.first()
        scheduler.scheduleReconciliation(next)
        if (next.automaticBackup) {
            scheduler.requestImmediateScan()
            scheduler.requestBackup(force = false, preferences = next)
        }
    }

    /** Starts a forced upload pass once the signed-in shell is ready. */
    fun startBackup() {
        scheduler.requestImmediateScan()
        viewModelScope.launch { scheduler.requestBackupWithStoredPreferences(force = true) }
    }

    fun retry() = startBackup()
}

data class StorageUi(
    val used: Long = 0,
    val allocated: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
)
data class PasswordChangeUi(
    val busy: Boolean = false,
    val changed: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: EncrageApi,
    private val sessions: SessionRepository,
    private val keys: MasterKeyManager,
    private val preferences: AppPreferences,
    reachability: ServerReachability,
) : ViewModel() {
    private val mutableStorage = MutableStateFlow(StorageUi())
    val storage: StateFlow<StorageUi> = mutableStorage.asStateFlow()
    private val mutableRecoveryKey = MutableStateFlow<String?>(null)
    val recoveryKey = mutableRecoveryKey.asStateFlow()
    private val mutablePasswordChange = MutableStateFlow(PasswordChangeUi())
    val passwordChange = mutablePasswordChange.asStateFlow()
    val serverUnreachableSince = reachability.unreachableSince
    val apiEndpoint = preferences.apiBaseUrl.stateIn(
        viewModelScope, SharingStarted.Eagerly, com.encrage.vault.BuildConfig.API_BASE_URL,
    )

    fun loadStorage() = viewModelScope.launch {
        if (mutableStorage.value.loading) return@launch
        mutableStorage.value = mutableStorage.value.copy(loading = true, error = null)
        runCatching { api.storage() }.onSuccess {
            mutableStorage.value = StorageUi(it.usedBytes, it.allocatedBytes)
        }.onFailure {
            mutableStorage.value = mutableStorage.value.copy(
                loading = false,
                error = UserMessages.action(it, "Couldn’t load storage usage"),
            )
        }
    }

    fun revealAfterAuthentication() { mutableRecoveryKey.value = keys.exportForAuthenticatedReveal() }
    fun hideRecoveryKey() { mutableRecoveryKey.value = null }

    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) {
        val validationError = when {
            currentPassword.isEmpty() -> "Enter your current password"
            newPassword.length < 12 -> "New password must be at least 12 characters"
            newPassword == currentPassword -> "New password must be different from the current password"
            newPassword != confirmation -> "New passwords do not match"
            else -> null
        }
        if (validationError != null) {
            mutablePasswordChange.value = PasswordChangeUi(error = validationError)
            return
        }
        viewModelScope.launch {
            mutablePasswordChange.value = PasswordChangeUi(busy = true)
            val response = runCatching {
                api.changePassword(ChangePasswordRequest(currentPassword, newPassword))
            }.getOrElse {
                mutablePasswordChange.value = PasswordChangeUi(error = UserMessages.action(it, "Could not change password"))
                return@launch
            }
            mutablePasswordChange.value = when {
                response.isSuccessful -> PasswordChangeUi(changed = true)
                response.code() == 401 -> PasswordChangeUi(error = "Current password is incorrect")
                response.code() == 400 || response.code() == 422 -> PasswordChangeUi(error = "New password must be at least 12 characters and different from the current password")
                else -> PasswordChangeUi(error = "Could not change password (HTTP ${response.code()})")
            }
        }
    }

    fun clearPasswordChangeState() { mutablePasswordChange.value = PasswordChangeUi() }
    fun saveApiEndpoint(endpoint: String) = viewModelScope.launch {
        if (preferences.apiBaseUrl.first() == endpoint) return@launch
        preferences.setApiBaseUrl(endpoint)
        sessions.clearForEndpointChange()
    }
    fun logout() = viewModelScope.launch { sessions.logout() }
}
