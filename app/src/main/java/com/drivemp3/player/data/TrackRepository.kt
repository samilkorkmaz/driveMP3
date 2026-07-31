package com.drivemp3.player.data

import com.drivemp3.player.data.local.CachedFileDao
import com.drivemp3.player.data.local.TrackDao
import com.drivemp3.player.data.local.TrackEntity
import com.drivemp3.player.model.LibraryScope
import com.drivemp3.player.model.SortDirection
import com.drivemp3.player.model.SortField
import com.drivemp3.player.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.OffsetDateTime

/**
 * The library, read from the local index and refreshed from Drive.
 *
 * Reads never wait on the network: [observeTracks] emits whatever is indexed, and
 * [refresh] replaces a scope's rows when new data arrives. That is what keeps
 * listing under the one-second target in spec section 5, what makes the list survive
 * going offline, and what makes search resolve per keystroke without a request.
 */
class TrackRepository(
    private val driveRepository: DriveRepository,
    private val trackDao: TrackDao,
    private val cachedFileDao: CachedFileDao,
) {

    /**
     * Ids of tracks held complete on disk, for the "Downloaded" badge (FR-3.2.4).
     *
     * Kept as its own flow rather than joined into [observeTracks]: cache state changes
     * on a completely different cadence from the library index, and combining them in
     * SQL would re-run the sorted track query every time a download finished.
     */
    fun observeDownloadedIds(): Flow<Set<String>> =
        cachedFileDao.observeCachedIds().map { it.toSet() }

    /**
     * @param nameQuery prefix to match against file names, case-insensitively.
     *   Blank means no filtering.
     */
    fun observeTracks(
        scope: LibraryScope,
        sortOrder: SortOrder,
        nameQuery: String = "",
    ): Flow<List<TrackEntity>> {
        val scopeId = scope.storageKey
        val pattern = prefixPattern(nameQuery)
        val ascending = sortOrder.direction == SortDirection.Ascending

        return when (sortOrder.field) {
            SortField.Name ->
                if (ascending) trackDao.observeByNameAsc(scopeId, pattern)
                else trackDao.observeByNameDesc(scopeId, pattern)

            SortField.CreatedTime ->
                if (ascending) trackDao.observeByCreatedTimeAsc(scopeId, pattern)
                else trackDao.observeByCreatedTimeDesc(scopeId, pattern)
        }
    }

    suspend fun isScopeIndexed(scope: LibraryScope): Boolean =
        trackDao.countInScope(scope.storageKey) > 0

    /**
     * Fetches every page for [scope] and swaps the indexed rows atomically.
     *
     * @return the Drive file ids now indexed, which the caller uses to reconcile the
     *   download badges against what is actually on disk.
     */
    suspend fun refresh(
        accessToken: String,
        scope: LibraryScope,
        sortOrder: SortOrder,
    ): List<String> {
        val files = driveRepository.fetchMp3Files(
            accessToken = accessToken,
            scope = scope,
            orderBy = sortOrder.driveOrderBy,
        )

        val entities = files.map { file -> file.toEntity(scope.storageKey) }
        trackDao.replaceScope(scope.storageKey, entities)
        return entities.map { it.id }
    }

    companion object {
        /**
         * Builds a SQL `LIKE` pattern matching names that *start with* [query].
         *
         * `%` and `_` are wildcards in `LIKE`, so a name containing them would
         * otherwise match far more than the user typed — searching `_` would match
         * every single-character prefix. They are escaped with a backslash, which
         * the DAO declares via `ESCAPE '\'`. Backslash itself is escaped first,
         * otherwise it would corrupt the escapes added after it.
         *
         * A blank query yields `%`, which matches every row, so the search and
         * no-search cases share one query.
         */
        fun prefixPattern(query: String): String {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return "%"

            val escaped = trimmed.lowercase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")

            return "$escaped%"
        }
    }
}

private fun DriveFile.toEntity(scopeId: String) = TrackEntity(
    id = id,
    scopeId = scopeId,
    name = name,
    nameLower = name.lowercase(),
    sizeBytes = size?.toLongOrNull(),
    createdTimeEpochMs = parseRfc3339(createdTime),
)

/**
 * [OffsetDateTime] rather than [java.time.Instant] so both `...Z` and `...+02:00`
 * forms parse. Normalising to epoch millis at write time lets SQLite do the date
 * sorting.
 */
private fun parseRfc3339(timestamp: String?): Long? {
    if (timestamp == null) return null
    return runCatching { OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }.getOrNull()
}
