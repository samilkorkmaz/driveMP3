package com.drivemp3.player.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedFileDao {

    /**
     * Ids only: the library list needs nothing more than "is this one downloaded",
     * and a set of ids is far cheaper to diff on each emission than whole rows.
     */
    @Query("SELECT fileId FROM cached_files")
    fun observeCachedIds(): Flow<List<String>>

    @Query("SELECT fileId FROM cached_files")
    suspend fun cachedIds(): List<String>

    /**
     * Total bytes of every fully-downloaded file, for the storage summary.
     *
     * `COALESCE` because `SUM` over no rows is SQL NULL, not 0 — an empty cache should
     * read as "0 B downloaded", not as unknown.
     */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cached_files")
    fun observeTotalSize(): Flow<Long>

    @Upsert
    suspend fun upsert(entity: CachedFileEntity)

    @Query("DELETE FROM cached_files WHERE fileId IN (:fileIds)")
    suspend fun deleteAll(fileIds: List<String>)

    @Query("DELETE FROM cached_files")
    suspend fun clear()
}
