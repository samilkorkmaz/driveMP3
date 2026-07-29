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
import com.drivemp3.player.data.DriveFile
import com.drivemp3.player.data.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

sealed interface LibraryUiState {
    data object SignedOut : LibraryUiState
    data object Loading : LibraryUiState
    data class ConsentRequired(val pendingIntent: PendingIntent) : LibraryUiState
    data class Content(val email: String?, val files: List<DriveFile>) : LibraryUiState
    data class Failed(val message: String) : LibraryUiState
}

class LibraryViewModel(
    private val auth: DriveAuthManager,
    private val repository: DriveRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.SignedOut)
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        resumeSessionIfAlreadyGranted()
    }

    /**
     * On launch, try for a token silently. If the scope has not been granted yet
     * this does nothing — consent is only shown when the user taps sign-in, never
     * unprompted on a cold start.
     */
    private fun resumeSessionIfAlreadyGranted() {
        viewModelScope.launch {
            val result = runCatching { auth.authorize() }.getOrNull()
            if (result is AuthResult.Authorized) load(result.accessToken)
        }
    }

    fun signIn() {
        _state.value = LibraryUiState.Loading
        viewModelScope.launch {
            try {
                when (val result = auth.authorize()) {
                    is AuthResult.Authorized -> load(result.accessToken)
                    is AuthResult.ConsentRequired ->
                        _state.value = LibraryUiState.ConsentRequired(result.pendingIntent)
                }
            } catch (e: Exception) {
                _state.value = LibraryUiState.Failed(
                    e.message ?: "Could not reach Google sign-in."
                )
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        val token = runCatching { auth.onConsentResult(data) }.getOrNull()
        if (token == null) {
            _state.value = LibraryUiState.SignedOut
            return
        }
        viewModelScope.launch { load(token) }
    }

    fun onConsentCancelled() {
        _state.value = LibraryUiState.SignedOut
    }

    fun signOut() {
        auth.clearToken()
        _state.value = LibraryUiState.SignedOut
    }

    fun retry() = signIn()

    private suspend fun load(accessToken: String) {
        _state.value = LibraryUiState.Loading
        _state.value = try {
            val email = repository.currentUserEmail(accessToken)
            val files = repository.listMp3Files(accessToken)
            LibraryUiState.Content(email, files)
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                // Token rejected — most likely the grant was revoked from the
                // Google Account page while the app held a cached token.
                auth.clearToken()
                LibraryUiState.Failed("Drive access was revoked. Sign in again.")
            } else {
                LibraryUiState.Failed("Drive returned HTTP ${e.code()}.")
            }
        } catch (e: IOException) {
            LibraryUiState.Failed("Network unavailable. Check your connection.")
        }
    }

    companion object {
        /** Pass an application context — this factory outlives any Activity. */
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    auth = ServiceLocator.authManager(appContext),
                    repository = ServiceLocator.driveRepository,
                )
            }
        }
    }
}
