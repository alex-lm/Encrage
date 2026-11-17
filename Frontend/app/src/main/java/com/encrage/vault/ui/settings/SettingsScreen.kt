package com.encrage.vault.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.encrage.vault.BuildConfig
import com.encrage.vault.data.remote.ApiEndpoint
import com.encrage.vault.ui.PasswordChangeUi
import com.encrage.vault.ui.StorageUi

@Composable
fun SettingsScreen(
    username: String,
    storage: StorageUi,
    recoveryKey: String?,
    passwordChange: PasswordChangeUi,
    apiEndpoint: String,
    onAuthenticateReveal: () -> Unit,
    onHideRecoveryKey: () -> Unit,
    onChangePassword: (String, String, String) -> Unit,
    onClearPasswordChangeState: () -> Unit,
    onSaveApiEndpoint: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showApiEndpointDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(passwordChange.changed) {
        if (passwordChange.changed) {
            showPasswordDialog = false
            snackbar.showSnackbar("Password changed successfully")
            onClearPasswordChangeState()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Account")
            ListItem(headlineContent = { Text(username) }, supportingContent = { Text("Signed in") })
            ListItem(
                headlineContent = { Text("Change password") },
                supportingContent = { Text("Update your account password") },
                leadingContent = { androidx.compose.material3.Icon(Icons.Rounded.Lock, null) },
                modifier = Modifier.clickable {
                    onClearPasswordChangeState()
                    showPasswordDialog = true
                },
            )
            Section("Storage")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(if (storage.allocated > 0) "${format(storage.used)} of ${format(storage.allocated)} used" else "Storage unavailable")
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (storage.allocated > 0) (storage.used.toFloat() / storage.allocated).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ListItem(
                headlineContent = { Text("Download all media") },
                supportingContent = { Text("New-phone migration (coming in restore phase)") },
                leadingContent = { androidx.compose.material3.Icon(Icons.Rounded.CloudDownload, null) },
            )
            Section("Security & Encryption")
            ListItem(
                headlineContent = { Text("Recovery Key") },
                supportingContent = { Text("Requires biometric or device credential") },
                leadingContent = { androidx.compose.material3.Icon(Icons.Rounded.Key, null) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
            OutlinedButton(onAuthenticateReveal, modifier = Modifier.padding(horizontal = 16.dp)) { Text("View Recovery Key") }
            Section("Network")
            ListItem(
                headlineContent = { Text("API endpoint") },
                supportingContent = { Text(apiEndpoint) },
                modifier = Modifier.clickable { showApiEndpointDialog = true },
            )
            Section("About")
            ListItem(headlineContent = { Text("Encrage") }, supportingContent = { Text("Private encrypted photo and video cloud") })
            Button(
                onClick = onLogout,
                modifier = Modifier.padding(8.dp),
            ) {
                androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.Logout, null)
                Text("Log out", modifier = Modifier.padding(start = 8.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }

    if (recoveryKey != null) RecoveryKeyDialog(recoveryKey, onHideRecoveryKey)
    if (showApiEndpointDialog) {
        ApiEndpointDialog(
            currentEndpoint = apiEndpoint,
            willSignOut = true,
            onSave = onSaveApiEndpoint,
            onDismiss = { showApiEndpointDialog = false },
        )
    }
    if (showPasswordDialog) {
        ChangePasswordDialog(
            state = passwordChange,
            onSubmit = onChangePassword,
            onEdit = { if (passwordChange.error != null) onClearPasswordChangeState() },
            onDismiss = {
                if (!passwordChange.busy) {
                    showPasswordDialog = false
                    onClearPasswordChangeState()
                }
            },
        )
    }
}

@Composable
fun ApiEndpointDialog(
    currentEndpoint: String,
    willSignOut: Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var endpoint by remember(currentEndpoint) { mutableStateOf(currentEndpoint) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("API endpoint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter an IP address with an optional port, or a full API base URL.")
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it; error = null },
                    label = { Text("IP, port, or URL") },
                    placeholder = { Text("192.0.2.10:8000") },
                    supportingText = {
                        Text(error ?: if (BuildConfig.DEBUG) "HTTP and HTTPS are supported" else "HTTPS is required")
                    },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (willSignOut) Text("Saving a different endpoint will sign you out of this server.")
            }
        },
        confirmButton = {
            Button(onClick = {
                ApiEndpoint.normalize(endpoint)
                    .onSuccess { normalized -> onSave(normalized); onDismiss() }
                    .onFailure { error = it.message ?: "Enter a valid API address" }
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (currentEndpoint != BuildConfig.API_BASE_URL) {
                    TextButton(onClick = {
                        onSave(BuildConfig.API_BASE_URL)
                        onDismiss()
                    }) { Text("Use default") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ChangePasswordDialog(
    state: PasswordChangeUi,
    onSubmit: (String, String, String) -> Unit,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val localError = if (!attempted) null else when {
        currentPassword.isEmpty() -> "Enter your current password"
        newPassword.length < 12 -> "New password must be at least 12 characters"
        newPassword == currentPassword -> "New password must be different from the current password"
        newPassword != confirmation -> "New passwords do not match"
        else -> null
    }
    val error = state.error ?: localError

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Changing your password keeps this device signed in and signs out your other sessions.")
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it; onEdit() },
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; onEdit() },
                    label = { Text("New password") },
                    supportingText = { Text("At least 12 characters") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it; onEdit() },
                    label = { Text("Confirm new password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.busy,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                enabled = !state.busy,
                onClick = {
                    attempted = true
                    if (localError == null) onSubmit(currentPassword, newPassword, confirmation)
                },
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Change password")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancel") } },
    )
}

@Composable
private fun RecoveryKeyDialog(key: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recovery Key") },
        text = {
            Column {
                Text("Anyone with this Recovery Key can decrypt your backed-up media.", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text(key, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Text("If you lose this key and no trusted device has a copy, your encrypted media cannot be recovered.")
            }
        },
        confirmButton = { Button(onDismiss) { Text("Done") } },
        dismissButton = {
            OutlinedButton(onClick = {
                val clip = ClipData.newPlainText("Recovery Key", key)
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            }) { Text("Copy Recovery Key") }
        },
    )
}

@Composable
private fun Section(label: String) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp))
    HorizontalDivider()
}

private fun format(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1_000_000_000.0) else "%.1f MB".format(bytes / 1_000_000.0)
