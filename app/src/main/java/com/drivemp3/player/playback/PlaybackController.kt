package com.drivemp3.player.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.drivemp3.player.auth.DriveAuthManager
import com.drivemp3.player.data.DriveApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Streams one Drive track at a time and reports progress as a [PlaybackState].
 *
 * Foreground only, by design for v0.3: no `MediaSessionService`, no notification, and
 * no audio-focus handling. Those arrive with the transport controls in v0.4, where
 * they belong together.
 *
 * Must be constructed and called from the main thread — [ExoPlayer] binds to the
 * looper of whichever thread builds it and rejects access from any other.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val auth: DriveAuthManager,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val dataSourceFactory = DriveHttpDataSourceFactory { auth.currentToken() }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var ticker: Job? = null
    private var recovery: Job? = null

    /**
     * Guards against a refresh loop: if a fresh token does not clear the 401, the
     * second failure must surface as an error rather than trigger another refresh.
     * Cleared once playback reaches ready, so a later expiry can refresh again.
     */
    private var hasRetriedAfterAuthFailure = false

    /**
     * Held as a [Lazy] rather than a `by lazy` property so teardown can ask whether
     * the player was ever built — a session that browses without playing anything
     * should not allocate a decoder, and must not be released as if it had.
     */
    private val playerHolder = lazy {
        ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory()))
            .build()
            .apply { addListener(PlayerListener()) }
    }

    private val player: ExoPlayer get() = playerHolder.value

    /**
     * Loads and plays the given Drive file. Tapping the track already loaded toggles
     * play/pause instead of restarting it — except once it has run to the end, where
     * a tap reads as "play it again".
     *
     * A track that failed leaves the player idle, so re-tapping its row reloads it from
     * the start. Resuming where it stopped is what the bar's play button does; the two
     * gestures are deliberately different.
     */
    fun play(trackId: String, trackName: String) {
        if (_state.value.trackId == trackId && player.playbackState != Player.STATE_IDLE) {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0L)
                player.play()
            } else {
                togglePlayPause()
            }
            return
        }

        hasRetriedAfterAuthFailure = false
        recovery?.cancel()

        _state.value = PlaybackState(
            trackId = trackId,
            trackName = trackName,
            isBuffering = true,
        )

        player.setMediaItem(mediaItemFor(trackId))
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        if (!_state.value.hasTrack) return

        if (player.isPlaying) {
            player.pause()
        } else {
            // An earlier error leaves the player idle; re-preparing is the documented
            // retry, and it resumes from the position already reached.
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    /** Seeks within the loaded track; ExoPlayer clamps to the known duration. */
    fun seekTo(positionMs: Long) {
        if (!_state.value.hasTrack) return
        player.seekTo(positionMs.coerceAtLeast(0L))
        pushProgress()
    }

    /** Unloads the track and hides the bar. Used on sign-out. */
    fun stop() {
        recovery?.cancel()
        stopTicker()
        if (playerHolder.isInitialized()) {
            player.stop()
            player.clearMediaItems()
        }
        // Safe even though ExoPlayer may deliver its teardown callbacks after this
        // line: they only ever copy progress and flags, never the track identity, so
        // an emptied state stays empty.
        _state.value = PlaybackState()
    }

    fun release() {
        recovery?.cancel()
        stopTicker()
        scope.cancel()
        if (playerHolder.isInitialized()) player.release()
        _state.value = PlaybackState()
    }

    /**
     * Enables constant-bitrate seeking.
     *
     * Without it, an MP3 carrying no Xing/VBRI seek header reports no duration and
     * refuses to seek — the scrubber would be dead for exactly the files a plain
     * `cat`-style encoder produces. With it, ExoPlayer derives both from the bitrate
     * and the `Content-Length` Drive returns, and seeks by byte offset.
     */
    private fun extractorsFactory() = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)

    private fun mediaItemFor(trackId: String) = MediaItem.Builder()
        .setMediaId(trackId)
        .setUri(DriveApi.mediaUrl(trackId))
        // Declared rather than inferred: the URL carries no file extension. This
        // routes the item to a progressive source, which is right for an HTTP MP3.
        .setMimeType(MimeTypes.AUDIO_MPEG)
        .build()

    private inner class PlayerListener : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTicker() else stopTicker()
            pushProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(isBuffering = true) }

                Player.STATE_READY -> {
                    hasRetriedAfterAuthFailure = false
                    _state.update { it.copy(isBuffering = false, error = null) }
                }

                Player.STATE_ENDED -> {
                    // Nothing to advance to until v0.4 adds the queue; park at the end.
                    stopTicker()
                    _state.update {
                        it.copy(
                            isPlaying = false,
                            isBuffering = false,
                            positionMs = it.durationMs ?: it.positionMs,
                        )
                    }
                    return
                }

                Player.STATE_IDLE -> _state.update { it.copy(isBuffering = false) }
            }
            pushProgress()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            pushProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            val status = error.httpStatusCode()

            if (status != null && status.isAuthFailure() && !hasRetriedAfterAuthFailure) {
                hasRetriedAfterAuthFailure = true
                recoverFromAuthFailure()
                return
            }

            stopTicker()
            _state.update {
                it.copy(isPlaying = false, isBuffering = false, error = error.userMessage())
            }
        }
    }

    /**
     * Fetches a new access token and resumes the same track where it stopped.
     *
     * This is the whole reason the player streams through
     * [DriveHttpDataSourceFactory]. An access token lasts about an hour, so any track
     * playing across that boundary is refused on its next range request. Recovery has
     * to be invisible: new token, re-prepare, seek back, keep playing.
     */
    private fun recoverFromAuthFailure() {
        // Read before re-preparing: this is the position playback has to return to.
        val resumePositionMs = player.currentPosition

        recovery?.cancel()
        recovery = scope.launch {
            _state.update { it.copy(isBuffering = true, isPlaying = false, error = null) }

            val token = auth.refreshAccessToken()
            if (token == null) {
                _state.update {
                    it.copy(isBuffering = false, error = "Drive access expired. Sign in again.")
                }
                return@launch
            }

            // Reaches the source already open on this track, not only later ones.
            dataSourceFactory.applyCurrentToken()

            player.prepare()
            player.seekTo(resumePositionMs)
            player.play()
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                pushProgress()
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** The player owns position and duration; [state] only mirrors them. */
    private fun pushProgress() {
        if (!playerHolder.isInitialized()) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        _state.update { it.copy(positionMs = player.currentPosition, durationMs = duration) }
    }

    private companion object {
        /**
         * Twice a second: fine enough that the elapsed-time label never looks stuck,
         * coarse enough to stay clear of the main thread's critical path.
         */
        const val POSITION_POLL_MS = 500L

        fun Throwable.causeChain(): Sequence<Throwable> =
            generateSequence(this) { it.cause }

        /** The HTTP status Drive refused the read with, if the failure was one. */
        fun PlaybackException.httpStatusCode(): Int? = causeChain()
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.responseCode

        /**
         * 401 is the expired token. 403 is included because a revoked or downgraded
         * grant surfaces that way too, and one silent re-authorization is cheap; a 403
         * that was really a download-quota rejection just fails the retry and reports
         * itself normally.
         */
        fun Int.isAuthFailure(): Boolean = this == 401 || this == 403

        /**
         * Ordered most specific first. An HTTP status is checked ahead of
         * [IOException] because Media3's response-code exception *is* one, and
         * "check your connection" would be wrong advice for a 403.
         */
        fun PlaybackException.userMessage(): String {
            httpStatusCode()?.let { status ->
                return if (status.isAuthFailure()) {
                    "Drive refused this track. Sign in again."
                } else {
                    "Drive returned HTTP $status."
                }
            }
            return if (causeChain().any { it is IOException }) {
                "Could not reach Drive. Check your connection."
            } else {
                "This track could not be played."
            }
        }
    }
}
