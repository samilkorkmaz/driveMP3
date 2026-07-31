package com.drivemp3.player.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One MP3 held complete on local storage, mirroring what ExoPlayer's `SimpleCache`
 * actually has on disk (FR-3.2.4).
 *
 * A table of its own rather than columns on [TrackEntity], for two reasons:
 *
 * - [TrackEntity] rows are wiped and re-inserted wholesale on every folder rescan
 *   (`replaceScope`), which would throw away cache state on each refresh.
 * - Being cached is a property of the *file*, not of a scope. The same MP3 indexed
 *   under both a folder and the all-Drive scope has two [TrackEntity] rows and one
 *   entry here, so the badge is consistent between them.
 *
 * The cache on disk is authoritative; this is a queryable mirror of it, reconciled by
 * [com.drivemp3.player.playback.MediaCache]. A row exists only for a *fully* cached
 * file — a partial download is not "Downloaded".
 */
@Entity(tableName = "cached_files")
data class CachedFileEntity(
    /** Drive file id, which is also the ExoPlayer cache key. */
    @PrimaryKey val fileId: String,

    /** Bytes on disk. Used by v0.6 to total cache usage against the quota. */
    val sizeBytes: Long,

    /** When the file was first observed complete. */
    val downloadedAtEpochMs: Long,
)
