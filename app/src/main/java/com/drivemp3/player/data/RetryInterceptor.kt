package com.drivemp3.player.data

import okhttp3.Interceptor
import okhttp3.Response
import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential-backoff retry for Drive's metadata calls (spec §5 risk register, v0.7).
 *
 * Drive rate-limits per-user and per-project, and a folder browse or a rescan can fire
 * several `files.list` calls in quick succession — exactly the pattern that trips
 * `rateLimitExceeded`. Google's guidance for these is to back off and retry rather than
 * surface the failure, so this does, with jittered exponential delays.
 *
 * Retried: 429, and 5xx, which are transient by definition; and 403 **only** when its
 * body names a rate-limit reason. A 403 is otherwise a real permission denial — retrying
 * it would just delay an error the user needs to see — so it is inspected via
 * [Response.peekBody], which reads the reason without consuming the body Retrofit will
 * later parse. 401 is deliberately not retried: an expired token is refreshed by the
 * caller, not waited out.
 *
 * This covers the Retrofit/OkHttp metadata path only. Streaming runs through ExoPlayer's
 * own `DataSource`, which applies its own load-error retry policy.
 */
class RetryInterceptor(
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val baseDelayMs: Long = DEFAULT_BASE_DELAY_MS,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var response = chain.proceed(chain.request())

        while (attempt < maxRetries && response.isRetryable()) {
            // The body must be released before the connection can be reused for the
            // retry; nothing here reads it (peekBody in isRetryable does not consume it).
            response.close()
            Thread.sleep(backoffMs(attempt))
            attempt++
            response = chain.proceed(chain.request())
        }

        return response
    }

    private fun Response.isRetryable(): Boolean = when (code) {
        429, 500, 502, 503, 504 -> true
        403 -> isRateLimited()
        else -> false
    }

    /**
     * A 403 is retryable only if it is a throttle rather than a permission denial.
     * `peekBody` snapshots the first bytes without consuming the source, so the real
     * body still reaches Retrofit's error parsing untouched.
     */
    private fun Response.isRateLimited(): Boolean {
        val body = peekBody(PEEK_BYTES).string()
        return body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded")
    }

    /** `baseDelay * 2^attempt` with up to a full extra interval of jitter, so a burst of
     *  requests that failed together do not all retry on the same beat. */
    private fun backoffMs(attempt: Int): Long {
        val exponential = baseDelayMs * 2.0.pow(attempt).toLong()
        return exponential + Random.nextLong(baseDelayMs)
    }

    private companion object {
        const val DEFAULT_MAX_RETRIES = 3
        const val DEFAULT_BASE_DELAY_MS = 500L
        const val PEEK_BYTES = 4096L
    }
}
