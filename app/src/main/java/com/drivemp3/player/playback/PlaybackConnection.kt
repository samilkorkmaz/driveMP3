package com.drivemp3.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.drivemp3.player.data.local.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The UI's handle on [PlaybackService].
 *
 * Owns no player. It binds a [MediaController] to the session, mirrors the player's
 * state into a [PlaybackState] flow, and forwards commands. That split is what lets
 * the Activity be destroyed and rebuilt while audio keeps going: this object dies with
 * the ViewModel, the service does not.
 *
 * Main-thread only, like [MediaController] itself.
 */
class PlaybackConnection(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var ticker: Job? = null

    /**
     * Commands issued before the binding completes. One slot, last-wins: connection
     * takes a few hundred milliseconds at launch, and the only realistic racer is a
     * very fast first tap, for which "play the last thing tapped" is right.
     */
    private var pendingAction: (MediaController.() -> Unit)? = null

    /** Survives a reconnect, so an error stays on screen if the Activity is recreated. */
    private var errorMessage: String? = null

    /**
     * True between an auth failure and the service's retry landing. Mirrors the
     * service's own one-shot retry so the bar shows a spinner rather than an error for
     * a recovery already in flight — and still surfaces the error if the retry fails.
     * See [PlaybackErrors].
     */
    private var isRecoveringAuth = false

    init {
        connect()
    }

    private fun connect() {
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()

        future.addListener(
            {
                val connected = runCatching { future.get() }.getOrNull() ?: return@addListener
                controller = connected
                connected.addListener(ControllerListener())

                pendingAction?.let { action ->
                    pendingAction = null
                    connected.action()
                }

                syncFromController()
                if (connected.isPlaying) startTicker()
            },
            // Media3 requires controller access on the thread that built it.
            ContextCompat.getMainExecutor(appContext),
        )
    }

    private fun withController(action: MediaController.() -> Unit) {
        val connected = controller
        if (connected == null) {
            pendingAction = action
            return
        }
        connected.action()
    }

    /**
     * Plays [track], with [queue] — the library exactly as the user currently has it
     * sorted and filtered — becoming what skip and shuffle move through (FR-3.4.3).
     *
     * Tapping the current track toggles play/pause instead of restarting it, except
     * once it has ended, where a tap reads as "play it again".
     */
    fun play(track: TrackEntity, queue: List<TrackEntity>) {
        withController {
            if (currentMediaItem?.mediaId == track.id && playbackState != Player.STATE_IDLE) {
                if (playbackState == Player.STATE_ENDED) {
                    seekTo(0L)
                    play()
                } else {
                    togglePlayPause()
                }
                return@withController
            }

            // Falling back to index 0 of the queue would start a different track than
            // the one tapped, so a track missing from its own queue gets a queue of one.
            val index = queue.indexOfFirst { it.id == track.id }
            val items = if (index >= 0) queue.map(::mediaItemFor) else listOf(mediaItemFor(track))
            val startIndex = if (index >= 0) index else 0

            errorMessage = null
            isRecoveringAuth = false

            setMediaItems(items, startIndex, 0L)
            prepare()
            play()
        }
    }

    /**
     * Re-points the queue at the library's current order without interrupting audio.
     *
     * The requirement is that skip follows the list *as it is now*, so re-sorting or
     * narrowing the search has to be reflected. A plain `setMediaItems` would re-buffer
     * the current track and put an audible gap in every sort toggle, so the ranges
     * before and after it are replaced separately: the playing item falls in neither
     * range and is therefore never removed and re-added.
     *
     * A [tracks] list that no longer contains the current track is ignored rather than
     * obeyed: that is what typing a search that filters out what you are listening to
     * looks like, and stopping playback would be a hostile reading of it.
     */
    fun updateQueue(tracks: List<TrackEntity>) {
        withController {
            val currentId = currentMediaItem?.mediaId ?: return@withController
            val newIndex = tracks.indexOfFirst { it.id == currentId }
            if (newIndex < 0) return@withController

            if (hasExactly(tracks)) return@withController

            val currentIndex = currentMediaItemIndex
            val head = tracks.subList(0, newIndex).map(::mediaItemFor)
            val tail = tracks.subList(newIndex + 1, tracks.size).map(::mediaItemFor)

            // Tail first: replacing the head shifts every index after it.
            replaceMediaItems(currentIndex + 1, mediaItemCount, tail)
            replaceMediaItems(0, currentIndex, head)
        }
    }

    fun togglePlayPause() = withController {
        if (isPlaying) {
            pause()
        } else {
            // An earlier error leaves the player idle; re-preparing is the documented
            // retry, and it resumes from the position already reached.
            if (playbackState == Player.STATE_IDLE) prepare()
            play()
        }
    }

    /**
     * Both skips leave play/pause exactly as they found it.
     *
     * That is what the notification's own buttons do — they reach the player directly,
     * bypassing this class — so forcing playback here would make the two sets of
     * controls behave differently for the same gesture. The only nudge is re-preparing
     * a player left idle by an earlier error.
     */
    fun skipToNext() = withController {
        if (playbackState == Player.STATE_IDLE) prepare()
        seekToNextMediaItem()
    }

    /** Restarts the current track when past its first seconds, as every player does. */
    fun skipToPrevious() = withController {
        if (playbackState == Player.STATE_IDLE) prepare()
        seekToPrevious()
    }

    fun seekTo(positionMs: Long) = withController {
        seekTo(positionMs.coerceAtLeast(0L))
        syncFromController()
    }

    fun setRepeatOne(enabled: Boolean) = withController {
        repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setShuffle(enabled: Boolean) = withController {
        shuffleModeEnabled = enabled
    }

    /** Empties the queue and hides the bar. Used on sign-out. */
    fun stopPlayback() = withController {
        // Named `stopPlayback` rather than `stop` so this line is unambiguously the
        // MediaController's, not a recursive call.
        stop()
        clearMediaItems()
        errorMessage = null
        isRecoveringAuth = false
        syncFromController()
    }

    /**
     * Releases the binding only. The service keeps the player and keeps playing — that
     * is the point of moving it out of the ViewModel.
     */
    fun release() {
        stopTicker()
        scope.cancel()
        controller?.release()
        controller = null
    }

    private inner class ControllerListener : Player.Listener {

        override fun onEvents(player: Player, events: Player.Events) {
            // One handler for every state-bearing event: the controller is the single
            // source of truth, so each callback does the same full re-read.
            syncFromController()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startTicker() else stopTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                isRecoveringAuth = false
                errorMessage = null
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (error.isAuthFailure() && !isRecoveringAuth) {
                // The service is refreshing the token; show a spinner, not a failure.
                isRecoveringAuth = true
                errorMessage = null
            } else {
                isRecoveringAuth = false
                errorMessage = error.userMessage()
            }
            // No sync here: onEvents always closes out the batch and does it.
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                syncFromController()
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    /** The session owns all of this; [state] only mirrors it. */
    private fun syncFromController() {
        val c = controller ?: return
        val item = c.currentMediaItem

        _state.value = PlaybackState(
            trackId = item?.mediaId?.takeIf { it.isNotEmpty() },
            trackName = item?.mediaMetadata?.title?.toString(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING || isRecoveringAuth,
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET && it > 0L },
            repeatOne = c.repeatMode == Player.REPEAT_MODE_ONE,
            shuffle = c.shuffleModeEnabled,
            // Command availability rather than hasNextMediaItem/hasPreviousMediaItem:
            // it is the player's own answer to "would this button do anything", and it
            // accounts for previous restarting the current track at the head of a queue.
            hasNext = c.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT),
            hasPrevious = c.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS),
            error = errorMessage,
        )
    }

    /** Cheap guard against re-issuing a queue replacement that would change nothing. */
    private fun MediaController.hasExactly(tracks: List<TrackEntity>): Boolean {
        if (mediaItemCount != tracks.size) return false
        return tracks.indices.all { getMediaItemAt(it).mediaId == tracks[it].id }
    }

    private companion object {
        /**
         * Twice a second: fine enough that the elapsed-time label never looks stuck,
         * coarse enough to stay clear of the main thread's critical path.
         */
        const val POSITION_POLL_MS = 500L

        /**
         * Carries the Drive file id and the display name only. The URI is deliberately
         * absent — Media3 strips it in transit anyway, and [PlaybackService] rebuilds
         * it from the media id. The title rides along because the notification reads
         * it from the metadata.
         */
        fun mediaItemFor(track: TrackEntity): MediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.name).build())
            .build()
    }
}
