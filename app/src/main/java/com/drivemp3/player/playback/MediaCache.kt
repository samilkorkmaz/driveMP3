package com.drivemp3.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.drivemp3.player.data.local.CachedFileDao
import com.drivemp3.player.data.local.CachedFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The local MP3 cache (FR-3.2.1, FR-3.2.4).
 *
 * Downloading is not a separate operation from playing. A [CacheDataSource] wrapped
 * around the Drive HTTP source writes every byte it fetches straight to disk on its way
 * to the decoder, so a first play streams and downloads in a single read, and a later
 * play of the same track never touches the network. That write-through arrangement is
 * the whole reason the project is on Media3 rather than a cross-platform wrapper.
 *
 * Two things come for free with [SimpleCache] and are worth knowing rather than
 * re-implementing:
 *
 * - **Atomic writes.** Bytes go to a `.tmp` file that is only renamed into the cache
 *   once the span is committed, so a process killed mid-download leaves a stray temp
 *   file, never a corrupt entry. `SimpleCache` deletes leftover temp files on startup.
 * - **Range awareness.** The cache is indexed by span, so resuming a partly-downloaded
 *   track fetches only the gap.
 *
 * @param dao the queryable mirror of what is on disk. Disk is authoritative; [reconcile]
 *   and [sync] bring the mirror back in line rather than the other way round.
 */
@OptIn(UnstableApi::class)
class MediaCache(
    context: Context,
    private val dao: CachedFileDao,
) {

    private val appContext = context.applicationContext

    /**
     * Under `filesDir`, deliberately not `cacheDir`: Android reclaims `cacheDir` under
     * storage pressure without asking, and spec section 5 requires downloaded tracks to
     * stay playable offline.
     */
    private val directory = File(appContext.filesDir, CACHE_DIR_NAME)

    /**
     * [NoOpCacheEvictor] — the cache grows without limit for now. v0.5 does this on
     * purpose so that v0.6's quota and LRU eviction can be built against a real, full
     * cache rather than a synthetic one. See VERSION_PLAN.md section 3.
     */
    private val cache: SimpleCache by lazy {
        SimpleCache(
            directory,
            NoOpCacheEvictor(),
            StandaloneDatabaseProvider(appContext),
        )
    }

    /**
     * Wraps [upstream] so reads are served from disk where possible and written to disk
     * where not.
     *
     * The indirection through a lambda factory is not incidental: opening [SimpleCache]
     * scans the cache directory and opens its index database, which must not happen on
     * the main thread. Building the real [CacheDataSource.Factory] eagerly here would do
     * exactly that, because this is called from the service's `onCreate`. Deferring to
     * `createDataSource` moves the first touch onto ExoPlayer's loading thread.
     *
     * `FLAG_IGNORE_CACHE_ON_ERROR` keeps a failed *cache write* — a full disk, say —
     * from taking playback down with it: the read falls back to the network and the
     * track still plays, just uncached.
     */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory {
        val delegate: CacheDataSource.Factory by lazy {
            CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(upstream)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
        return DataSource.Factory { delegate.createDataSource() }
    }

    /** Brings one file's row in line with the disk. */
    suspend fun sync(fileId: String): Unit = withContext(Dispatchers.IO) {
        val completeBytes = completeSizeOf(fileId)
        if (completeBytes == null) {
            dao.deleteAll(listOf(fileId))
        } else {
            dao.upsert(
                CachedFileEntity(
                    fileId = fileId,
                    sizeBytes = completeBytes,
                    downloadedAtEpochMs = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Rebuilds the mirror for a whole list, used on launch and after a rescan to pick up
     * files cached in an earlier session.
     *
     * Writes only the differences. An unconditional upsert would make Room emit on every
     * call, and since the visible track list is what triggers this, that would be a loop.
     */
    suspend fun reconcile(fileIds: List<String>): Unit = withContext(Dispatchers.IO) {
        val known = dao.cachedIds().toSet()
        val complete = fileIds.associateWith { completeSizeOf(it) }.filterValues { it != null }

        val now = System.currentTimeMillis()
        complete
            .filterKeys { it !in known }
            .forEach { (fileId, size) ->
                dao.upsert(
                    CachedFileEntity(
                        fileId = fileId,
                        sizeBytes = size ?: 0L,
                        downloadedAtEpochMs = now,
                    )
                )
            }

        // Only ids in this list are considered: a file absent from the current folder is
        // not evidence that it left the cache.
        val stale = fileIds.filter { it in known && it !in complete }
        if (stale.isNotEmpty()) dao.deleteAll(stale)
    }

    /**
     * The bytes held on disk if the file is playable offline end to end, otherwise null.
     *
     * Two details matter here, and both were arrived at from measurements rather than
     * from the API docs.
     *
     * **Contiguity.** `getCachedLength` gives the unbroken run starting at byte 0, where
     * `getCachedBytes` would give the total across the range. Seeking around a track
     * leaves holes, and a file with holes is not playable offline however many bytes it
     * adds up to — so the unbroken run is the honest measure.
     *
     * **The trailing tolerance.** An MP3 usually ends with a 128-byte ID3v1 tag, and
     * `Mp3Extractor` stops the moment it fails to find another MPEG frame, so those last
     * bytes are never requested and never cached. A strict `cached == contentLength`
     * test therefore denies the badge to a track that is, for every playback purpose,
     * fully downloaded — observed exactly that way on *Manastır Türküsü*: 2,347,040 of
     * 2,347,168 bytes, short by precisely 128. The allowance is sized to cover the
     * trailers that actually occur — ID3v1 (128), TAG+ (227), Lyrics3v2 and small APEv2
     * — while staying far below any real MP3, so it cannot pass off a partial download
     * as a complete one.
     *
     * The badge therefore means "every byte the player will ask for is on disk", which
     * is what FR-3.2.4 is for.
     */
    private fun completeSizeOf(fileId: String): Long? {
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(fileId))
        if (contentLength <= 0L) return null

        // Negative when byte 0 itself is uncached; the check below rejects that too.
        val contiguousFromStart = cache.getCachedLength(fileId, 0L, contentLength)
        if (contiguousFromStart < contentLength - TRAILING_METADATA_TOLERANCE_BYTES) return null

        // Report what is actually occupying storage, not the nominal file size — v0.6
        // totals these against the quota.
        return cache.getCachedBytes(fileId, 0L, contentLength).coerceAtLeast(0L)
    }

    private companion object {
        const val CACHE_DIR_NAME = "media_cache"

        /**
         * How many unread bytes at the end of a file still count as fully downloaded.
         * See [completeSizeOf] — this is the ID3v1/Lyrics3/APE trailer that the MP3
         * extractor never reads, not slack in the download.
         */
        const val TRAILING_METADATA_TOLERANCE_BYTES = 8L * 1024L
    }
}
