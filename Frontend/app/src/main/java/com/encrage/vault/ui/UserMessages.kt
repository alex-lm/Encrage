package com.encrage.vault.ui

import com.encrage.vault.data.remote.isConnectivityFailure
import retrofit2.HttpException

/** Turns low-level failures into short sentences a person can act on. Never includes secrets. */
object UserMessages {
    const val SERVER_UNREACHABLE = "Can’t reach your Encrage server. Check that it’s running and that the server address is correct."

    fun signIn(error: Throwable): String = when {
        error is HttpException && error.code() == 401 -> "Incorrect username or password."
        error is HttpException && error.code() == 429 -> "Too many attempts. Wait a minute and try again."
        error is HttpException && error.code() in 500..599 -> "The server had a problem signing you in. Try again shortly."
        error is HttpException && error.code() == 404 -> "That address doesn’t look like an Encrage server. Check the server address."
        isConnectivityFailure(error) -> SERVER_UNREACHABLE
        else -> "Sign in failed. Check the server address and try again."
    }

    fun action(error: Throwable, fallback: String): String =
        if (isConnectivityFailure(error)) SERVER_UNREACHABLE else fallback

    /**
     * Upload failures are stored as raw text (often the server's JSON error envelope). Summarise them for
     * the Backup screen.
     */
    fun backupFailure(raw: String): String {
        val serverMessage = Regex("\"message\"\\s*:\\s*\"([^\"]{1,200})\"").find(raw)?.groupValues?.get(1)
        val text = serverMessage ?: raw
        return when {
            text.contains("quota", ignoreCase = true) || text.contains("storage", ignoreCase = true) && text.contains("full", ignoreCase = true) ->
                "Your cloud storage is full."
            text.contains("too_large", ignoreCase = true) || text.contains("exceeds", ignoreCase = true) ->
                "A file is larger than the server allows."
            text.contains("failed to connect", ignoreCase = true) || text.contains("timeout", ignoreCase = true) ->
                "The server couldn’t be reached during upload."
            text.contains("no longer readable", ignoreCase = true) || text.contains("ENOENT") || text.contains("FileNotFound") ->
                "A file was moved or deleted before it could be uploaded."
            else -> text.take(140)
        }
    }
}
