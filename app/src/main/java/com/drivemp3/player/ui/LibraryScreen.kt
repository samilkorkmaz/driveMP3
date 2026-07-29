package com.drivemp3.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivemp3.player.R
import com.drivemp3.player.data.DriveFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (state is LibraryUiState.Content) {
                        TextButton(onClick = onSignOut) {
                            Text(stringResource(R.string.sign_out))
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (state) {
                LibraryUiState.SignedOut -> SignedOutContent(
                    onSignIn = onSignIn,
                    modifier = Modifier.align(Alignment.Center),
                )

                // The consent screen is already being launched; keep the spinner up.
                LibraryUiState.Loading,
                is LibraryUiState.ConsentRequired,
                    -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is LibraryUiState.Content -> FileList(state)

                is LibraryUiState.Failed -> MessageWithAction(
                    message = state.message,
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRetry,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun SignedOutContent(onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    MessageWithAction(
        message = stringResource(R.string.signed_out_message),
        actionLabel = stringResource(R.string.sign_in),
        onAction = onSignIn,
        modifier = modifier,
    )
}

@Composable
private fun MessageWithAction(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun FileList(state: LibraryUiState.Content, modifier: Modifier = Modifier) {
    if (state.files.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.empty_message),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        state.email?.let { email ->
            item {
                ListItem(
                    headlineContent = {
                        Text(email, style = MaterialTheme.typography.bodyMedium)
                    },
                    supportingContent = {
                        Text("${state.files.size} MP3 files")
                    },
                )
                HorizontalDivider()
            }
        }

        items(items = state.files, key = DriveFile::id) { file ->
            ListItem(
                headlineContent = { Text(file.name) },
                supportingContent = {
                    Text("${formatCreatedTime(file.createdTime)}  ·  ${formatSize(file.size)}")
                },
            )
        }
    }
}
