package com.encrage.vault.data.remote

import com.encrage.vault.crypto.SecretStore
import com.encrage.vault.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import retrofit2.HttpException
import java.io.IOException
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
) {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    suspend fun restore() {
        val username = preferences.username.first()
        val refresh = runCatching { tokens.refreshToken() }.getOrNull()
        if (username == null || refresh == null) {
            mutableState.value = SessionState.SignedOut
            return
        }
        try {
            val response = authApi.refresh(RefreshRequest(refresh))
            if (response.isSuccessful) {
                response.body()?.let(::acceptTokens)
                mutableState.value = SessionState.SignedIn(username, online = true)
            } else if (response.code() == 400 || response.code() == 401 || response.code() == 403) {
                clearLocalSession()
            } else {
                mutableState.value = SessionState.SignedIn(username, online = false)
            }
        } catch (_: IOException) {
            mutableState.value = SessionState.SignedIn(username, online = false)
        }
    }

    suspend fun login(username: String, password: String) {
        val response = authApi.login(LoginRequest(username.trim(), password))
        acceptTokens(response)
        preferences.setUsername(username.trim())
        mutableState.value = SessionState.SignedIn(username.trim(), online = true)
    }

    suspend fun logout() {
        val refresh = tokens.refreshToken()
        if (refresh != null) runCatching { authApi.logout(LogoutRequest(refresh)) }
        clearLocalSession()
    }

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
