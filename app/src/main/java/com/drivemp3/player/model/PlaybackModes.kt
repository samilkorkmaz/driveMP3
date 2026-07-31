package com.drivemp3.player.model

/**
 * The two transport modes from FR-3.4.2 and FR-3.4.3.
 *
 * Persisted alongside the sort order rather than reset each launch: a listener who
 * shuffles their library means it as a standing preference, not a one-session choice.
 * Search is the deliberate exception — see [com.drivemp3.player.data.SettingsStore].
 */
data class PlaybackModes(
    /** FR-3.4.2: the current track repeats endlessly instead of advancing. */
    val repeatOne: Boolean = false,

    /** FR-3.4.3: next, and end-of-track, draw from the queue in a random order. */
    val shuffle: Boolean = false,
)
