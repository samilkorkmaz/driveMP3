package com.drivemp3.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivemp3.player.BuildConfig
import com.drivemp3.player.R
import com.drivemp3.player.data.local.TrackEntity
import com.drivemp3.player.model.LibraryScope
import com.drivemp3.player.model.SortDirection
import com.drivemp3.player.model.SortField
import com.drivemp3.player.model.SortOrder
import com.drivemp3.player.playback.PlaybackState

/**
 * @param playback read through a lambda rather than passed by value so the position
 *   ticking twice a second only recomposes the now-playing bar, not the track list.
 * @param playingTrackId changes once per track, so highlighting a row is cheap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    playback: () -> PlaybackState,
    playingTrackId: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onChangeFolder: () -> Unit,
    onSortFieldSelected: (SortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTrackClick: (TrackEntity) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleRepeatOne: () -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_name_versioned, BuildConfig.VERSION_NAME))
                },
                actions = {
                    if (state is LibraryUiState.Content) {
                        IconButton(onClick = onRefresh, enabled = !state.isRefreshing) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.refresh),
                            )
                        }
                        OverflowMenu(
                            onChangeFolder = onChangeFolder,
                            onSignOut = onSignOut,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NowPlayingBar(
                state = playback(),
                onTogglePlayPause = onTogglePlayPause,
                onSeek = onSeek,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onToggleRepeatOne = onToggleRepeatOne,
                onToggleShuffle = onToggleShuffle,
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when (state) {
                LibraryUiState.SignedOut -> MessageWithAction(
                    message = stringResource(R.string.signed_out_message),
                    actionLabel = stringResource(R.string.sign_in),
                    onAction = onSignIn,
                    modifier = Modifier.align(Alignment.Center),
                )

                // The consent screen is already being launched; keep the spinner up.
                LibraryUiState.Loading,
                is LibraryUiState.ConsentRequired,
                LibraryUiState.NeedsFolderSelection,
                    -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is LibraryUiState.Content -> LibraryContent(
                    state = state,
                    playingTrackId = playingTrackId,
                    onChangeFolder = onChangeFolder,
                    onSortFieldSelected = onSortFieldSelected,
                    onToggleSortDirection = onToggleSortDirection,
                    onSearchQueryChange = onSearchQueryChange,
                    onTrackClick = onTrackClick,
                )

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
private fun OverflowMenu(
    onChangeFolder: () -> Unit,
    onSignOut: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = stringResource(R.string.more_options),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.change_folder)) },
            onClick = {
                expanded = false
                onChangeFolder()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sign_out)) },
            onClick = {
                expanded = false
                onSignOut()
            },
        )
    }
}

@Composable
private fun LibraryContent(
    state: LibraryUiState.Content,
    playingTrackId: String?,
    onChangeFolder: () -> Unit,
    onSortFieldSelected: (SortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTrackClick: (TrackEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScopeHeader(state = state, onChangeFolder = onChangeFolder)

        SearchField(
            query = state.searchQuery,
            onQueryChange = onSearchQueryChange,
        )

        SortBar(
            sortOrder = state.sortOrder,
            onSortFieldSelected = onSortFieldSelected,
            onToggleSortDirection = onToggleSortDirection,
        )

        if (state.isRefreshing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        state.refreshError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        HorizontalDivider()

        if (state.tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    // Distinguishes "this folder has no MP3s" from "your search
                    // matched nothing", which need different user responses.
                    text = if (state.searchQuery.isBlank()) {
                        stringResource(R.string.empty_message)
                    } else {
                        stringResource(R.string.no_search_matches, state.searchQuery.trim())
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@Column
        }

        TrackList(
            tracks = state.tracks,
            downloadedTrackIds = state.downloadedTrackIds,
            playingTrackId = playingTrackId,
            onTrackClick = onTrackClick,
        )
    }
}

/** Incremental prefix search over the local index — no request per keystroke. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = stringResource(R.string.clear_search),
                    )
                }
            }
        },
    )
}

@Composable
private fun ScopeHeader(
    state: LibraryUiState.Content,
    onChangeFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scopeLabel = when (val scope = state.scope) {
        LibraryScope.AllDrive -> stringResource(R.string.all_of_my_drive)
        is LibraryScope.Folder -> scope.folderName
    }

    ListItem(
        modifier = modifier,
        overlineContent = state.email?.let { email -> { Text(email) } },
        headlineContent = {
            Text(scopeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(pluralStringResource(R.plurals.track_count, state.tracks.size, state.tracks.size))
        },
        trailingContent = {
            androidx.compose.material3.TextButton(onClick = onChangeFolder) {
                Text(stringResource(R.string.change_folder))
            }
        },
    )
}

/** The sort row from spec section 4: field selector plus a direction toggle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBar(
    sortOrder: SortOrder,
    onSortFieldSelected: (SortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.sort_label),
            style = MaterialTheme.typography.labelLarge,
        )

        FilterChip(
            selected = sortOrder.field == SortField.CreatedTime,
            onClick = { onSortFieldSelected(SortField.CreatedTime) },
            label = { Text(stringResource(R.string.sort_by_upload_date)) },
        )
        FilterChip(
            selected = sortOrder.field == SortField.Name,
            onClick = { onSortFieldSelected(SortField.Name) },
            label = { Text(stringResource(R.string.sort_by_name)) },
        )

        val ascending = sortOrder.direction == SortDirection.Ascending
        IconButton(onClick = onToggleSortDirection) {
            Icon(
                imageVector = if (ascending) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = stringResource(
                    if (ascending) R.string.sort_ascending else R.string.sort_descending
                ),
            )
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<TrackEntity>,
    downloadedTrackIds: Set<String>,
    playingTrackId: String?,
    onTrackClick: (TrackEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items = tracks, key = { "${it.scopeId}:${it.id}" }) { track ->
            val isCurrent = track.id == playingTrackId
            val isDownloaded = track.id in downloadedTrackIds

            ListItem(
                modifier = Modifier.clickable { onTrackClick(track) },
                leadingContent = { DownloadedBadge(isDownloaded = isDownloaded) },
                headlineContent = {
                    Text(
                        text = track.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // Colour, not an icon: the leading slot belongs to the
                        // "Downloaded" badge.
                        color = if (isCurrent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Unspecified
                        },
                    )
                },
                supportingContent = {
                    Text(
                        "${formatCreatedTime(track.createdTimeEpochMs)}  ·  " +
                            formatSize(track.sizeBytes)
                    )
                },
                trailingContent = if (!isCurrent) null else {
                    {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.current_track),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }
    }
}

/**
 * FR-3.2.4's "Downloaded" indicator: shown only when the whole file is on disk.
 *
 * Occupies its slot whether or not it is lit — an icon that appears and disappears
 * would shift every title sideways as tracks finish downloading. Undownloaded rows get
 * a same-sized spacer instead.
 */
@Composable
private fun DownloadedBadge(isDownloaded: Boolean, modifier: Modifier = Modifier) {
    if (!isDownloaded) {
        Spacer(modifier = modifier.size(BadgeSize))
        return
    }
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = stringResource(R.string.downloaded),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(BadgeSize),
    )
}

/** Material's default icon size; the spacer has to match it exactly to align rows. */
private val BadgeSize = 24.dp
