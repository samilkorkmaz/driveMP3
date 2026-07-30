package com.drivemp3.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource

/**
 * Supplies ExoPlayer with HTTP sources that carry the Drive bearer token.
 *
 * Drive's `files/{id}?alt=media` endpoint takes no query-string credential, so the
 * token has to travel as an `Authorization` header on every GET the player makes —
 * including the ranged ones it issues when seeking. That rules out handing ExoPlayer
 * a plain URL and means owning the [DataSource.Factory].
 *
 * The token is read from [tokenProvider] at request time, never captured once:
 * [createDataSource] is called on a loading thread, and the token in play may have
 * been replaced since the track started.
 */
@OptIn(UnstableApi::class)
class DriveHttpDataSourceFactory(
    private val tokenProvider: () -> String?,
) : DataSource.Factory {

    private val delegate = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
        .setReadTimeoutMs(READ_TIMEOUT_MS)
        // Drive answers `?alt=media` with a redirect to a content-serving host. Media3
        // follows same-scheme redirects regardless; this only additionally permits a
        // scheme change, and carries the Authorization header along — acceptable
        // because the hop stays inside Google's own hosts.
        .setAllowCrossProtocolRedirects(true)

    /**
     * Publishes the current token to sources created from here.
     *
     * The factory's default request properties are a single object shared with every
     * [DefaultHttpDataSource] it has produced, and each source re-reads them on
     * `open()`. So this reaches the source already streaming the current track, which
     * is what lets a mid-track token refresh take effect without rebuilding anything.
     */
    fun applyCurrentToken() {
        val token = tokenProvider()
        delegate.setDefaultRequestProperties(
            if (token == null) emptyMap() else mapOf("Authorization" to "Bearer $token")
        )
    }

    override fun createDataSource(): DataSource {
        applyCurrentToken()
        return delegate.createDataSource()
    }

    private companion object {
        /** Deliberately version-less, so it cannot drift from the build file. */
        const val USER_AGENT = "DriveMP3 (Android)"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
    }
}
