package com.drivemp3.player.model

/**
 * Where playback left off, persisted so a cold start can pick it back up.
 *
 * Carries [trackName] alongside [trackId] because the restored track has to show in the
 * now-playing bar and notification before it is prepared — and the name is not derivable
 * from the id without a Drive round trip the resume path deliberately avoids.
 */
data class ResumeState(
    val trackId: String,
    val trackName: String,
    val positionMs: Long,
)
