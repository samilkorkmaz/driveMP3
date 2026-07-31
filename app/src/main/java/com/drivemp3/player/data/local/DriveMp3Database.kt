package com.drivemp3.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, CachedFileEntity::class],
    // 2: added TrackEntity.nameLower for prefix search.
    // 3: added CachedFileEntity for the "Downloaded" badge.
    // No migrations are written — every table here is a cache of something else
    // (Drive for tracks, SimpleCache for downloads), so both are rebuilt rather than
    // migrated. Dropping cached_files does not delete a single downloaded byte; the
    // next reconcile re-derives it from what is actually on disk.
    version = 3,
    exportSchema = false,
)
abstract class DriveMp3Database : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    abstract fun cachedFileDao(): CachedFileDao

    companion object {
        const val NAME = "drivemp3.db"
    }
}
