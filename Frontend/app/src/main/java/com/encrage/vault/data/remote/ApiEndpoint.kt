package com.encrage.vault.data.remote

import com.encrage.vault.BuildConfig
import com.encrage.vault.data.preferences.AppPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

object ApiEndpoint {
    fun normalize(value: String, allowCleartext: Boolean = BuildConfig.DEBUG): Result<String> = runCatching {
        val input = value.trim()
        require(input.isNotEmpty()) { "Enter an API address" }
        val candidate = if ("://" in input) input else "http://$input"
        val url = candidate.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid IP address or URL")
        require(url.scheme == "https" || allowCleartext) { "HTTP endpoints are only allowed in debug builds" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "The API URL cannot contain credentials" }
        require(url.query == null && url.fragment == null) { "The API URL cannot contain a query or fragment" }
        url.newBuilder()
            .encodedPath(if (url.encodedPath.endsWith('/')) url.encodedPath else "${url.encodedPath}/")
            .build()
            .toString()
    }
}

@Singleton
class ApiEndpointInterceptor @Inject constructor(
    private val preferences: AppPreferences,
) : Interceptor {
    private val retrofitBaseUrl = BuildConfig.API_BASE_URL.toHttpUrl()

    override fun intercept(chain: Interceptor.Chain): Response {
        // OkHttp interceptors run off the main thread. DataStore caches this value after its first read.
        val configuredBaseUrl = runBlocking { preferences.apiBaseUrl.first() }.toHttpUrl()
        val request = chain.request()
        return chain.proceed(
            request.newBuilder()
                .url(rewriteApiUrl(request.url, retrofitBaseUrl, configuredBaseUrl))
                .build(),
        )
    }
}

internal fun rewriteApiUrl(original: HttpUrl, retrofitBase: HttpUrl, configuredBase: HttpUrl): HttpUrl {
    val relativePath = original.encodedPath
        .removePrefix(retrofitBase.encodedPath)
        .removePrefix("/")
    val configuredPath = configuredBase.encodedPath.let { if (it.endsWith('/')) it else "$it/" }
    return configuredBase.newBuilder()
        .encodedPath(configuredPath + relativePath)
        .encodedQuery(original.encodedQuery)
        .build()
}
