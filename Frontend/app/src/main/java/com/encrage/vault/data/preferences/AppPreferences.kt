package com.encrage.vault.data.preferences

import android.content.Context
import com.encrage.vault.BuildConfig
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("encrage_preferences")

data class BackupPreferences(
    val automaticBackup: Boolean = true,
    val backupPhotos: Boolean = true,
    val backupVideos: Boolean = true,
    val useMobileData: Boolean = false,
    val useRoaming: Boolean = false,
    val onlyWhileCharging: Boolean = false,
    val backgroundBackup: Boolean = true,
)

@Singleton
class AppPreferences @Inject constructor(@ApplicationContext private val context: Context) {
    val username: Flow<String?> = context.dataStore.data.map { it[USERNAME] }
    val apiBaseUrl: Flow<String> = context.dataStore.data.map { it[API_BASE_URL] ?: BuildConfig.API_BASE_URL }
    val backup: Flow<BackupPreferences> = context.dataStore.data.map { p ->
        BackupPreferences(
            automaticBackup = p[AUTO_BACKUP] ?: true,
            backupPhotos = p[PHOTOS] ?: true,
            backupVideos = p[VIDEOS] ?: true,
            useMobileData = p[MOBILE] ?: false,
            useRoaming = p[ROAMING] ?: false,
            onlyWhileCharging = p[CHARGING] ?: false,
            backgroundBackup = p[BACKGROUND] ?: true,
        )
    }

    suspend fun setUsername(value: String?) = context.dataStore.edit { p ->
        if (value == null) p.remove(USERNAME) else p[USERNAME] = value
    }

    suspend fun setApiBaseUrl(value: String) = context.dataStore.edit { it[API_BASE_URL] = value }

    val galleryColumns: Flow<Int> = context.dataStore.data.map {
        (it[GALLERY_COLUMNS] ?: DEFAULT_COLUMNS).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    suspend fun setGalleryColumns(value: Int) = context.dataStore.edit {
        it[GALLERY_COLUMNS] = value.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    suspend fun updateBackup(transform: (BackupPreferences) -> BackupPreferences) {
        context.dataStore.edit { p ->
            val next = transform(
                BackupPreferences(
                    p[AUTO_BACKUP] ?: true, p[PHOTOS] ?: true, p[VIDEOS] ?: true,
                    p[MOBILE] ?: false, p[ROAMING] ?: false, p[CHARGING] ?: false,
                    p[BACKGROUND] ?: true,
                ),
            )
            p[AUTO_BACKUP] = next.automaticBackup
            p[PHOTOS] = next.backupPhotos
            p[VIDEOS] = next.backupVideos
            p[MOBILE] = next.useMobileData
            p[ROAMING] = next.useRoaming
            p[CHARGING] = next.onlyWhileCharging
            p[BACKGROUND] = next.backgroundBackup
        }
    }

    companion object {
        const val DEFAULT_COLUMNS = 4
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 6
        private val GALLERY_COLUMNS = intPreferencesKey("gallery_columns")
        private val USERNAME = stringPreferencesKey("username")
        private val API_BASE_URL = stringPreferencesKey("api_base_url")
        private val AUTO_BACKUP = booleanPreferencesKey("automatic_backup")
        private val PHOTOS = booleanPreferencesKey("backup_photos")
        private val VIDEOS = booleanPreferencesKey("backup_videos")
        private val MOBILE = booleanPreferencesKey("use_mobile_data")
        private val ROAMING = booleanPreferencesKey("use_roaming")
        private val CHARGING = booleanPreferencesKey("only_while_charging")
        private val BACKGROUND = booleanPreferencesKey("background_backup")
    }
}
