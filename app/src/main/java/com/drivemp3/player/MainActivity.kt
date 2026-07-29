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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivemp3.player.ui.LibraryScreen
import com.drivemp3.player.ui.LibraryUiState
import com.drivemp3.player.ui.LibraryViewModel
import com.drivemp3.player.ui.theme.DriveMp3Theme

class MainActivity : ComponentActivity() {

    private val viewModel: LibraryViewModel by viewModels {
        LibraryViewModel.factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DriveMp3Theme {
                val state by viewModel.state.collectAsStateWithLifecycle()

                val consentLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartIntentSenderForResult()
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        viewModel.onConsentResult(result.data)
                    } else {
                        viewModel.onConsentCancelled()
                    }
                }

                // Keyed on the state instance, so the consent screen is launched
                // once per request rather than on every recomposition.
                LaunchedEffect(state) {
                    val current = state
                    if (current is LibraryUiState.ConsentRequired) {
                        consentLauncher.launch(
                            IntentSenderRequest.Builder(current.pendingIntent).build()
                        )
                    }
                }

                LibraryScreen(
                    state = state,
                    onSignIn = viewModel::signIn,
                    onSignOut = viewModel::signOut,
                    onRetry = viewModel::retry,
                )
            }
        }
    }
}
