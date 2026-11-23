package com.encrage.vault

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.encrage.vault.backup.BackupScheduler
import com.encrage.vault.ui.AppRoot
import com.encrage.vault.ui.AppViewModel
import com.encrage.vault.ui.BackupViewModel
import com.encrage.vault.ui.PhotosViewModel
import com.encrage.vault.ui.SettingsViewModel
import com.encrage.vault.ui.theme.EncrageTheme
import com.encrage.vault.data.local.MediaEntity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val appViewModel: AppViewModel by viewModels()
    private val photosViewModel: PhotosViewModel by viewModels()
    private val backupViewModel: BackupViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    @Inject lateinit var scheduler: BackupScheduler

    /** Observable so the UI updates when access is granted here or in system settings. */
    private var hasLibraryPermission by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLibraryPermission = scheduler.hasFullLibraryAccess()
        appViewModel.scanLibrary()
    }
    private var pendingDelete: List<MediaEntity> = emptyList()
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val items = pendingDelete
        pendingDelete = emptyList()
        if (result.resultCode == Activity.RESULT_OK) {
            photosViewModel.deleteSelected(items, localAlreadyDeleted = true)
        } else {
            photosViewModel.reportActionError("Deletion cancelled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hasLibraryPermission = scheduler.hasFullLibraryAccess()
        setContent {
            EncrageTheme {
                AppRoot(
                    appViewModel, photosViewModel, backupViewModel, settingsViewModel,
                    hasLibraryPermission = hasLibraryPermission,
                    onRequestLibraryPermission = ::requestLibraryPermission,
                    onAuthenticateRecoveryReveal = ::authenticateForRecoveryReveal,
                    onRecoveryVisibilityChanged = ::setSecureWindow,
                    onDeleteMedia = ::requestMediaDeletion,
                )
            }
        }
        appViewModel.scanLibrary()
    }

    override fun onResume() {
        super.onResume()
        val granted = scheduler.hasFullLibraryAccess()
        if (granted != hasLibraryPermission) {
            hasLibraryPermission = granted
            appViewModel.scanLibrary()
        }
    }

    private fun requestLibraryPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        ) else if (Build.VERSION.SDK_INT <= 28) arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissionLauncher.launch(permissions)
    }

    private fun requestMediaDeletion(items: List<MediaEntity>) {
        if (items.isEmpty()) return
        val localUris = items.mapNotNull { it.mediaStoreUri?.let(android.net.Uri::parse) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && localUris.isNotEmpty()) {
            runCatching {
                pendingDelete = items
                val request = MediaStore.createDeleteRequest(contentResolver, localUris)
                deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }.onFailure {
                pendingDelete = emptyList()
                photosViewModel.reportActionError(it.message ?: "Could not request deletion")
            }
        } else {
            photosViewModel.deleteSelected(items, localAlreadyDeleted = false)
        }
    }

    private fun authenticateForRecoveryReveal(onSuccess: () -> Unit) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_CANCELED
                    if (!cancelled) Toast.makeText(this@MainActivity, errString, Toast.LENGTH_LONG).show()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("View Recovery Key")
                .setSubtitle("Confirm it’s you before revealing the key")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private fun setSecureWindow(secure: Boolean) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
