package com.drivemp3.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drivemp3.player.ui.FolderPickerScreen
import com.drivemp3.player.ui.FolderPickerViewModel
import com.drivemp3.player.ui.LibraryScreen
import com.drivemp3.player.ui.LibraryUiState
import com.drivemp3.player.ui.LibraryViewModel
import com.drivemp3.player.ui.theme.DriveMp3Theme

class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels {
        LibraryViewModel.factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DriveMp3Theme {
                val libraryState by libraryViewModel.state.collectAsStateWithLifecycle()
                val playback by libraryViewModel.playback.collectAsStateWithLifecycle()
                val playingTrackId by
                    libraryViewModel.playingTrackId.collectAsStateWithLifecycle()

                val consentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        libraryViewModel.onConsentResult(result.data)
                    } else {
                        libraryViewModel.onConsentCancelled()
                    }
                }

                // Keyed on the state instance, so the consent screen is launched
                // once per request rather than on every recomposition.
                LaunchedEffect(libraryState) {
                    val current = libraryState
                    if (current is LibraryUiState.ConsentRequired) {
                        consentLauncher.launch(
                            IntentSenderRequest.Builder(current.pendingIntent).build()
                        )
                    }
                }

                // Two screens, so navigation is a boolean rather than a nav graph.
                // The picker is forced open when no scope has ever been chosen.
                var isChangingFolder by rememberSaveable { mutableStateOf(false) }
                val mustChooseFolder = libraryState is LibraryUiState.NeedsFolderSelection

                if (isChangingFolder || mustChooseFolder) {
                    val pickerViewModel: FolderPickerViewModel = viewModel(
                        factory = FolderPickerViewModel.factory(applicationContext)
                    )
                    val pickerState by pickerViewModel.state.collectAsStateWithLifecycle()

                    FolderPickerScreen(
                        state = pickerState,
                        onOpenFolder = pickerViewModel::openFolder,
                        onNavigateUp = { pickerViewModel.navigateUp() },
                        onScopeSelected = { scope ->
                            libraryViewModel.onScopeSelected(scope)
                            isChangingFolder = false
                        },
                        onRetry = pickerViewModel::retry,
                        // Not dismissible until a scope exists — there is no
                        // library to return to yet.
                        onDismiss = if (mustChooseFolder) null else {
                            { isChangingFolder = false }
                        },
                    )
                } else {
                    LibraryScreen(
                        state = libraryState,
                        // Passed as a lambda so the twice-a-second position update
                        // recomposes only the now-playing bar that reads it.
                        playback = { playback },
                        playingTrackId = playingTrackId,
                        onSignIn = libraryViewModel::signIn,
                        onSignOut = libraryViewModel::signOut,
                        onRetry = libraryViewModel::retry,
                        onRefresh = libraryViewModel::refresh,
                        onChangeFolder = { isChangingFolder = true },
                        onSortFieldSelected = libraryViewModel::setSortField,
                        onToggleSortDirection = libraryViewModel::toggleSortDirection,
                        onSearchQueryChange = libraryViewModel::onSearchQueryChange,
                        onTrackClick = libraryViewModel::onTrackClick,
                        onTogglePlayPause = libraryViewModel::togglePlayPause,
                        onSeek = libraryViewModel::seekTo,
                    )
                }
            }
        }
    }
}
