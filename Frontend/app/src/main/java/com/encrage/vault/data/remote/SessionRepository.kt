package com.encrage.vault.data.remote

import com.encrage.vault.crypto.SecretStore
import com.encrage.vault.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SessionState {
    data object Restoring : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val username: String, val online: Boolean) : SessionState
}

@Singleton
class SessionTokenStore @Inject constructor(private val secretStore: SecretStore) {
    private val accessToken = AtomicReference<String?>(null)
    fun accessToken(): String? = accessToken.get()
    fun setAccessToken(value: String?) = accessToken.set(value)
    fun refreshToken(): String? {
        val bytes = secretStore.get(REFRESH) ?: return null
        return bytes.decodeToString().also { bytes.fill(0) }
    }
    fun setRefreshToken(value: String) = secretStore.put(REFRESH, value.encodeToByteArray())
    /** Presence only; decrypting needs an unlocked device. */
    fun hasRefreshToken(): Boolean = secretStore.contains(REFRESH)
    fun clear() {
        accessToken.set(null)
        secretStore.delete(REFRESH)
    }

    private companion object { const val REFRESH = "refresh_token" }
}

@Singleton
class SessionRepository @Inject constructor(
    private val authApi: AuthApi,
    private val tokens: SessionTokenStore,
    private val preferences: AppPreferences,
    private val refresher: TokenRefresher,
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    /**
     * Opens the local session immediately when credentials exist (the gallery is Room-backed and must not
     * wait on the network), then refreshes in the background through the shared [TokenRefresher] lock.
     */
    suspend fun restore() {
        val username = preferences.username.first()
        // Presence, not decryption: a locked phone must not look like a signed-out user.
        val hasRefresh = withContext(Dispatchers.IO) { tokens.hasRefreshToken() }
        if (username == null || !hasRefresh) {
            mutableState.value = SessionState.SignedOut
            return
        }
        mutableState.value = SessionState.SignedIn(username, online = false)
        when (withContext(Dispatchers.IO) { refresher.refresh(staleAccessToken = null) }) {
            is RefreshOutcome.Refreshed -> if (mutableState.value is SessionState.SignedIn) {
                mutableState.value = SessionState.SignedIn(username, online = true)
            }
            RefreshOutcome.Revoked, RefreshOutcome.NoCredentials -> clearLocalSession()
            RefreshOutcome.Unavailable -> Unit
        }
    }

    suspend fun login(username: String, password: String) {
        val response = authApi.login(LoginRequest(username.trim(), password))
        acceptTokens(response)
        preferences.setUsername(username.trim())
        mutableState.value = SessionState.SignedIn(username.trim(), online = true)
    }

    /** Signs out locally at once; server-side revocation is best effort and never blocks the UI. */
    suspend fun logout() {
        val refresh = withContext(Dispatchers.IO) { runCatching { tokens.refreshToken() }.getOrNull() }
        clearLocalSession()
        if (refresh != null) {
            backgroundScope.launch { runCatching { authApi.logout(LogoutRequest(refresh)) } }
        }
    }

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun clearForEndpointChange() = clearLocalSession()

    fun markCredentialsRevoked() {
        tokens.clear()
        mutableState.value = SessionState.SignedOut
    }

    private fun acceptTokens(response: TokenResponse) {
        tokens.setRefreshToken(response.refreshToken)
        tokens.setAccessToken(response.accessToken)
    }

    private suspend fun clearLocalSession() {
        tokens.clear()
        preferences.setUsername(null)
        mutableState.value = SessionState.SignedOut
    }
}
