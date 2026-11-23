package com.encrage.vault.ui.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.encrage.vault.data.preferences.BackupPreferences
import com.encrage.vault.ui.BackupUi
import com.encrage.vault.ui.formatBytes
import com.encrage.vault.ui.plural
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private data class BackupStatusView(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val emphasis: Boolean = false,
    val action: String? = null,
)

@Composable
fun BackupScreen(
    ui: BackupUi,
    apiEndpoint: String,
    hasLibraryPermission: Boolean,
    onRequestPermission: () -> Unit,
    onUpdate: ((BackupPreferences) -> BackupPreferences) -> Unit,
    onBackupNow: () -> Unit,
) {
    val counts = ui.counts
    val settings = ui.settings
    val pending = counts.waiting + counts.active
    val uploadedBytes = (counts.totalLocalBytes - counts.bytesRemaining).coerceAtLeast(0)
    val progress = if (counts.totalLocalBytes > 0) (uploadedBytes.toFloat() / counts.totalLocalBytes).coerceIn(0f, 1f) else 1f
    val status = statusView(ui, pending, apiEndpoint)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (status.emphasis) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
                contentColor = if (status.emphasis) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(
                        if (status.emphasis) MaterialTheme.colorScheme.error.copy(alpha = .15f) else MaterialTheme.colorScheme.primaryContainer,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        status.icon, null,
                        tint = if (status.emphasis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(status.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 14.dp))
                Text(
                    status.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.emphasis) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (counts.totalLocalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    )
                    Text(
                        "${formatBytes(uploadedBytes)} of ${formatBytes(counts.totalLocalBytes)} on this phone is backed up (${(progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.emphasis) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                status.action?.let { label ->
                    Button(onClick = onBackupNow, modifier = Modifier.padding(top = 16.dp)) { Text(label) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Backed up", counts.backedUp, Modifier.weight(1f))
            Stat("Waiting", pending, Modifier.weight(1f))
            Stat("Failed", counts.failed, Modifier.weight(1f), highlight = counts.failed > 0)
        }

        if (counts.failed > 0 && ui.failureReasons.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Why items failed", style = MaterialTheme.typography.titleSmall)
                    ui.failureReasons.forEach { reason ->
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                            Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }

        if (!hasLibraryPermission) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhotoLibrary, null)
                        Text("Limited photo access", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 10.dp))
                    }
                    Text(
                        "Only the photos you picked can be backed up. Allow full access to back up everything automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    OutlinedButton(onClick = onRequestPermission, modifier = Modifier.padding(top = 10.dp)) { Text("Allow full access") }
                }
            }
        }

        Text("Backup settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            SettingRow("Automatic backup", "Back up new photos and videos as they appear", settings.automaticBackup) {
                onUpdate { p -> p.copy(automaticBackup = it) }
            }
            SettingRow("Keep running in the background", "Continue backing up while Encrage is closed", settings.backgroundBackup, enabled = settings.automaticBackup) {
                onUpdate { p -> p.copy(backgroundBackup = it) }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("Back up photos", null, settings.backupPhotos) { onUpdate { p -> p.copy(backupPhotos = it) } }
            SettingRow("Back up videos", null, settings.backupVideos) { onUpdate { p -> p.copy(backupVideos = it) } }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            SettingRow("Use mobile data", "Otherwise automatic backup waits for Wi‑Fi", settings.useMobileData) {
                onUpdate { p -> p.copy(useMobileData = it) }
            }
            SettingRow("Use data while roaming", "Only applies when mobile data is on", settings.useRoaming, enabled = settings.useMobileData) {
                onUpdate { p -> p.copy(useRoaming = it) }
            }
            SettingRow("Only while charging", "Save battery on large backups", settings.onlyWhileCharging) {
                onUpdate { p -> p.copy(onlyWhileCharging = it) }
            }
        }
        Text(
            "“Back up now” ignores the Wi‑Fi and charging limits for one run.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }
}

private fun statusView(ui: BackupUi, pending: Int, apiEndpoint: String): BackupStatusView {
    val counts = ui.counts
    val settings = ui.settings
    return when {
        ui.serverUnreachableSince != null -> BackupStatusView(
            Icons.Rounded.CloudOff,
            "Can’t reach your server",
            "Encrage couldn’t connect to ${displayHost(apiEndpoint)} since ${clock(ui.serverUnreachableSince)}. " +
                "Check that the server is running and on the same network. Backup resumes automatically.",
            emphasis = true,
            action = "Try again",
        )
        counts.active > 0 -> BackupStatusView(
            Icons.Rounded.CloudUpload,
            "Backing up…",
            "${plural(pending, "item")} left. You can keep using your phone.",
        )
        !settings.automaticBackup && (pending > 0 || counts.failed > 0) -> BackupStatusView(
            Icons.Rounded.PauseCircle,
            "Automatic backup is off",
            "${plural(pending + counts.failed, "item")} on this phone aren’t backed up.",
            action = "Back up now",
        )
        pending > 0 -> BackupStatusView(
            Icons.Rounded.Schedule,
            "${plural(pending, "item")} waiting",
            when {
                settings.onlyWhileCharging && !settings.useMobileData -> "Backup starts when the phone is charging and on Wi‑Fi."
                settings.onlyWhileCharging -> "Backup starts when the phone is charging."
                !settings.useMobileData -> "Backup starts when you’re on Wi‑Fi."
                else -> "Backup will start shortly."
            },
            action = "Back up now",
        )
        counts.failed > 0 -> BackupStatusView(
            Icons.Rounded.ErrorOutline,
            "${plural(counts.failed, "item")} couldn’t be backed up",
            "Everything else is safe. Retrying often fixes temporary problems.",
            emphasis = true,
            action = "Retry",
        )
        counts.totalLocalBytes == 0L -> BackupStatusView(
            Icons.Rounded.CloudDone,
            "Nothing to back up yet",
            "New photos and videos on this phone will be backed up automatically.",
        )
        else -> BackupStatusView(
            Icons.Rounded.CloudDone,
            "Everything is backed up",
            "${plural(counts.backedUp, "item")} are encrypted and stored on your server.",
        )
    }
}

private fun displayHost(endpoint: String): String =
    endpoint.removePrefix("https://").removePrefix("http://").trimEnd('/')

private fun clock(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier, highlight: Boolean = false) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                "%,d".format(value),
                style = MaterialTheme.typography.titleLarge,
                color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}
