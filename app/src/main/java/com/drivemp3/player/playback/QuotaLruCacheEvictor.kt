package com.drivemp3.player.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * LRU cache eviction under a user-set quota (FR-3.2.2, FR-3.2.3).
 *
 * Modelled on Media3's own `LeastRecentlyUsedCacheEvictor`, with the two differences the
 * spec requires:
 *
 * - **A pinned key.** The currently-playing file is never evicted, however old its
 *   spans, until playback moves on and [setPinnedKey] releases it. Media3's evictor has
 *   no such notion; it would drop the already-buffered head of the track you are
 *   listening to the moment the quota was tight.
 * - **A runtime-adjustable limit.** The quota is a Settings choice, so [setMaxBytes]
 *   changes it in place and evicts down to the new figure. A fixed-size evictor would
 *   force `SimpleCache` to be torn down and reopened to change the limit, which the
 *   playback service — holding the one instance — cannot allow.
 *
 * "Least recently used" is read from each span's `lastTouchTimestamp`, which
 * `SimpleCache` bumps on every read (we opt in via [requiresCacheSpanTouches]). Playing
 * a track reads its spans, so its bytes move to the most-recently-used end exactly when
 * it plays — touch order is play order, without a second `lastPlayedAt` column that could
 * drift from what is actually on disk. Whole resources are evicted at once
 * ([Cache.removeResource]) rather than span by span, so a file's mirror row — and its
 * "Downloaded" badge — clears in one step through [onEvicted].
 *
 * Thread model: `SimpleCache` makes every callback here under its own monitor, and calls
 * them re-entrantly (an eviction inside [onSpanAdded] triggers [onSpanRemoved] on the
 * same thread). [setMaxBytes] and [setPinnedKey] arrive from other threads. The [lock]
 * guards the mutable state; it is never held across a call back into [cache], so the
 * re-entrant removal cannot deadlock.
 */
@UnstableApi
internal class QuotaLruCacheEvictor(
    initialMaxBytes: Long,
    private val onEvicted: (String) -> Unit,
) : CacheEvictor {

    private val lock = Any()

    private val leastRecentlyUsed = TreeSet(
        // A total order that never returns 0 for two distinct spans: a TreeSet needs it,
        // or it silently drops one span and loses track of its bytes. CacheSpan's own
        // ordering compares position alone, so spans of different files at the same
        // offset would collide.
        Comparator<CacheSpan> { a, b ->
            val byTime = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
            if (byTime != 0) return@Comparator byTime
            val byKey = a.key.compareTo(b.key)
            if (byKey != 0) return@Comparator byKey
            a.position.compareTo(b.position)
        }
    )

    private var currentSize = 0L
    private var maxBytes = initialMaxBytes
    private var pinnedKey: String? = null

    /** Sets the ceiling and immediately evicts down to it (the "quota lowered" case). */
    fun setMaxBytes(cache: Cache, maxBytes: Long) {
        synchronized(lock) { this.maxBytes = maxBytes }
        evict(cache, 0L)
    }

    /** Never evicts — pinning only ever protects data, so it can add nothing to remove. */
    fun setPinnedKey(key: String?) {
        synchronized(lock) { pinnedKey = key }
    }

    override fun requiresCacheSpanTouches(): Boolean = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        // Evict up front for the full incoming file where its length is known, so the
        // download does not momentarily blow past the quota before onSpanAdded catches up.
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        synchronized(lock) {
            leastRecentlyUsed.add(span)
            currentSize += span.length
        }
        evict(cache, 0L)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        synchronized(lock) {
            leastRecentlyUsed.remove(span)
            currentSize -= span.length
        }
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        // A touch re-times the span, so it has to be re-inserted to keep the set ordered.
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /**
     * Removes whole least-recently-used resources until usage plus [requiredSpace] fits
     * under the quota, skipping the pinned file.
     *
     * The key to drop is chosen under [lock] but removed outside it: `removeResource`
     * re-enters through [onSpanRemoved], which needs the lock. The loop re-reads state
     * each pass, so a concurrent [setMaxBytes] or span change is picked up, and the loop
     * ends the moment nothing evictable is left — which is how a single file larger than
     * the whole quota, or a quota consisting only of the pinned track, is tolerated
     * rather than looping forever.
     */
    private fun evict(cache: Cache, requiredSpace: Long) {
        while (true) {
            val keyToEvict = synchronized(lock) {
                if (maxBytes == Long.MAX_VALUE) return
                if (currentSize + requiredSpace <= maxBytes) return
                leastRecentlyUsed.firstOrNull { it.key != pinnedKey }?.key ?: return
            }
            cache.removeResource(keyToEvict)
            onEvicted(keyToEvict)
        }
    }
}
