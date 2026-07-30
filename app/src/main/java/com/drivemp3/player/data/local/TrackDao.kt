package com.drivemp3.player.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Four explicit ordered queries rather than one interpolated `ORDER BY`: Room
 * cannot parameterise sort clauses safely, and the alternative (`@RawQuery`) gives
 * up compile-time verification of the SQL.
 *
 * Search folds into the same four queries instead of doubling them: an empty search
 * passes the pattern `%`, which matches every row. See
 * [com.drivemp3.player.data.TrackRepository.prefixPattern].
 *
 * Both search and name ordering use `nameLower`, so the `(scopeId, nameLower)` index
 * serves both. The `createdTimeEpochMs IS NULL` leading term keeps undated files at
 * the end in either direction, and `nameLower` is the tiebreaker so equal timestamps
 * produce a stable order across reloads.
 */
@Dao
interface TrackDao {

    @Query(
        """
        SELECT * FROM tracks
        WHERE scopeId = :scopeId AND nameLower LIKE :namePattern ESCAPE '\'
        ORDER BY nameLower ASC
        """
    )
    fun observeByNameAsc(scopeId: String, namePattern: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE scopeId = :scopeId AND nameLower LIKE :namePattern ESCAPE '\'
        ORDER BY nameLower DESC
        """
    )
    fun observeByNameDesc(scopeId: String, namePattern: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE scopeId = :scopeId AND nameLower LIKE :namePattern ESCAPE '\'
        ORDER BY createdTimeEpochMs IS NULL ASC,
                 createdTimeEpochMs ASC,
                 nameLower ASC
        """
    )
    fun observeByCreatedTimeAsc(scopeId: String, namePattern: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT * FROM tracks
        WHERE scopeId = :scopeId AND nameLower LIKE :namePattern ESCAPE '\'
        ORDER BY createdTimeEpochMs IS NULL ASC,
                 createdTimeEpochMs DESC,
                 nameLower ASC
        """
    )
    fun observeByCreatedTimeDesc(scopeId: String, namePattern: String): Flow<List<TrackEntity>>

    @Query("SELECT COUNT(*) FROM tracks WHERE scopeId = :scopeId")
    suspend fun countInScope(scopeId: String): Int

    /**
     * Swaps a scope's contents atomically, so a failed refresh can never leave the
     * list half-populated and readers never observe an empty intermediate state.
     */
    @Transaction
    suspend fun replaceScope(scopeId: String, tracks: List<TrackEntity>) {
        deleteScope(scopeId)
        insertAll(tracks)
    }

    @Query("DELETE FROM tracks WHERE scopeId = :scopeId")
    suspend fun deleteScope(scopeId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)
}
