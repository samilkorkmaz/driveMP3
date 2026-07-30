package com.drivemp3.player.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val UNKNOWN = "—"
private const val UNKNOWN_DURATION = "--:--"
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

/**
 * Elapsed or total playback time as `m:ss`, or `h:mm:ss` once past an hour.
 *
 * Null renders as `--:--` rather than `0:00`: before ExoPlayer has read enough of the
 * MP3 to know the duration, a zero would read as a zero-length track.
 */
fun formatDuration(millis: Long?): String {
    if (millis == null || millis < 0L) return UNKNOWN_DURATION
    val totalSeconds = millis / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
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
