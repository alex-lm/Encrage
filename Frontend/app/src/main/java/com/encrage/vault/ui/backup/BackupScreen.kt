package com.encrage.vault.ui.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.encrage.vault.data.local.BackupCounts
import com.encrage.vault.data.preferences.BackupPreferences

@Composable
fun BackupScreen(
    counts: BackupCounts,
    settings: BackupPreferences,
    onUpdate: ((BackupPreferences) -> BackupPreferences) -> Unit,
    onBackupNow: () -> Unit,
) {
    val pendingItems = counts.waiting + counts.active
    val uploadedBytes = (counts.totalLocalBytes - counts.bytesRemaining).coerceAtLeast(0)
    val progress = if (counts.totalLocalBytes > 0) {
        (uploadedBytes.toFloat() / counts.totalLocalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
    ) {
        Icon(
            if (pendingItems == 0 && counts.failed == 0) Icons.Rounded.CloudDone else Icons.Rounded.CloudUpload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (pendingItems == 0 && counts.failed == 0) "Backup complete" else "Backing up",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (counts.totalLocalBytes > 0) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            Text(
                "${formatBytes(uploadedBytes)} of ${formatBytes(counts.totalLocalBytes)} backed up (${(progress * 100).toInt()}%)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        when {
            pendingItems > 0 -> Text(
                "$pendingItems ${if (pendingItems == 1) "item" else "items"} remaining",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            counts.failed > 0 -> Text(
                "${counts.failed} ${if (counts.failed == 1) "item needs" else "items need"} retry",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (counts.failed > 0) {
            Text(
                "${counts.failed} ${if (counts.failed == 1) "item couldn’t" else "items couldn’t"} be backed up",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (pendingItems + counts.failed > 0) {
            Button(onClick = onBackupNow, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (counts.failed > 0) "Retry now" else "Back up now")
            }
            if (!settings.useMobileData) {
                Text(
                    "Automatic uploads wait for Wi-Fi unless “Use mobile data” is on. “Back up now” uses any network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Text(
            "Backup settings",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 30.dp, bottom = 8.dp),
        )
        Setting("Automatic backup", settings.automaticBackup) { onUpdate { p -> p.copy(automaticBackup = it) } }
        Setting("Back up photos", settings.backupPhotos) { onUpdate { p -> p.copy(backupPhotos = it) } }
        Setting("Back up videos", settings.backupVideos) { onUpdate { p -> p.copy(backupVideos = it) } }
        Setting("Use mobile data", settings.useMobileData) { onUpdate { p -> p.copy(useMobileData = it) } }
        Setting("Use data while roaming", settings.useRoaming) { onUpdate { p -> p.copy(useRoaming = it) } }
        Setting("Only while charging", settings.onlyWhileCharging) { onUpdate { p -> p.copy(onlyWhileCharging = it) } }
        Setting("Background backup", settings.backgroundBackup) { onUpdate { p -> p.copy(backgroundBackup = it) } }
    }
}

@Composable
private fun Setting(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked, onChange)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    else -> "$bytes B"
}
