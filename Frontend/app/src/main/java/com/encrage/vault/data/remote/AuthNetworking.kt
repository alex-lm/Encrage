package com.encrage.vault.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

class BearerInterceptor @Inject constructor(private val tokens: SessionTokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.accessToken()
        val request = if (token == null) chain.request() else chain.request().newBuilder()
            .header("Authorization", "Bearer $token").build()
        return chain.proceed(request)
    }
}

sealed interface RefreshOutcome {
    data class Refreshed(val accessToken: String) : RefreshOutcome
    /** The server rejected the refresh token (400/401/403); the session must be cleared. */
    data object Revoked : RefreshOutcome
    /** Offline or a transient server failure; keep the local session. */
    data object Unavailable : RefreshOutcome
    data object NoCredentials : RefreshOutcome
}

/**
 * The single place that spends a refresh token. Refresh tokens rotate and the server revokes the whole
 * session when a rotated token is reused, so startup restore and the 401 authenticator must never refresh
 * concurrently. Both go through this lock. Blocking: call from an I/O thread only.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val authApi: Provider<AuthApi>,
    private val tokens: SessionTokenStore,
) {
    private val lock = Any()

    /**
     * @param staleAccessToken the access token the caller already tried (or null). If another caller has
     * refreshed since, the newer token is returned without spending the refresh token again.
     */
    fun refresh(staleAccessToken: String?): RefreshOutcome = synchronized(lock) {
        val current = tokens.accessToken()
        if (current != null && current != staleAccessToken) return RefreshOutcome.Refreshed(current)
        val refresh = try {
            tokens.refreshToken()
        } catch (_: Exception) {
            return RefreshOutcome.Unavailable // Keystore unavailable (e.g. device locked); try again later.
        } ?: return RefreshOutcome.NoCredentials
        val response = try {
            runBlocking { authApi.get().refresh(RefreshRequest(refresh)) }
        } catch (_: Exception) {
            return RefreshOutcome.Unavailable
        }
        if (!response.isSuccessful) {
            return if (response.code() in REVOKED_CODES) RefreshOutcome.Revoked else RefreshOutcome.Unavailable
        }
        val body = response.body() ?: return RefreshOutcome.Unavailable
        // Persist the rotated refresh token before exposing the new access token.
        tokens.setRefreshToken(body.refreshToken)
        tokens.setAccessToken(body.accessToken)
        RefreshOutcome.Refreshed(body.accessToken)
    }

    private companion object {
        val REVOKED_CODES = setOf(400, 401, 403)
    }
}

@Singleton
class RefreshAuthenticator @Inject constructor(
    private val refresher: TokenRefresher,
    private val sessions: Provider<SessionRepository>,
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val attemptedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        return when (val outcome = refresher.refresh(attemptedToken)) {
            is RefreshOutcome.Refreshed -> response.request.withBearer(outcome.accessToken)
            RefreshOutcome.Revoked -> {
                sessions.get().markCredentialsRevoked()
                null
            }
            RefreshOutcome.Unavailable, RefreshOutcome.NoCredentials -> null // Preserve the offline session.
        }
    }

    private fun Request.withBearer(token: String) = newBuilder().header("Authorization", "Bearer $token").build()
    private fun responseCount(response: Response): Int {
        var count = 1
        var previous = response.priorResponse
        while (previous != null) { count++; previous = previous.priorResponse }
        return count
    }
}
