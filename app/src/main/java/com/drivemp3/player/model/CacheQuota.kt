package com.drivemp3.player.model

/**
 * The offline-cache size limit the user picks in Settings (FR-3.2.2).
 *
 * Stored by [name] in DataStore, like the sort enums, so an unknown or renamed value
 * falls back to [DEFAULT] rather than throwing.
 *
 * [Unlimited] is carried as [Long.MAX_VALUE] so the evictor can treat "no limit" as an
 * ordinary — if unreachable — ceiling and skip eviction with a single comparison, rather
 * than threading a nullable through every size check.
 */
enum class CacheQuota(val bytes: Long) {
    Mb250(250L * 1024 * 1024),
    Mb500(500L * 1024 * 1024),
    Gb1(1024L * 1024 * 1024),
    Gb5(5L * 1024 * 1024 * 1024),
    Unlimited(Long.MAX_VALUE);

    val isUnlimited: Boolean get() = this == Unlimited

    companion object {
        /**
         * Unlimited on purpose: enabling a limit deletes downloads, so upgrading into
         * v0.6 must not silently start evicting. The user opts into a ceiling.
         */
        val DEFAULT = Unlimited
    }
}
