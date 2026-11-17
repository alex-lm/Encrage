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
import com.encrage.vault.data.remote.SessionRepository
import com.encrage.vault.data.remote.SessionState
import com.encrage.vault.data.repository.MediaRepository
import com.encrage.vault.media.MediaStoreScanner
import com.encrage.vault.media.CloudThumbnailLoader
import com.encrage.vault.media.CloudOriginalMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
            AuthUiState(error = t.message ?: "Sign in failed")
        }
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
        runCatching { masterKeys.import(value) }
            .onSuccess {
                mutableHasMasterKey.value = true
                mutableAuthUi.value = AuthUiState()
                viewModelScope.launch { scheduler.requestBackupWithStoredPreferences(force = true) }
            }
            .onFailure { mutableAuthUi.value = AuthUiState(error = "That Recovery Key is not valid") }
    }

    fun scanLibrary() = viewModelScope.launch {
        scanner.scan(scheduler.hasFullLibraryAccess())
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

@HiltViewModel
class PhotosViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val cloudThumbnails: CloudThumbnailLoader,
    private val cloudOriginals: CloudOriginalMedia,
) : ViewModel() {
    private val mutableFilters = MutableStateFlow(PhotosFilters())
    private val cloudThumbnailCache = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    val filters = mutableFilters.asStateFlow()
    private val mutableActionUi = MutableStateFlow(MediaActionUi())
    val actionUi = mutableActionUi.asStateFlow()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val gallery = mutableFilters.flatMapLatest { filters ->
        repository.gallery(filters.media.mediaType, filters.location.localState)
    }.cachedIn(viewModelScope)

    fun selectMediaFilter(filter: MediaFilter) {
        mutableFilters.value = mutableFilters.value.copy(media = filter)
    }

    fun selectLocationFilter(filter: BackupLocationFilter) {
        mutableFilters.value = mutableFilters.value.copy(location = filter)
    }

    suspend fun cloudThumbnail(mediaId: String): Bitmap? {
        cloudThumbnailCache.get(mediaId)?.let { return it }
        return runCatching { cloudThumbnails.load(mediaId) }.getOrNull()?.also {
            cloudThumbnailCache.put(mediaId, it)
        }
    }

    suspend fun cloudPhoto(item: MediaEntity): Bitmap? =
        runCatching { cloudOriginals.loadPhoto(item) }.getOrNull()

    fun cloudVideoDataSource(item: MediaEntity): DataSource.Factory =
        cloudOriginals.videoDataSourceFactory(item)

    fun downloadSelected(items: List<MediaEntity>) {
        if (items.isEmpty() || items.any { it.localState != LocalState.CLOUD_ONLY || it.mediaId == null }) {
            mutableActionUi.value = MediaActionUi(error = "Downloads are available only when every selected item is cloud-only")
            return
        }
        viewModelScope.launch {
            mutableActionUi.value = MediaActionUi(busy = true)
            val result = runCatching { repository.downloadCloudOnly(items) }.getOrElse {
                mutableActionUi.value = MediaActionUi(error = it.message ?: "Download failed")
                return@launch
            }
            mutableActionUi.value = if (result.failures.isEmpty()) {
                MediaActionUi(message = "Downloaded ${result.downloaded} ${if (result.downloaded == 1) "item" else "items"}")
            } else {
                MediaActionUi(error = "Downloaded ${result.downloaded}; ${result.failures.size} failed")
            }
        }
    }

    fun deleteSelected(items: List<MediaEntity>, localAlreadyDeleted: Boolean) = viewModelScope.launch {
        mutableActionUi.value = MediaActionUi(busy = true)
        val result = runCatching { repository.deleteMedia(items, localAlreadyDeleted) }.getOrElse {
            mutableActionUi.value = MediaActionUi(error = it.message ?: "Deletion failed")
            return@launch
        }
        mutableActionUi.value = if (result.failures.isEmpty()) {
            MediaActionUi(message = "Deleted ${result.deleted} ${if (result.deleted == 1) "item" else "items"}")
        } else {
            MediaActionUi(error = "Deleted ${result.deleted}; ${result.failures.size} failed")
        }
    }

    fun reportActionError(message: String) { mutableActionUi.value = MediaActionUi(error = message) }
    fun clearActionMessage() { mutableActionUi.value = mutableActionUi.value.copy(message = null, error = null) }

    fun clearCloudThumbnailCache() = cloudThumbnailCache.evictAll()

    fun syncCloudMetadata() = viewModelScope.launch { runCatching { repository.syncNextCloudPage(reset = true) } }

    override fun onCleared() {
        clearCloudThumbnailCache()
        super.onCleared()
    }

    private companion object {
        const val THUMBNAIL_CACHE_BYTES = 24 * 1024 * 1024
    }
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    repository: MediaRepository,
    private val preferences: AppPreferences,
    private val scheduler: BackupScheduler,
) : ViewModel() {
    val counts = repository.backupCounts().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupCounts(0, 0, 0, 0, 0, 0),
    )
    val settings = preferences.backup.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupPreferences(),
    )

    fun update(transform: (BackupPreferences) -> BackupPreferences) = viewModelScope.launch {
        preferences.updateBackup(transform)
        val next = transform(settings.value)
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

data class StorageUi(val used: Long = 0, val allocated: Long = 0, val loading: Boolean = false)
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
) : ViewModel() {
    private val mutableStorage = MutableStateFlow(StorageUi())
    val storage: StateFlow<StorageUi> = mutableStorage.asStateFlow()
    private val mutableRecoveryKey = MutableStateFlow<String?>(null)
    val recoveryKey = mutableRecoveryKey.asStateFlow()
    private val mutablePasswordChange = MutableStateFlow(PasswordChangeUi())
    val passwordChange = mutablePasswordChange.asStateFlow()
    val apiEndpoint = preferences.apiBaseUrl.stateIn(
        viewModelScope, SharingStarted.Eagerly, com.encrage.vault.BuildConfig.API_BASE_URL,
    )

    fun loadStorage() = viewModelScope.launch {
        mutableStorage.value = mutableStorage.value.copy(loading = true)
        runCatching { api.storage() }.onSuccess {
            mutableStorage.value = StorageUi(it.usedBytes, it.allocatedBytes)
        }.onFailure { mutableStorage.value = mutableStorage.value.copy(loading = false) }
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
                mutablePasswordChange.value = PasswordChangeUi(error = it.message ?: "Could not change password")
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
