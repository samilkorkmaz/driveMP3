package com.drivemp3.player.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val UNKNOWN = "—"
private const val BYTES_PER_KB = 1024.0

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Drive sends `size` as a string because it is an int64; the index stores it parsed. */
fun formatSize(sizeBytes: Long?): String {
    val bytes = sizeBytes ?: return UNKNOWN
    val kb = bytes / BYTES_PER_KB
    return when {
        bytes < 1024 -> "$bytes B"
        kb < 1024 -> String.format(Locale.US, "%.0f KB", kb)
        else -> String.format(Locale.US, "%.1f MB", kb / BYTES_PER_KB)
    }
}

/** Drive's `createdTime`, normalised to epoch millis at index time, in device-local time. */
fun formatCreatedTime(epochMillis: Long?): String {
    if (epochMillis == null) return UNKNOWN
    return runCatching {
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DATE_FORMAT)
    }.getOrDefault(UNKNOWN)
}
