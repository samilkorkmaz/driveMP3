package com.drivemp3.player.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.drivemp3.player.ServiceLocator
import com.drivemp3.player.auth.AuthResult
import com.drivemp3.player.auth.DriveAuthManager
import com.drivemp3.player.data.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

data class FolderRef(val id: String, val name: String)

data class FolderPickerUiState(
    /** Root first, current folder last. Doubles as the navigation stack. */
    val breadcrumbs: List<FolderRef> = listOf(ROOT),
    val subfolders: List<FolderRef> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
) {
    val currentFolder: FolderRef get() = breadcrumbs.last()
    val isAtRoot: Boolean get() = breadcrumbs.size == 1

    companion object {
        val ROOT = FolderRef(DriveRepository.ROOT_FOLDER_ID, "My Drive")
    }
}

private val ROOT = FolderPickerUiState.ROOT

/**
 * Browses the Drive folder tree.
 *
 * Android has no native Drive folder picker — the Google Picker is a web component
 * — so the tree is walked with `files.list` queries instead. See VERSION_PLAN.md
 * section 3, v0.2.
 */
class FolderPickerViewModel(
    private val auth: DriveAuthManager,
    private val driveRepository: DriveRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FolderPickerUiState())
    val state: StateFlow<FolderPickerUiState> = _state.asStateFlow()

    init {
        loadCurrentFolder()
    }

    fun openFolder(folder: FolderRef) {
        _state.update { it.copy(breadcrumbs = it.breadcrumbs + folder, subfolders = emptyList()) }
        loadCurrentFolder()
    }

    /** Returns false when already at the root, so the caller can close the picker. */
    fun navigateUp(): Boolean {
        val current = _state.value
        if (current.isAtRoot) return false
        _state.update {
            it.copy(breadcrumbs = it.breadcrumbs.dropLast(1), subfolders = emptyList())
        }
        loadCurrentFolder()
        return true
    }

    fun retry() = loadCurrentFolder()

    private fun loadCurrentFolder() {
        val parentId = _state.value.currentFolder.id
        _state.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            try {
                val token = when (val result = auth.authorize()) {
                    is AuthResult.Authorized -> result.accessToken
                    // Reaching the picker requires an existing grant, so this only
                    // happens if it was revoked mid-session.
                    is AuthResult.ConsentRequired -> {
                        _state.update {
                            it.copy(isLoading = false, error = "Sign in again to browse folders.")
                        }
                        return@launch
                    }
                }

                val folders = driveRepository.listSubfolders(token, parentId)
                    .map { FolderRef(id = it.id, name = it.name) }

                _state.update { it.copy(subfolders = folders, isLoading = false, error = null) }
            } catch (e: HttpException) {
                _state.update {
                    it.copy(isLoading = false, error = "Drive returned HTTP ${e.code()}.")
                }
            } catch (e: IOException) {
                _state.update {
                    it.copy(isLoading = false, error = "Network unavailable. Check your connection.")
                }
            }
        }
    }

    companion object {
        fun factory(appContext: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                FolderPickerViewModel(
                    auth = ServiceLocator.authManager(appContext),
                    driveRepository = ServiceLocator.driveRepository,
                )
            }
        }
    }
}
