package com.drivemp3.player.ui

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val UNKNOWN = "—"
private const val BYTES_PER_KB = 1024.0

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Drive sends `size` as a string because it is an int64. */
fun formatSize(size: String?): String {
    val bytes = size?.toLongOrNull() ?: return UNKNOWN
    val kb = bytes / BYTES_PER_KB
    return when {
        bytes < 1024 -> "$bytes B"
        kb < 1024 -> String.format(Locale.US, "%.0f KB", kb)
        else -> String.format(Locale.US, "%.1f MB", kb / BYTES_PER_KB)
    }
}

/**
 * Parses Drive's RFC 3339 timestamp into the device's local time.
 *
 * [OffsetDateTime.parse] rather than [java.time.Instant.parse] so both `...Z` and
 * `...+02:00` forms are accepted.
 */
fun formatCreatedTime(createdTime: String?): String {
    if (createdTime == null) return UNKNOWN
    return runCatching {
        OffsetDateTime.parse(createdTime)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DATE_FORMAT)
    }.getOrDefault(UNKNOWN)
}
