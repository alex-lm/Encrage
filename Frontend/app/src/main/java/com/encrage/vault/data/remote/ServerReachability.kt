package com.encrage.vault.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/**
 * Process-wide view of whether the configured Encrage server answered its most recent request.
 * It is a UI hint only; it never gates requests. No URL, token or payload is recorded.
 */
@Singleton
class ServerReachability @Inject constructor() {
    private val mutableUnreachableSince = MutableStateFlow<Long?>(null)

    /** Epoch millis of the first failed request in the current outage, or null when reachable. */
    val unreachableSince: StateFlow<Long?> = mutableUnreachableSince.asStateFlow()

    fun markReachable() {
        if (mutableUnreachableSince.value != null) mutableUnreachableSince.value = null
    }

    fun markUnreachable() {
        if (mutableUnreachableSince.value == null) mutableUnreachableSince.value = System.currentTimeMillis()
    }
}

/** Records transport-level success or failure. Any HTTP response, even an error, proves reachability. */
class ReachabilityInterceptor @Inject constructor(
    private val reachability: ServerReachability,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = try {
            chain.proceed(chain.request())
        } catch (error: IOException) {
            if (isConnectivityFailure(error)) reachability.markUnreachable()
            throw error
        }
        reachability.markReachable()
        return response
    }
}

/**
 * True when [error] means the server could not be reached or the connection dropped, as opposed to a
 * problem with a local file. Callers use it to pause work instead of blaming the individual item.
 */
fun isConnectivityFailure(error: Throwable): Boolean = when (error) {
    is java.io.FileNotFoundException -> false
    is UnknownHostException, is SocketException, is SSLException -> true
    is InterruptedIOException -> true // includes SocketTimeoutException
    is IOException -> error.message?.let { message ->
        CONNECTIVITY_HINTS.any { message.contains(it, ignoreCase = true) }
    } ?: false
    else -> false
}

private val CONNECTIVITY_HINTS = listOf("failed to connect", "unexpected end of stream", "connection", "stream was reset")
