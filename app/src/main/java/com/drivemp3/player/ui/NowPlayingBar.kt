package com.drivemp3.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drivemp3.player.R
import com.drivemp3.player.playback.PlaybackState

/**
 * The now-playing bar from spec section 4: title, elapsed/total, scrubber, play/pause.
 *
 * Skip, loop, and shuffle are v0.4; the transport row is laid out centred so they can
 * be added either side of play/pause without moving it.
 *
 * Renders nothing until a track has been loaded — an empty bar on a fresh launch would
 * be dead space.
 */
@Composable
fun NowPlayingBar(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.hasTrack) return

    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        // Inset applied inside the Surface, not on it: the tinted background should
        // run under the navigation bar while the controls stay clear of it. The
        // activity is edge-to-edge, and Scaffold leaves bottom-bar insets to the bar.
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider()

            Text(
                text = state.trackName.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            SeekRow(state = state, onSeek = onSeek)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayPauseButton(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onClick = onTogglePlayPause,
                )
            }
        }
    }
}

/**
 * Scrubber flanked by elapsed and total time.
 *
 * While the thumb is held, the elapsed label follows the finger rather than the
 * player: the seek is only issued on release, so tracking the player would make the
 * label fight the drag.
 */
@Composable
private fun SeekRow(
    state: PlaybackState,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = state.durationMs

    // Keyed on the track, so loading a different one drops a stale drag.
    var dragFraction by remember(state.trackId) { mutableStateOf<Float?>(null) }

    val drag = dragFraction
    val fraction = drag ?: state.progress
    val elapsedMs = if (drag != null && durationMs != null) {
        (drag * durationMs).toLong()
    } else {
        state.positionMs
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = formatDuration(elapsedMs),
            style = MaterialTheme.typography.labelMedium,
        )

        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                val target = dragFraction
                dragFraction = null
                if (target != null && durationMs != null) {
                    onSeek((target * durationMs).toLong())
                }
            },
            // Seeking a fraction of an unknown duration has no meaning, and Drive has
            // not answered the ranged GET yet either.
            enabled = durationMs != null,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    isBuffering: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(onClick = onClick, modifier = modifier) {
        when {
            // Buffering replaces the glyph rather than sitting beside it, so the
            // control never changes size or shifts the row.
            isBuffering -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                // The filled button's content colour; the indicator's own default is
                // `primary`, which is this button's *background*.
                color = LocalContentColor.current,
            )

            isPlaying -> Icon(
                painter = painterResource(R.drawable.ic_pause_24),
                contentDescription = stringResource(R.string.pause),
            )

            else -> Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.play),
            )
        }
    }
}
