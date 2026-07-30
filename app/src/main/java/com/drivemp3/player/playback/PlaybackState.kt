package com.drivemp3.player.playback

/**
 * What the now-playing bar renders. Empty ([trackId] null) means nothing has been
 * played this session and the bar stays hidden.
 */
data class PlaybackState(
    /** Drive file id of the loaded track. */
    val trackId: String? = null,

    /** Raw Drive file name — the only title there is, per spec section 2.1. */
    val trackName: String? = null,

    val isPlaying: Boolean = false,

    /** Buffering, or waiting on a token refresh; both mean "no audio right now". */
    val isBuffering: Boolean = false,

    val positionMs: Long = 0L,

    /**
     * Null until ExoPlayer has read enough of the MP3 to know it.
     *
     * Duration is not a Drive field, so it cannot come from the index — for a CBR
     * file without a Xing header it is an estimate from the bitrate and byte count,
     * and it can therefore change slightly as playback proceeds.
     */
    val durationMs: Long? = null,

    /** Set when playback stopped on an error that a retry did not clear. */
    val error: String? = null,
) {
    val hasTrack: Boolean get() = trackId != null

    /** Fraction played, clamped, for the seek bar. 0 while the duration is unknown. */
    val progress: Float
        get() {
            val total = durationMs ?: return 0f
            if (total <= 0L) return 0f
            return (positionMs.toFloat() / total).coerceIn(0f, 1f)
        }
}
