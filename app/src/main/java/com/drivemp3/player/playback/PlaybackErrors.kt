package com.drivemp3.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import java.io.IOException

/**
 * Failure classification shared by the two sides of the session boundary.
 *
 * [PlaybackService] owns the player and decides whether to retry; the UI's
 * [PlaybackConnection] sees the same [PlaybackException] and has to reach the same
 * verdict, or it would show an error over a recovery already underway. Both call these
 * predicates so there is one definition of "retryable" rather than two that drift.
 */

private fun Throwable.causeChain(): Sequence<Throwable> = generateSequence(this) { it.cause }

/** The HTTP status Drive refused the read with, if the failure was one. */
@OptIn(UnstableApi::class)
fun PlaybackException.httpStatusCode(): Int? = causeChain()
    .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
    .firstOrNull()
    ?.responseCode

/**
 * Whether a fresh access token might fix this.
 *
 * 401 is the expired token. 403 is included because a revoked or downgraded grant
 * surfaces that way too, and one silent re-authorization is cheap; a 403 that was
 * really a download-quota rejection just fails the retry and reports itself normally.
 */
fun PlaybackException.isAuthFailure(): Boolean =
    httpStatusCode().let { it == 401 || it == 403 }

/**
 * Ordered most specific first. An HTTP status is checked ahead of [IOException]
 * because Media3's response-code exception *is* one, and "check your connection"
 * would be wrong advice for a 403.
 */
fun PlaybackException.userMessage(): String {
    httpStatusCode()?.let { status ->
        return if (isAuthFailure()) {
            "Drive refused this track. Sign in again."
        } else {
            "Drive returned HTTP $status."
        }
    }
    return if (causeChain().any { it is IOException }) {
        "Could not reach Drive. Check your connection."
    } else {
        "This track could not be played."
    }
}
