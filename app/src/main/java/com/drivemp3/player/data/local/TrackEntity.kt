package com.drivemp3.player.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * One MP3 as Drive reports it. Holds no ID3 data — see spec section 2.1.
 *
 * Keyed on (id, scopeId) rather than id alone so the same file can be indexed
 * under both a folder scope and the all-Drive scope without one eviction
 * clobbering the other.
 */
@Entity(
    tableName = "tracks",
    primaryKeys = ["id", "scopeId"],
    indices = [
        Index("scopeId", "nameLower"),
        Index("scopeId", "createdTimeEpochMs"),
    ],
)
data class TrackEntity(
    /** Drive file id. */
    val id: String,

    /** Partition key: [com.drivemp3.player.model.LibraryScope.storageKey]. */
    val scopeId: String,

    /** Raw Drive file name, used for display. */
    val name: String,

    /**
     * [name] lowercased at index time, used for prefix search and name sorting.
     *
     * A stored column rather than `LOWER(name)` at query time because SQLite's
     * built-in `LOWER` only handles ASCII, which would break case-insensitive
     * matching on any non-English file name. Kotlin's `lowercase()` is full
     * Unicode and locale-independent.
     */
    val nameLower: String,

    /** Null when Drive reports no size. */
    val sizeBytes: Long?,

    /** Drive's `createdTime` normalised to epoch millis so SQL can sort on it. */
    val createdTimeEpochMs: Long?,
)
