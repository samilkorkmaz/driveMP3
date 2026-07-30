package com.drivemp3.player.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/** Outcome of asking Play Services for a Drive access token. */
sealed interface AuthResult {
    data class Authorized(val accessToken: String) : AuthResult

    /** The user has not granted the scope yet; this intent must be launched. */
    data class ConsentRequired(val pendingIntent: PendingIntent) : AuthResult
}

/**
 * Obtains OAuth access tokens for the Drive read-only scope.
 *
 * Uses Play Services [AuthorizationRequest] rather than the deprecated
 * `GoogleSignIn` API. Note there is no separate identity step: the signed-in
 * account's email comes from Drive's own `about` endpoint, so v0.1 does not
 * need Credential Manager or ID token parsing.
 */
class DriveAuthManager(context: Context) {

    private val client = Identity.getAuthorizationClient(context.applicationContext)

    private val request = AuthorizationRequest.Builder()
        .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
        .build()

    @Volatile
    private var cachedToken: String? = null

    /**
     * Returns a usable access token, or the consent intent that must be launched
     * to obtain one. Once consent has been granted, later calls resolve silently
     * — that is what makes the session survive a cold launch.
     */
    suspend fun authorize(): AuthResult {
        cachedToken?.let { return AuthResult.Authorized(it) }

        val result = client.authorize(request).await()
        val token = result.accessToken
        val pendingIntent = result.pendingIntent

        return when {
            token != null -> {
                cachedToken = token
                AuthResult.Authorized(token)
            }
            pendingIntent != null -> AuthResult.ConsentRequired(pendingIntent)
            else -> error("Authorization returned neither an access token nor a consent intent")
        }
    }

    /**
     * The cached token without suspending, for callers that cannot: ExoPlayer builds
     * its `DataSource` on a loading thread and asks for request headers synchronously.
     * Null before the first successful [authorize].
     */
    fun currentToken(): String? = cachedToken

    /**
     * Discards the cached token and asks Play Services for a new one.
     *
     * Needed because an access token lives about an hour while a track can outlast
     * that: Drive then answers 401 mid-stream and only a fresh token recovers. Play
     * Services owns the refresh token, so this resolves silently as long as the grant
     * stands; it returns null when the grant is gone and the user must consent again.
     */
    suspend fun refreshAccessToken(): String? {
        cachedToken = null
        val result = runCatching { authorize() }.getOrNull()
        return (result as? AuthResult.Authorized)?.accessToken
    }

    /** Reads the token out of the Intent handed back by the consent screen. */
    fun onConsentResult(data: Intent?): String? =
        client.getAuthorizationResultFromIntent(data).accessToken
            ?.also { cachedToken = it }

    /**
     * Drops the in-memory token. The Google-side grant survives, so the next
     * [authorize] resolves silently without prompting again. Full revocation
     * belongs with the Settings screen in v0.6.
     */
    fun clearToken() {
        cachedToken = null
    }

    companion object {
        /**
         * A Google *restricted* scope. Fine while the OAuth consent screen is in
         * Testing mode; a public Play Store listing would require verification
         * plus a CASA assessment. See VERSION_PLAN.md section 4.
         */
        const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    }
}
