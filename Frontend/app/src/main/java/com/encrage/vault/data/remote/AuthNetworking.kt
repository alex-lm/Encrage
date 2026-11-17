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

@Singleton
class RefreshAuthenticator @Inject constructor(
    private val authApi: Provider<AuthApi>,
    private val tokens: SessionTokenStore,
    private val sessions: SessionRepository,
) : Authenticator {
    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (responseCount(response) >= 2) return null
        val attemptedToken = response.request.header("Authorization")?.removePrefix("Bearer ")
        synchronized(refreshLock) {
            val currentToken = tokens.accessToken()
            if (currentToken != null && currentToken != attemptedToken) return response.request.withBearer(currentToken)
            val refresh = tokens.refreshToken() ?: return null
            val refreshed = try {
                runBlocking { authApi.get().refresh(RefreshRequest(refresh)) }
            } catch (_: Exception) {
                return null // Preserve the local session on transient/offline failures.
            }
            if (!refreshed.isSuccessful) {
                if (refreshed.code() in setOf(400, 401, 403)) sessions.markCredentialsRevoked()
                return null
            }
            val body = refreshed.body() ?: return null
            tokens.setAccessToken(body.accessToken)
            tokens.setRefreshToken(body.refreshToken)
            return response.request.withBearer(body.accessToken)
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
