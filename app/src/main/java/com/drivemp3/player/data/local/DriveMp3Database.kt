package com.drivemp3.player.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class],
    // 2: added TrackEntity.nameLower for prefix search. No migration is written —
    // the index is a cache of Drive, so it is rebuilt on next refresh.
    version = 2,
    exportSchema = false,
)
abstract class DriveMp3Database : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        const val NAME = "drivemp3.db"
    }
}
