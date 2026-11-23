package com.encrage.vault.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000_000 -> "%.2f TB".format(bytes / 1_000_000_000_000.0)
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

internal fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

internal fun formatDurationMs(ms: Long): String = formatDuration((ms.coerceAtLeast(0) + 500) / 1000)

private val dayOfWeek = DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())
private val sameYear = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
private val otherYear = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val timeOfDay = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val longDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** "Today", "Yesterday", "Monday", "Sat, Nov 21" or "Sat, Nov 21, 2024". */
internal fun friendlyDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    date == today -> "Today"
    date == today.minusDays(1) -> "Yesterday"
    date.isAfter(today.minusDays(7)) && !date.isAfter(today) -> date.format(dayOfWeek)
    date.year == today.year -> date.format(sameYear)
    else -> date.format(otherYear)
}

internal fun monthLabel(date: LocalDate): String = date.format(monthYear)

internal fun captureDateTime(epochMs: Long): Pair<String, String> {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return zoned.toLocalDate().format(longDate) to zoned.toLocalTime().format(timeOfDay)
}
