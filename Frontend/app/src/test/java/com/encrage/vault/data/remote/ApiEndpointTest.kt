package com.encrage.vault.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiEndpointTest {
    @Test
    fun hostAndPortAreNormalizedForDebug() {
        assertEquals(
            "http://192.0.2.20:8000/",
            ApiEndpoint.normalize(" 192.0.2.20:8000 ", allowCleartext = true).getOrThrow(),
        )
    }

    @Test
    fun bareHostDefaultsToHttpsOutsideDebugBuilds() {
        assertEquals(
            "https://photos.example.com/",
            ApiEndpoint.normalize("photos.example.com", allowCleartext = false).getOrThrow(),
        )
        assertEquals(
            "https://192.0.2.20:8443/",
            ApiEndpoint.normalize("192.0.2.20:8443", allowCleartext = false).getOrThrow(),
        )
    }

    @Test
    fun fullUrlKeepsBasePathAndAddsTrailingSlash() {
        assertEquals(
            "https://photos.example.com/api/v1/",
            ApiEndpoint.normalize("https://photos.example.com/api/v1", allowCleartext = false).getOrThrow(),
        )
    }

    @Test
    fun cleartextIsRejectedOutsideDebugBuilds() {
        val result = ApiEndpoint.normalize("http://192.0.2.20:8000", allowCleartext = false)
        assertTrue(result.isFailure)
        assertEquals("HTTP endpoints are only allowed in debug builds", result.exceptionOrNull()?.message)
    }

    @Test
    fun queryIsRejectedFromBaseUrl() {
        val result = ApiEndpoint.normalize("https://photos.example.com/?token=secret", allowCleartext = false)
        assertTrue(result.isFailure)
    }

    @Test
    fun requestIsRebasedAndKeepsRouteAndQuery() {
        val rewritten = rewriteApiUrl(
            original = "https://default.example/api/media?limit=100".toHttpUrl(),
            retrofitBase = "https://default.example/api/".toHttpUrl(),
            configuredBase = "https://custom.example/backend/".toHttpUrl(),
        )

        assertEquals("https://custom.example/backend/media?limit=100", rewritten.toString())
    }
}
