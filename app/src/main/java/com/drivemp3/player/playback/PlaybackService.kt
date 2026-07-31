package com.drivemp3.player.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.drivemp3.player.MainActivity
import com.drivemp3.player.ServiceLocator
import com.drivemp3.player.auth.DriveAuthManager
import com.drivemp3.player.data.DriveApi
import com.drivemp3.player.data.SettingsStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hosts the player for the whole process, not just the visible screen.
 *
 * Moving [ExoPlayer] out of the ViewModel is what v0.4 is really about: a
 * `MediaSessionService` keeps playing across screen-off, backgrounding, and Activity
 * destruction, and the [MediaSession] it publishes is what supplies the notification
 * controls and routes headset and Bluetooth media buttons — none of which the UI has
 * to implement.
 *
 * The UI reaches this through [PlaybackConnection], never directly.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var auth: DriveAuthManager
    private lateinit var settingsStore: SettingsStore
    private lateinit var mediaCache: MediaCache
    private lateinit var dataSourceFactory: DriveHttpDataSourceFactory

    private var mediaSession: MediaSession? = null
    private var recovery: Job? = null
    private var cacheWatcher: Job? = null

    /**
     * The track the cache watcher is following. Held rather than read from the player at
     * transition time, because by then the player already reports the *new* item and the
     * outgoing one — the one that may have just finished downloading — would be lost.
     */
    private var currentCacheKey: String? = null

    /**
     * Guards against a refresh loop: if a fresh token does not clear the 401, the
     * second failure has to surface rather than trigger another refresh. Cleared once
     * playback reaches ready, so a later expiry can refresh again.
     */
    private var hasRetriedAfterAuthFailure = false

    override fun onCreate() {
        super.onCreate()

        auth = ServiceLocator.authManager(this)
        settingsStore = ServiceLocator.settingsStore(this)
        mediaCache = ServiceLocator.mediaCache(this)
        dataSourceFactory = DriveHttpDataSourceFactory { auth.currentToken() }

        // The cache wraps the network source rather than sitting beside it, so the
        // first play streams and downloads in one read (FR-3.2.1) and every later play
        // is served from disk without a request.
        val cachingFactory = mediaCache.dataSourceFactory(dataSourceFactory)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(cachingFactory, extractorsFactory())
            )
            // handleAudioFocus: pause for a phone call, duck for a notification, and
            // stop when another player takes over for good.
            .setAudioAttributes(musicAttributes(), /* handleAudioFocus= */ true)
            // Pause when headphones are unplugged rather than blaring from the speaker.
            .setHandleAudioBecomingNoisy(true)
            // Streaming needs the radio alive with the screen off; Media3 only holds
            // the lock while actually playing.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                addListener(AuthRecoveryListener())
                addListener(CacheSyncListener())
            }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(UriResolvingCallback())
            .setSessionActivity(openAppIntent())
            .build()

        restorePersistedModes(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Swiping the app away should not strand a silent notification, but it also must
     * not kill audio the user left running on purpose.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        recovery?.cancel()
        stopCacheWatcher()
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Rebuilds the stream URL on this side of the session boundary.
     *
     * A [MediaItem] sent by a controller arrives stripped of its `localConfiguration`
     * — Media3 drops the URI in transit, so items handed straight to the player would
     * have nothing to fetch. Resolving from the media id here is the designed hook,
     * and it means the controller only ever needs to know Drive file ids.
     */
    private inner class UriResolvingCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                item.buildUpon()
                    .setUri(DriveApi.mediaUrl(item.mediaId))
                    // Keys the cache by Drive file id rather than by URL. It keeps
                    // cached files findable by id for the badge, and survives any later
                    // change to the URL's shape.
                    .setCustomCacheKey(item.mediaId)
                    .build()
            }
        )
    }

    /**
     * Keeps the cache mirror current while a track streams (FR-3.2.4).
     *
     * A track becomes fully cached at the moment ExoPlayer reads its final byte, and
     * the player has no callback for that — so this polls, cheaply: completeness is an
     * in-memory span lookup, not disk I/O. Polling only runs while something is playing,
     * and stops as soon as the current track is confirmed complete.
     *
     * Transitions and pauses sync directly rather than waiting for the next tick, so
     * the badge appears the moment a track finishes.
     */
    private inner class CacheSyncListener : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startCacheWatcher() else stopCacheWatcher()
            syncCurrentTrack()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Both ends of the transition: the track just left behind is the one most
            // likely to have just completed, and the incoming one may already be cached
            // from an earlier play.
            val outgoing = currentCacheKey
            currentCacheKey = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
            val incoming = currentCacheKey

            scope.launch {
                outgoing?.let { mediaCache.sync(it) }
                incoming?.let { mediaCache.sync(it) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) syncCurrentTrack()
        }
    }

    private fun startCacheWatcher() {
        if (cacheWatcher?.isActive == true) return
        cacheWatcher = scope.launch {
            while (isActive) {
                delay(CACHE_POLL_MS)
                syncCurrentTrack()
            }
        }
    }

    private fun stopCacheWatcher() {
        cacheWatcher?.cancel()
        cacheWatcher = null
    }

    private fun syncCurrentTrack() {
        val fileId = mediaSession?.player?.currentMediaItem?.mediaId ?: return
        if (fileId.isEmpty()) return
        currentCacheKey = fileId
        scope.launch { mediaCache.sync(fileId) }
    }

    /**
     * Fetches a new access token and resumes the current track where it stopped.
     *
     * An access token lasts about an hour, so any track playing across that boundary is
     * refused on its next range request — and background playback makes long sessions
     * the normal case rather than the edge one. Recovery has to be invisible: new
     * token, re-prepare, seek back, keep playing.
     */
    private inner class AuthRecoveryListener : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) hasRetriedAfterAuthFailure = false
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!error.isAuthFailure() || hasRetriedAfterAuthFailure) return
            hasRetriedAfterAuthFailure = true

            val player = mediaSession?.player ?: return
            // Read before re-preparing: this is the position playback must return to.
            val resumePositionMs = player.currentPosition

            recovery?.cancel()
            recovery = scope.launch {
                if (auth.refreshAccessToken() == null) return@launch

                // Reaches the source already open on this track, not only later ones.
                dataSourceFactory.applyCurrentToken()

                player.prepare()
                player.seekTo(resumePositionMs)
                player.play()
            }
        }
    }

    /**
     * Applies the saved loop/shuffle modes once at startup.
     *
     * Matters when a media button revives this service with no UI attached: without it
     * the player would silently fall back to "off" for both while the user's last
     * choice sat in DataStore. Live toggles come from the controller instead, so this
     * runs exactly once and never fights it.
     */
    private fun restorePersistedModes(player: Player) {
        scope.launch {
            val modes = settingsStore.playbackModes.first()
            player.repeatMode =
                if (modes.repeatOne) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.shuffleModeEnabled = modes.shuffle
        }
    }

    /** Tapping the notification returns to the library rather than a fresh task. */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun musicAttributes() = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .setUsage(C.USAGE_MEDIA)
        .build()

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

    private companion object {
        /**
         * Slow on purpose. Nothing depends on the badge being instant, and the exact
         * moment of completion is caught by the transition and end-of-track syncs
         * anyway; this only covers a track that finishes downloading well before it
         * finishes playing.
         */
        const val CACHE_POLL_MS = 5_000L
    }
}
