package com.drivemp3.player.ui

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.drivemp3.player.ServiceLocator
import com.drivemp3.player.auth.AuthResult
import com.drivemp3.player.auth.DriveAuthManager
import com.drivemp3.player.data.DriveRepository
import com.drivemp3.player.data.NetworkMonitor
import com.drivemp3.player.data.SettingsStore
import com.drivemp3.player.data.TrackRepository
import com.drivemp3.player.data.local.TrackEntity
import com.drivemp3.player.model.CacheQuota
import com.drivemp3.player.model.LibraryScope
import com.drivemp3.player.model.SortField
import com.drivemp3.player.model.SortOrder
import com.drivemp3.player.playback.MediaCache
import com.drivemp3.player.playback.PlaybackConnection
import com.drivemp3.player.playback.PlaybackState
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

sealed interface LibraryUiState {
    data object SignedOut : LibraryUiState
    data object Loading : LibraryUiState
    data class ConsentRequired(val pendingIntent: PendingIntent) : LibraryUiState

    /** Signed in but no folder chosen yet — the picker is mandatory at this point. */
    data object NeedsFolderSelection : LibraryUiState

    data class Content(
        val email: String?,
        val scope: LibraryScope,
        val sortOrder: SortOrder,
        /** Already filtered by [searchQuery]; the UI renders these as-is. */
        val tracks: List<TrackEntity>,
        /** Ids held complete on disk — the "Downloaded" badge (FR-3.2.4). */
        val downloadedTrackIds: Set<String>,
        /** Total bytes of every fully-downloaded track. */
        val downloadedBytes: Long,
        /** Usable free space on the volume that holds the download cache. */
        val freeSpaceBytes: Long,
        val searchQuery: String,
        val isRefreshing: Boolean,
        /** No connectivity (v0.7): the list still renders, but only cached tracks play. */
        val isOffline: Boolean,
        /** Cached-only view (v0.7): [tracks] is filtered to downloaded files. */
        val showDownloadedOnly: Boolean,
        /** A failed refresh: shown as a banner without discarding the indexed list. */
        val refreshError: String? = null,
    ) : LibraryUiState

    data class Failed(val message: String) : LibraryUiState
}

/** What the Settings screen renders (spec §6). */
data class SettingsUiState(
    val email: String?,
    val quota: CacheQuota,
    /** Bytes on disk across every cached span, complete or partial. */
    val usedCacheBytes: Long,
    /** Usable free space on the volume that holds the cache. */
    val deviceFreeBytes: Long,
)

class LibraryViewModel(
    private val auth: DriveAuthManager,
    private val settingsStore: SettingsStore,
    private val trackRepository: TrackRepository,
    private val driveRepository: DriveRepository,
    private val playbackConnection: PlaybackConnection,
    private val mediaCache: MediaCache,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    /** Auth phase plus the incidental per-session fields, kept in one flow. */
    private data class Session(
        val auth: AuthPhase = AuthPhase.SignedOut,
        val email: String? = null,
        val isRefreshing: Boolean = false,
        val refreshError: String? = null,
        /** No connectivity. Assumed online until the monitor says otherwise. */
        val isOffline: Boolean = false,
    )

    private sealed interface AuthPhase {
        data object SignedOut : AuthPhase
        data object Authorizing : AuthPhase
        data class ConsentRequired(val pendingIntent: PendingIntent) : AuthPhase
        data class Authorized(val accessToken: String) : AuthPhase
        data class Failed(val message: String) : AuthPhase
    }

    private val session = MutableStateFlow(Session())

    /**
     * Transient, so it is not persisted with the sort order: a search is a momentary
     * lookup, and restoring one on relaunch would look like a broken library.
     */
    private val searchQuery = MutableStateFlow("")

    /**
     * Cached-only view (v0.7). Transient like [searchQuery] — a filter that narrows the
     * library to downloaded tracks, most useful offline, but not a setting worth
     * restoring on relaunch.
     */
    private val showDownloadedOnly = MutableStateFlow(false)

    /**
     * One-shot user-facing messages surfaced as a snackbar — currently the refusal to
     * play an undownloaded track offline. A StateFlow the UI clears via [onSnackbarShown]
     * once shown, so a config change mid-display does not re-raise it.
     */
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage

    private data class Inputs(
        val session: Session,
        val scope: LibraryScope?,
        val sortOrder: SortOrder,
        val searchQuery: String,
    )

    /** The two figures behind the storage summary, carried together through the combine. */
    private data class Storage(val downloadedBytes: Long, val freeSpaceBytes: Long)

    /**
     * Total downloaded size paired with current free space. Driven by the downloaded
     * total — free space is re-read on the same IO hop each time a download completes or
     * a cached file is dropped, which is exactly when the pair changes.
     */
    private val storage: kotlinx.coroutines.flow.Flow<Storage> =
        trackRepository.observeDownloadedTotalBytes()
            .map { downloaded -> Storage(downloaded, mediaCache.freeSpaceBytes()) }
            .flowOn(Dispatchers.IO)

    /**
     * Settings-screen state (spec §6). The downloaded-total flow is only a change
     * trigger here — a download finishing or an eviction firing re-reads the true cache
     * footprint and device free space. Read on IO because both touch the disk.
     */
    val settings: StateFlow<SettingsUiState> = combine(
        session.map { it.email }.distinctUntilChanged(),
        settingsStore.cacheQuota,
        trackRepository.observeDownloadedTotalBytes(),
    ) { email, quota, _ -> email to quota }
        .map { (email, quota) ->
            SettingsUiState(
                email = email,
                quota = quota,
                usedCacheBytes = mediaCache.usedBytes(),
                deviceFreeBytes = mediaCache.freeSpaceBytes(),
            )
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = SettingsUiState(null, CacheQuota.DEFAULT, 0L, 0L),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LibraryUiState> = combine(
        session,
        settingsStore.libraryScope,
        settingsStore.sortOrder,
        searchQuery,
    ) { session, scope, sortOrder, searchQuery ->
        Inputs(session, scope, sortOrder, searchQuery)
    }
        .flatMapLatest { (session, scope, sortOrder, searchQuery) ->
            when (val phase = session.auth) {
                AuthPhase.SignedOut -> flowOf(LibraryUiState.SignedOut)
                AuthPhase.Authorizing -> flowOf(LibraryUiState.Loading)
                is AuthPhase.ConsentRequired ->
                    flowOf(LibraryUiState.ConsentRequired(phase.pendingIntent))

                is AuthPhase.Failed -> flowOf(LibraryUiState.Failed(phase.message))

                is AuthPhase.Authorized ->
                    if (scope == null) {
                        flowOf(LibraryUiState.NeedsFolderSelection)
                    } else {
                        // Rendered straight from the local index, so this emits
                        // immediately even while a refresh is still in flight, and
                        // re-queries per keystroke without touching the network.
                        combine(
                            trackRepository.observeTracks(scope, sortOrder, searchQuery),
                            trackRepository.observeDownloadedIds(),
                            storage,
                            showDownloadedOnly,
                        ) { tracks, downloadedIds, storage, downloadedOnly ->
                            // The cached-only view filters here rather than in SQL: the
                            // download set lives in a separate flow on its own cadence,
                            // so joining it into the sorted query would re-run that query
                            // every time a download finished.
                            val visibleTracks =
                                if (downloadedOnly) tracks.filter { it.id in downloadedIds }
                                else tracks

                            LibraryUiState.Content(
                                email = session.email,
                                scope = scope,
                                sortOrder = sortOrder,
                                tracks = visibleTracks,
                                downloadedTrackIds = downloadedIds,
                                downloadedBytes = storage.downloadedBytes,
                                freeSpaceBytes = storage.freeSpaceBytes,
                                searchQuery = searchQuery,
                                isRefreshing = session.isRefreshing,
                                isOffline = session.isOffline,
                                showDownloadedOnly = downloadedOnly,
                                refreshError = session.refreshError,
                            )
                        }
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LibraryUiState.Loading,
        )

    /**
     * Playback progress, kept out of [state] on purpose: it changes twice a second,
     * and folding it into the library state would re-emit the whole track list at that
     * rate.
     */
    val playback: StateFlow<PlaybackState> = playbackConnection.state

    /** Just enough of [playback] to highlight a row, so the list settles between tracks. */
    val playingTrackId: StateFlow<String?> = playbackConnection.state
        .map { it.trackId }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = null,
        )

    init {
        resumeSessionIfAlreadyGranted()
        refreshWhenTokenOrScopeChanges()
        keepQueueMatchingTheVisibleList()
        observeConnectivity()
    }

    /** Folds connectivity into the session so the library can render an offline banner. */
    private fun observeConnectivity() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online ->
                session.update { it.copy(isOffline = !online) }
            }
        }
    }

    /**
     * Keeps skip and shuffle moving through the library *as currently sorted and
     * filtered* (FR-3.4.3), rather than through a snapshot taken when playback began.
     *
     * Re-sorting or narrowing the search therefore re-points the queue.
     * [PlaybackConnection.updateQueue] does it without interrupting audio, and ignores
     * a list that no longer contains the playing track.
     *
     * Subscribed only while a track is loaded — which is exactly when a queue exists to
     * correct. Collecting [state] unconditionally would give it a permanent subscriber
     * and quietly cancel out its `WhileSubscribed` sharing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun keepQueueMatchingTheVisibleList() {
        viewModelScope.launch {
            playbackConnection.state
                .map { it.hasTrack }
                .distinctUntilChanged()
                .flatMapLatest { hasTrack ->
                    if (!hasTrack) {
                        emptyFlow()
                    } else {
                        state
                            .map { (it as? LibraryUiState.Content)?.tracks.orEmpty() }
                            .distinctUntilChanged()
                    }
                }
                .collect { tracks -> playbackConnection.updateQueue(tracks) }
        }
    }

    /**
     * On launch, try for a token silently. If the scope has not been granted yet
     * this does nothing — consent is only shown when the user taps sign-in, never
     * unprompted on a cold start.
     */
    private fun resumeSessionIfAlreadyGranted() {
        viewModelScope.launch {
            val result = runCatching { auth.authorize() }.getOrNull()
            if (result is AuthResult.Authorized) {
                onAuthorized(result.accessToken)
            } else {
                session.update { it.copy(auth = AuthPhase.SignedOut) }
            }
        }
    }

    /**
     * Refreshes on a new token or a new folder, but *not* on a sort change — sorting
     * happens in SQL against data already indexed. `collectLatest` cancels an
     * in-flight fetch when the user switches folders mid-refresh.
     */
    private fun refreshWhenTokenOrScopeChanges() {
        viewModelScope.launch {
            combine(
                session.map { it.auth }.distinctUntilChanged(),
                settingsStore.libraryScope,
            ) { phase, scope -> phase to scope }
                .collectLatest { (phase, scope) ->
                    if (phase is AuthPhase.Authorized && scope != null) {
                        refreshNow(phase.accessToken, scope)
                    }
                }
        }
    }

    fun signIn() {
        session.update { it.copy(auth = AuthPhase.Authorizing) }
        viewModelScope.launch {
            try {
                when (val result = auth.authorize()) {
                    is AuthResult.Authorized -> onAuthorized(result.accessToken)
                    is AuthResult.ConsentRequired -> session.update {
                        it.copy(auth = AuthPhase.ConsentRequired(result.pendingIntent))
                    }
                }
            } catch (e: Exception) {
                session.update { it.copy(auth = AuthPhase.Failed(authErrorMessage(e))) }
            }
        }
    }

    /**
     * Result of the consent screen. [ok] is the activity result code: a plain user
     * cancel returns to the sign-in screen silently, but a failure that came back OK
     * — or an [ApiException] that isn't a cancel — is surfaced as [AuthPhase.Failed]
     * rather than swallowed. A cert/SHA-1 mismatch is exactly that case: the picker
     * runs, then authorization is denied with no token, which used to look like a
     * silent bounce back to sign-in.
     */
    fun onConsentResult(ok: Boolean, data: Intent?) {
        val attempt = runCatching { auth.onConsentResult(data) }
        attempt.getOrNull()?.let { token ->
            viewModelScope.launch { onAuthorized(token) }
            return
        }

        val error = attempt.exceptionOrNull()
        val cancelled = (!ok && error == null) ||
            (error is ApiException && error.statusCode == CommonStatusCodes.CANCELED)

        session.update {
            if (cancelled) {
                it.copy(auth = AuthPhase.SignedOut)
            } else {
                it.copy(auth = AuthPhase.Failed(authErrorMessage(error)))
            }
        }
    }

    fun signOut() {
        // Before the token is dropped: the stream would fail on its next range
        // request anyway, and a notification still ticking after sign-out — now that
        // the service outlives this screen — would be worse than broken.
        playbackConnection.stopPlayback()
        auth.clearToken()
        searchQuery.value = ""
        session.value = Session()
    }

    fun retry() = signIn()

    fun onScopeSelected(scope: LibraryScope) {
        // A query typed against the previous folder is meaningless in the new one.
        searchQuery.value = ""
        viewModelScope.launch { settingsStore.setLibraryScope(scope) }
    }

    /** Called on every keystroke; filtering is a local SQL prefix match. */
    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun setSortField(field: SortField) {
        viewModelScope.launch {
            val current = settingsStore.sortOrder.first()
            if (current.field != field) settingsStore.setSortOrder(current.copy(field = field))
        }
    }

    fun toggleSortDirection() {
        viewModelScope.launch {
            val current = settingsStore.sortOrder.first()
            settingsStore.setSortOrder(current.copy(direction = current.direction.toggled()))
        }
    }

    /** Manual re-scan of the current folder. */
    fun refresh() {
        val phase = session.value.auth
        if (phase !is AuthPhase.Authorized) return
        viewModelScope.launch {
            val scope = settingsStore.libraryScope.first() ?: return@launch
            refreshNow(phase.accessToken, scope)
        }
    }

    /**
     * Streams the tapped track (FR-3.4.1), handing the player the whole visible list so
     * skip has somewhere to go.
     *
     * Requires a live token: the service's `DataSource` reads it synchronously, so a
     * track tapped before authorization finished would 401 on its very first request.
     */
    fun onTrackClick(track: TrackEntity) {
        if (session.value.auth !is AuthPhase.Authorized) return
        val content = state.value as? LibraryUiState.Content ?: return

        // Fail the play up front when offline and the track is not on disk (spec §5):
        // streaming it would 404 the network layer and surface a cryptic playback error
        // seconds later. A clear refusal now is the honest outcome.
        if (content.isOffline && track.id !in content.downloadedTrackIds) {
            _snackbarMessage.value =
                "\"${track.name}\" isn't downloaded, and you're offline."
            return
        }

        playbackConnection.play(track = track, queue = content.tracks)
    }

    /** Cached-only view toggle (v0.7). */
    fun toggleDownloadedOnly() {
        showDownloadedOnly.update { !it }
    }

    fun onSnackbarShown() {
        _snackbarMessage.value = null
    }

    /**
     * Deletes one track's downloaded bytes (FR-3.2 cache management).
     *
     * Stops playback first when it is the loaded track: its bytes are about to go, and
     * clearing them under an active read would either force a silent re-download or, when
     * offline, fail the stream. `stopPlayback` runs on the main thread (MediaController's
     * requirement); `remove` then hops to IO on its own.
     */
    fun clearTrack(track: TrackEntity) {
        viewModelScope.launch {
            if (playback.value.trackId == track.id) playbackConnection.stopPlayback()
            mediaCache.remove(track.id)
        }
    }

    /**
     * Empties the whole download cache. Always stops playback: every cached file is being
     * removed, so leaving the bar playing a track whose badge just vanished would be
     * incoherent — and the loaded track's bytes may be among those going.
     */
    fun clearCache() {
        viewModelScope.launch {
            playbackConnection.stopPlayback()
            mediaCache.clearAll()
        }
    }

    /**
     * Changes the cache ceiling (FR-3.2.2). Persisting it is all that is needed — the
     * [MediaCache] collector reacts to the new value and evicts down to it if the cache
     * already exceeds it.
     */
    fun setCacheQuota(quota: CacheQuota) {
        viewModelScope.launch { settingsStore.setCacheQuota(quota) }
    }

    fun togglePlayPause() = playbackConnection.togglePlayPause()

    fun seekTo(positionMs: Long) = playbackConnection.seekTo(positionMs)

    fun skipToNext() = playbackConnection.skipToNext()

    fun skipToPrevious() = playbackConnection.skipToPrevious()

    /**
     * FR-3.4.2. Applied to the session immediately so the toggle never lags, and
     * persisted so the choice survives a relaunch — [PlaybackService] reads the stored
     * value back when a media button revives it with no UI attached.
     */
    fun toggleRepeatOne() {
        val enabled = !playback.value.repeatOne
        playbackConnection.setRepeatOne(enabled)
        viewModelScope.launch { settingsStore.setRepeatOne(enabled) }
    }

    /** FR-3.4.3, persisted on the same terms as [toggleRepeatOne]. */
    fun toggleShuffle() {
        val enabled = !playback.value.shuffle
        playbackConnection.setShuffle(enabled)
        viewModelScope.launch { settingsStore.setShuffle(enabled) }
    }

    /**
     * Releases the binding to the session, not the player. Audio deliberately survives
     * this — the service holds it, and the notification stays live so the user can get
     * back or stop it.
     */
    override fun onCleared() {
        super.onCleared()
        playbackConnection.release()
    }

    /**
     * Turns an authorization failure into something the user can act on. A
     * [CommonStatusCodes.DEVELOPER_ERROR] almost always means this build's signing
     * certificate SHA-1 (or package name) isn't registered in the Google Cloud OAuth
     * client — the single most common cause of "the picker runs, then it bounces back
     * to sign-in". Everything actionable points at SETUP.md.
     */
    private fun authErrorMessage(error: Throwable?): String = when {
        error is ApiException && error.statusCode == CommonStatusCodes.DEVELOPER_ERROR ->
            "Sign-in is misconfigured: this build's signing certificate (SHA-1) is not " +
                "registered in the Google Cloud OAuth client, or the package name doesn't " +
                "match. See SETUP.md."

        error is ApiException ->
            "Google sign-in failed (code ${error.statusCode}). If it keeps returning to " +
                "this screen, check the SHA-1 and test-user setup in SETUP.md."

        error is IOException ->
            "Couldn't reach Google. Check your connection and try again."

        else -> error?.message ?: "Sign-in didn't complete. See SETUP.md if it persists."
    }

    private suspend fun onAuthorized(accessToken: String) {
        session.update { it.copy(auth = AuthPhase.Authorized(accessToken)) }

        // Advisory only — a failure here must not block the library.
        val email = runCatching { driveRepository.currentUserEmail(accessToken) }.getOrNull()
        session.update { it.copy(email = email) }
    }

    private suspend fun refreshNow(accessToken: String, scope: LibraryScope) {
        session.update { it.copy(isRefreshing = true, refreshError = null) }
        try {
            val indexedIds =
                trackRepository.refresh(accessToken, scope, settingsStore.sortOrder.first())

            // Recovers badges for anything downloaded in an earlier session. The service
            // keeps the mirror current while tracks play; this is the cold-start path,
            // and it matters most right after a destructive schema change, which wipes
            // the mirror without deleting a single cached byte.
            //
            // Deliberately driven by the refresh rather than by the visible track list:
            // reconciling writes to the same table the list observes, and hanging it off
            // that flow would feed back into itself.
            mediaCache.reconcile(indexedIds)

            session.update { it.copy(isRefreshing = false, refreshError = null) }
        } catch (e: HttpException) {
            when (e.code()) {
                401 -> {
                    // Token rejected — the grant was revoked from the Google Account page
                    // while the app held a cached token. Only 401 forces re-sign-in.
                    auth.clearToken()
                    session.update {
                        it.copy(
                            auth = AuthPhase.Failed("Drive access was revoked. Sign in again."),
                            isRefreshing = false,
                        )
                    }
                }

                403 -> {
                    // Past RetryInterceptor's backoff, so either sustained rate-limiting
                    // or an access denial — neither is fixed by signing in again, and the
                    // indexed list is still valid, so this is a banner, not a wipe.
                    session.update {
                        it.copy(
                            isRefreshing = false,
                            refreshError = "Drive is limiting requests or denied access. " +
                                "Try again shortly.",
                        )
                    }
                }

                else -> {
                    session.update {
                        it.copy(
                            isRefreshing = false,
                            refreshError = "Drive returned HTTP ${e.code()}.",
                        )
                    }
                }
            }
        } catch (e: IOException) {
            // The indexed list is still valid offline, so this is a banner, not a
            // full-screen failure.
            session.update {
                it.copy(isRefreshing = false, refreshError = "Offline — showing the last scan.")
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Pass an application context — this factory outlives any Activity. */
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    auth = ServiceLocator.authManager(appContext),
                    settingsStore = ServiceLocator.settingsStore(appContext),
                    trackRepository = ServiceLocator.trackRepository(appContext),
                    driveRepository = ServiceLocator.driveRepository,
                    // Not from ServiceLocator: the binding is owned and released by
                    // the ViewModel. What it binds to — the service and its player —
                    // is what lives for the process lifetime now.
                    playbackConnection = PlaybackConnection(appContext),
                    mediaCache = ServiceLocator.mediaCache(appContext),
                    networkMonitor = ServiceLocator.networkMonitor(appContext),
                )
            }
        }
    }
}
