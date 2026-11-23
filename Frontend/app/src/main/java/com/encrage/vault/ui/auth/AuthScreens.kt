package com.encrage.vault.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.encrage.vault.ui.AuthUiState
import com.encrage.vault.ui.settings.ApiEndpointDialog
import com.encrage.vault.ui.settings.RecoveryKeyBox
import com.encrage.vault.ui.settings.copySensitive
import kotlinx.coroutines.delay

@Composable
fun LoginScreen(
    state: AuthUiState,
    apiEndpoint: String,
    onLogin: (String, String) -> Unit,
    onEdit: () -> Unit,
    onSaveApiEndpoint: (String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showApiEndpointDialog by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val canSubmit = !state.busy && username.isNotBlank() && password.isNotEmpty()
    fun submit() {
        if (canSubmit) {
            focus.clearFocus()
            onLogin(username, password)
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("Welcome to Encrage", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                "Your photos are encrypted on this phone before they’re backed up.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it; onEdit() },
                label = { Text("Username") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; onEdit() },
                label = { Text("Password") },
                singleLine = true,
                enabled = !state.busy,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                isError = state.error != null,
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password },
            )
            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = ::submit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Sign in")
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { showApiEndpointDialog = true }, enabled = !state.busy) {
                Icon(Icons.Rounded.Dns, null, Modifier.size(18.dp))
                Text(
                    "Server: ${apiEndpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    if (showApiEndpointDialog) {
        ApiEndpointDialog(
            currentEndpoint = apiEndpoint,
            willSignOut = false,
            onSave = onSaveApiEndpoint,
            onDismiss = { showApiEndpointDialog = false },
        )
    }
}

@Composable
fun EncryptionSetupScreen(
    error: String?,
    generatedKey: String?,
    onGenerate: () -> Unit,
    onImport: (String) -> Unit,
    onAcknowledgeGenerated: () -> Unit,
) {
    val context = LocalContext.current
    var importMode by rememberSaveable { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2500)
            copied = false
        }
    }

    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            Spacer(Modifier.height(18.dp))
            Text(
                if (generatedKey != null) "Save your Recovery Key" else "Protect your memories",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (generatedKey != null) {
                    "This key is the only way to decrypt your backups on a new phone. It never leaves your devices — not even your server sees it."
                } else {
                    "Encrage encrypts every photo and video with a Recovery Key that only your devices know."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            when {
                generatedKey != null -> {
                    RecoveryKeyBox(generatedKey)
                    OutlinedButton(
                        onClick = {
                            copySensitive(context, generatedKey)
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                        Text(if (copied) "Copied" else "Copy to a password manager", modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(
                        "If you lose this key and no other device has it, your backups can’t be recovered.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .toggleable(value = saved, role = Role.Checkbox, onValueChange = { saved = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = saved, onCheckedChange = null)
                        Text("I’ve stored my Recovery Key somewhere safe", modifier = Modifier.padding(start = 12.dp))
                    }
                    Button(
                        onClick = onAcknowledgeGenerated,
                        enabled = saved,
                        modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                    ) { Text("Continue") }
                }
                importMode -> {
                    OutlinedTextField(
                        key, { key = it },
                        label = { Text("Recovery Key") },
                        minLines = 2,
                        isError = error != null,
                        supportingText = { Text(error ?: "Paste the key from your password manager or other device") },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (key.isNotBlank()) onImport(key) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button({ onImport(key) }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Use this Recovery Key") }
                    TextButton({ importMode = false }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("Back") }
                }
                else -> {
                    Button(onGenerate, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("I’m new — create a Recovery Key") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton({ importMode = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("I already have a Recovery Key") }
                    Text(
                        "Use your existing key if you’ve backed up from another phone before.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}
