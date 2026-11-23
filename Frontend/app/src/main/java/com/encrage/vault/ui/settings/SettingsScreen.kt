package com.encrage.vault.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.encrage.vault.BuildConfig
import com.encrage.vault.data.remote.ApiEndpoint
import com.encrage.vault.ui.PasswordChangeUi
import com.encrage.vault.ui.StorageUi
import com.encrage.vault.ui.formatBytes
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    username: String,
    storage: StorageUi,
    recoveryKey: String?,
    passwordChange: PasswordChangeUi,
    apiEndpoint: String,
    onRetryStorage: () -> Unit,
    onAuthenticateReveal: () -> Unit,
    onHideRecoveryKey: () -> Unit,
    onChangePassword: (String, String, String) -> Unit,
    onClearPasswordChangeState: () -> Unit,
    onSaveApiEndpoint: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showApiEndpointDialog by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(passwordChange.changed) {
        if (passwordChange.changed) {
            showPasswordDialog = false
            snackbar.showSnackbar("Password changed. Other devices were signed out.")
            onClearPasswordChangeState()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            username.take(1).uppercase(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(username, style = MaterialTheme.typography.titleMedium)
                        Text(
                            displayHost(apiEndpoint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                SettingsRow(Icons.Rounded.Lock, "Change password", "Signs out your other devices") {
                    onClearPasswordChangeState()
                    showPasswordDialog = true
                }
            }

            SectionLabel("Cloud storage")
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    when {
                        storage.allocated > 0 -> {
                            val fraction = (storage.used.toFloat() / storage.allocated).coerceIn(0f, 1f)
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(formatBytes(storage.used), style = MaterialTheme.typography.headlineSmall)
                                Text(
                                    " of ${formatBytes(storage.allocated)} used",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 3.dp),
                                )
                            }
                            LinearProgressIndicator(
                                progress = { fraction },
                                color = if (fraction > .9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            )
                            Text(
                                "${formatBytes((storage.allocated - storage.used).coerceAtLeast(0))} free",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        storage.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Checking storage…", modifier = Modifier.padding(start = 12.dp))
                        }
                        else -> {
                            Text(storage.error ?: "Storage usage isn’t available right now.", style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = onRetryStorage, modifier = Modifier.padding(top = 10.dp)) { Text("Try again") }
                        }
                    }
                }
            }

            SectionLabel("Security")
            SettingsCard {
                SettingsRow(Icons.Rounded.Key, "Recovery Key", "View after confirming it’s you", onClick = onAuthenticateReveal)
            }

            SectionLabel("Server")
            SettingsCard {
                SettingsRow(Icons.Rounded.Dns, "Server address", apiEndpoint) { showApiEndpointDialog = true }
            }

            SettingsCard {
                SettingsRow(Icons.Rounded.Info, "Encrage ${BuildConfig.VERSION_NAME}", "Private, end-to-end encrypted photo and video backup", onClick = null)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                SettingsRow(
                    Icons.AutoMirrored.Rounded.Logout, "Log out", null,
                    tint = MaterialTheme.colorScheme.error,
                ) { confirmLogout = true }
            }
            Spacer(Modifier.height(12.dp))
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
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            icon = { Icon(Icons.AutoMirrored.Rounded.Logout, null) },
            title = { Text("Log out of Encrage?") },
            text = {
                Text(
                    "Backup stops until you sign in again. Your photos stay on this phone and your Recovery Key stays on this device.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmLogout = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
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

private fun displayHost(endpoint: String): String =
    endpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    tint: Color = Color.Unspecified,
    onClick: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(title, color = if (tint == Color.Unspecified) Color.Unspecified else tint) },
        supportingContent = subtitle?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(icon, null, tint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint) },
        trailingContent = if (onClick != null && tint == Color.Unspecified) {
            { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
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
    fun save() {
        ApiEndpoint.normalize(endpoint)
            .onSuccess { normalized -> onSave(normalized); onDismiss() }
            .onFailure { error = it.message ?: "Enter a valid server address" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Dns, null) },
        title = { Text("Server address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter your Encrage server’s IP address and port, or its full URL.")
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it; error = null },
                    label = { Text("IP, port, or URL") },
                    placeholder = { Text("192.168.1.20:8000") },
                    supportingText = {
                        Text(error ?: if (BuildConfig.DEBUG) "HTTP and HTTPS are supported" else "HTTPS is required")
                    },
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (willSignOut) {
                    Text(
                        "Changing the server signs you out of the current one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = ::save) { Text("Save") } },
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
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    supporting: String? = null,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    if (visible) "Hide password" else "Show password",
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        enabled = enabled,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
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
    fun submit() {
        attempted = true
        val valid = currentPassword.isNotEmpty() && newPassword.length >= 12 &&
            newPassword != currentPassword && newPassword == confirmation
        if (valid) onSubmit(currentPassword, newPassword, confirmation)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "This device stays signed in. Your other devices will be signed out.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PasswordField(currentPassword, { currentPassword = it; onEdit() }, "Current password", !state.busy)
                PasswordField(
                    newPassword, { newPassword = it; onEdit() }, "New password", !state.busy,
                    supporting = "At least 12 characters (${newPassword.length}/12)".takeIf { newPassword.length < 12 } ?: "Looks good",
                )
                PasswordField(
                    confirmation, { confirmation = it; onEdit() }, "Confirm new password", !state.busy,
                    imeAction = ImeAction.Done, onDone = ::submit,
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = !state.busy, onClick = ::submit) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Text("Change password")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Cancel") } },
    )
}

@Composable
private fun RecoveryKeyDialog(key: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2500)
            copied = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text("Your Recovery Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Anyone with this key can decrypt your backups. Store it somewhere private, like a password manager.")
                RecoveryKeyBox(key)
                Text(
                    "If you lose it and no other device has it, your encrypted backups can’t be recovered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = { Button(onDismiss) { Text("Done") } },
        dismissButton = {
            OutlinedButton(onClick = {
                copySensitive(context, key)
                copied = true
            }) {
                Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                Text(if (copied) "Copied" else "Copy", modifier = Modifier.padding(start = 8.dp))
            }
        },
    )
}

/** Monospaced, selectable key display shared with the setup screen. */
@Composable
fun RecoveryKeyBox(key: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        SelectionContainer {
            Text(
                key.chunked(4).joinToString(" "),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

/** Copies a secret with the platform's sensitive-content flag so clipboard previews hide it. */
fun copySensitive(context: Context, value: String) {
    val clip = ClipData.newPlainText("Recovery Key", value)
    clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
