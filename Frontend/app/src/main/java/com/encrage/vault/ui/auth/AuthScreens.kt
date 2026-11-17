package com.encrage.vault.ui.auth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.encrage.vault.ui.AuthUiState
import com.encrage.vault.ui.settings.ApiEndpointDialog

@Composable
fun LoginScreen(
    state: AuthUiState,
    apiEndpoint: String,
    onLogin: (String, String) -> Unit,
    onSaveApiEndpoint: (String) -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showApiEndpointDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Your private library", style = MaterialTheme.typography.headlineMedium)
        Text("Encrypted before it leaves this phone", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onLogin(username, password) },
            enabled = !state.busy && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { if (state.busy) CircularProgressIndicator() else Text("Sign in") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { showApiEndpointDialog = true },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("API: $apiEndpoint", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    var importMode by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
        Text("Protect your memories", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(10.dp))
        Text(
            "Your Recovery Key encrypts every original, thumbnail and private metadata record. It never goes to the server.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        if (generatedKey != null) {
            Text("Save this Recovery Key now", style = MaterialTheme.typography.titleMedium)
            Text(generatedKey, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 16.dp))
            Text("If you lose it and no trusted device has a copy, your encrypted media cannot be recovered.", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(20.dp))
            OutlinedButton({ copySensitive(context, generatedKey) }, modifier = Modifier.fillMaxWidth()) { Text("Copy Recovery Key") }
            Button(onAcknowledgeGenerated, modifier = Modifier.fillMaxWidth()) { Text("I’ve saved my Recovery Key") }
        } else if (importMode) {
            OutlinedTextField(key, { key = it }, label = { Text("Recovery Key") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
            Button({ onImport(key) }, enabled = key.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Use Recovery Key") }
            OutlinedButton({ importMode = false }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        } else {
            Button(onGenerate, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Generate new Recovery Key") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton({ importMode = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Enter existing Recovery Key") }
        }
    }
}

private fun copySensitive(context: Context, value: String) {
    val clip = ClipData.newPlainText("Recovery Key", value)
    clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean("android.content.extra.IS_SENSITIVE", true)
    }
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
}
