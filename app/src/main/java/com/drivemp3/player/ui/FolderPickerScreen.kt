package com.drivemp3.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivemp3.player.R
import com.drivemp3.player.model.LibraryScope

/**
 * Walks the Drive folder tree so the user can scope the library (FR-3.1.2).
 *
 * [onDismiss] is null when a scope has never been chosen, which makes the picker
 * mandatory: there is no library to go back to yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPickerScreen(
    state: FolderPickerUiState,
    onOpenFolder: (FolderRef) -> Unit,
    onNavigateUp: () -> Unit,
    onScopeSelected: (LibraryScope) -> Unit,
    onRetry: () -> Unit,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true) {
        if (!state.isAtRoot) onNavigateUp() else onDismiss?.invoke()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.currentFolder.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    when {
                        !state.isAtRoot -> IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.navigate_up),
                            )
                        }

                        onDismiss != null -> IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.close),
                            )
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            Button(
                onClick = {
                    onScopeSelected(
                        LibraryScope.Folder(
                            folderId = state.currentFolder.id,
                            folderName = state.currentFolder.name,
                        )
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.use_this_folder, state.currentFolder.name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider()

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.error != null -> MessageWithAction(
                        message = state.error,
                        actionLabel = stringResource(R.string.retry),
                        onAction = onRetry,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> FolderList(
                        state = state,
                        onOpenFolder = onOpenFolder,
                        onScopeSelected = onScopeSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderList(
    state: FolderPickerUiState,
    onOpenFolder: (FolderRef) -> Unit,
    onScopeSelected: (LibraryScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        // Offered only at the root: it is a whole-account search, not a folder.
        if (state.isAtRoot) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.all_of_my_drive)) },
                    supportingContent = {
                        Text(stringResource(R.string.all_of_my_drive_summary))
                    },
                    modifier = Modifier.clickable { onScopeSelected(LibraryScope.AllDrive) },
                )
                HorizontalDivider()
            }
        }

        if (state.subfolders.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_subfolders),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@LazyColumn
        }

        items(items = state.subfolders, key = FolderRef::id) { folder ->
            ListItem(
                headlineContent = {
                    Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { onOpenFolder(folder) },
            )
        }
    }
}
