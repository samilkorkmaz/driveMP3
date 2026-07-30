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
import com.drivemp3.player.data.SettingsStore
import com.drivemp3.player.data.TrackRepository
import com.drivemp3.player.data.local.TrackEntity
import com.drivemp3.player.model.LibraryScope
import com.drivemp3.player.model.SortField
import com.drivemp3.player.model.SortOrder
import com.drivemp3.player.playback.PlaybackController
import com.drivemp3.player.playback.PlaybackState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
        val searchQuery: String,
        val isRefreshing: Boolean,
        /** A failed refresh: shown as a banner without discarding the indexed list. */
        val refreshError: String? = null,
    ) : LibraryUiState

    data class Failed(val message: String) : LibraryUiState
}

class LibraryViewModel(
    private val auth: DriveAuthManager,
    private val settingsStore: SettingsStore,
    private val trackRepository: TrackRepository,
    private val driveRepository: DriveRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    /** Auth phase plus the incidental per-session fields, kept in one flow. */
    private data class Session(
        val auth: AuthPhase = AuthPhase.SignedOut,
        val email: String? = null,
        val isRefreshing: Boolean = false,
        val refreshError: String? = null,
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

    private data class Inputs(
        val session: Session,
        val scope: LibraryScope?,
        val sortOrder: SortOrder,
        val searchQuery: String,
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
                        trackRepository.observeTracks(scope, sortOrder, searchQuery)
                            .map { tracks ->
                                LibraryUiState.Content(
                                    email = session.email,
                                    scope = scope,
                                    sortOrder = sortOrder,
                                    tracks = tracks,
                                    searchQuery = searchQuery,
                                    isRefreshing = session.isRefreshing,
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
    val playback: StateFlow<PlaybackState> = playbackController.state

    /** Just enough of [playback] to highlight a row, so the list settles between tracks. */
    val playingTrackId: StateFlow<String?> = playbackController.state
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
                session.update {
                    it.copy(
                        auth = AuthPhase.Failed(
                            e.message ?: "Could not reach Google sign-in."
                        )
                    )
                }
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        val token = runCatching { auth.onConsentResult(data) }.getOrNull()
        if (token == null) {
            session.update { it.copy(auth = AuthPhase.SignedOut) }
            return
        }
        viewModelScope.launch { onAuthorized(token) }
    }

    fun onConsentCancelled() {
        session.update { it.copy(auth = AuthPhase.SignedOut) }
    }

    fun signOut() {
        // Before the token is dropped: the stream would fail on its next range
        // request anyway, and a bar still ticking after sign-out looks broken.
        playbackController.stop()
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
     * Streams the tapped track (FR-3.4.1). Requires a live token: the player's
     * `DataSource` reads it synchronously, so a track tapped before authorization
     * finished would 401 on its very first request.
     */
    fun onTrackClick(track: TrackEntity) {
        if (session.value.auth !is AuthPhase.Authorized) return
        playbackController.play(trackId = track.id, trackName = track.name)
    }

    fun togglePlayPause() = playbackController.togglePlayPause()

    fun seekTo(positionMs: Long) = playbackController.seekTo(positionMs)

    /**
     * v0.3 is foreground-only, so the player dies with the screen that owns it —
     * surviving rotation, because the ViewModel does, but not the task being closed.
     * Background playback and its `MediaSessionService` are v0.4.
     */
    override fun onCleared() {
        super.onCleared()
        playbackController.release()
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
            trackRepository.refresh(accessToken, scope, settingsStore.sortOrder.first())
            session.update { it.copy(isRefreshing = false, refreshError = null) }
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                // Token rejected — most likely the grant was revoked from the
                // Google Account page while the app held a cached token.
                auth.clearToken()
                session.update {
                    it.copy(
                        auth = AuthPhase.Failed("Drive access was revoked. Sign in again."),
                        isRefreshing = false,
                    )
                }
            } else {
                session.update {
                    it.copy(isRefreshing = false, refreshError = "Drive returned HTTP ${e.code()}.")
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
                    // Not from ServiceLocator: this one is owned and released by the
                    // ViewModel, not shared for the process lifetime.
                    playbackController = PlaybackController(
                        context = appContext,
                        auth = ServiceLocator.authManager(appContext),
                    ),
                )
            }
        }
    }
}
