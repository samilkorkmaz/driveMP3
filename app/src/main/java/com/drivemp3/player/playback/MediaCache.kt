package com.drivemp3.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import com.drivemp3.player.data.SettingsStore
import com.drivemp3.player.data.local.CachedFileDao
import com.drivemp3.player.data.local.CachedFileEntity
import com.drivemp3.player.model.CacheQuota
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * @param settingsStore watched for the cache quota (FR-3.2.2); a change applies live.
 */
@OptIn(UnstableApi::class)
class MediaCache(
    context: Context,
    private val dao: CachedFileDao,
    settingsStore: SettingsStore,
) {

    private val appContext = context.applicationContext

    /**
     * Process-lived, never cancelled: this object is a ServiceLocator singleton. Carries
     * the quota-watching collector and the fire-and-forget mirror deletes an eviction
     * triggers. IO because both open or touch the cache directory.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Under `filesDir`, deliberately not `cacheDir`: Android reclaims `cacheDir` under
     * storage pressure without asking, and spec section 5 requires downloaded tracks to
     * stay playable offline.
     */
    private val directory = File(appContext.filesDir, CACHE_DIR_NAME)

    /**
     * The LRU-under-quota evictor (FR-3.2.3). When it drops a file, the mirror row is
     * deleted here so the "Downloaded" badge and storage totals clear with it. The delete
     * is fire-and-forget and idempotent — a manual [remove] may have beaten it to the row.
     */
    private val evictor = QuotaLruCacheEvictor(
        initialMaxBytes = CacheQuota.DEFAULT.bytes,
        onEvicted = { fileId -> scope.launch { dao.deleteAll(listOf(fileId)) } },
    )

    private val cache: SimpleCache by lazy {
        SimpleCache(
            directory,
            evictor,
            StandaloneDatabaseProvider(appContext),
        )
    }

    init {
        // Apply the quota, and evict down to it, on every change — including the first
        // value at launch, which enforces a limit lowered in a previous session. Touching
        // `cache` opens it (a directory scan), so this deliberately runs on the IO scope,
        // never the main thread.
        scope.launch {
            settingsStore.cacheQuota.collect { quota -> evictor.setMaxBytes(cache, quota.bytes) }
        }
    }

    /**
     * Protects [fileId] from eviction while it plays, and releases the previous pin
     * (FR-3.2.3). Null when playback stops. Cheap enough for the main thread — it only
     * sets a field, and never opens the cache.
     */
    fun pin(fileId: String?) = evictor.setPinnedKey(fileId)

    /**
     * Bytes on disk across every cached span, complete or partial — the true cache
     * footprint for the Settings storage bar, which is larger than the mirror's total of
     * only fully-downloaded files.
     */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { cache.cacheSpace }

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

    /**
     * Usable free space on the volume that holds the download cache, for the storage
     * summary shown alongside the total downloaded size.
     *
     * Read from `filesDir` rather than [directory]: the two share a volume, and
     * `filesDir` always exists, so this reports correctly even before the cache has been
     * opened and its directory created. `usableSpace` — not `freeSpace` — because it
     * accounts for the reserve the OS won't hand to an app.
     */
    fun freeSpaceBytes(): Long = appContext.filesDir.usableSpace

    /**
     * Deletes one track's downloaded bytes and its mirror row.
     *
     * `removeResource` drops every span for the key from disk; the caller is expected to
     * have stopped playback of this track first, since removing bytes out from under an
     * active read would only force a re-fetch. The DAO delete follows the disk, keeping
     * this the same direction as [sync] and [reconcile].
     */
    suspend fun remove(fileId: String): Unit = withContext(Dispatchers.IO) {
        cache.removeResource(fileId)
        dao.deleteAll(listOf(fileId))
    }

    /**
     * Empties the whole download cache and the mirror.
     *
     * `getKeys` returns a snapshot, so removing while iterating it is safe. The mirror is
     * cleared in one statement rather than per key — it is only a mirror, and a stray row
     * left after its bytes are gone would show a phantom "Downloaded" badge until the next
     * reconcile.
     */
    suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        cache.keys.forEach { key -> cache.removeResource(key) }
        dao.clear()
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
