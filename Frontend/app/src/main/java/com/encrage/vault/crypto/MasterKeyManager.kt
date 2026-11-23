package com.encrage.vault.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MasterKeyManager @Inject constructor(private val secretStore: SecretStore) {
    /**
     * Whether a master key is stored. Checks presence, not decryptability: the wrapping key requires an
     * unlocked device, and treating "phone is locked" as "no key" would send the user to key setup.
     */
    fun hasKey(): Boolean = secretStore.contains(KEY_NAME)

    /** True when the stored key can be unwrapped right now (false while the device is locked). */
    fun isUsable(): Boolean = runCatching {
        val key = secretStore.get(KEY_NAME) ?: return@runCatching false
        (key.size == KEY_BYTES).also { key.fill(0) }
    }.getOrDefault(false)

    fun pendingRecoveryKey(): String? = runCatching {
        secretStore.get(PENDING_KEY_NAME)?.let { key -> encode(key).also { key.fill(0) } }
    }.getOrNull()

    fun generate(): String {
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        secretStore.put(PENDING_KEY_NAME, key.copyOf())
        return encode(key).also { key.fill(0) }
    }

    fun import(recoveryKey: String) {
        val decoded = decode(recoveryKey)
        try {
            require(decoded.size == KEY_BYTES) { "Recovery Key must represent exactly 32 bytes" }
            secretStore.put(KEY_NAME, decoded.copyOf())
            secretStore.delete(PENDING_KEY_NAME)
        } finally {
            decoded.fill(0)
        }
    }

    fun acknowledgeGeneratedKey() {
        val pending = secretStore.get(PENDING_KEY_NAME) ?: error("No generated Recovery Key is pending")
        try {
            require(pending.size == KEY_BYTES)
            secretStore.put(KEY_NAME, pending.copyOf())
            secretStore.delete(PENDING_KEY_NAME)
        } finally {
            pending.fill(0)
        }
    }

    /** Callers must wipe the returned array as soon as their operation completes. */
    fun unwrap(): ByteArray = secretStore.get(KEY_NAME)?.also {
        check(it.size == KEY_BYTES) { "Stored media key has an invalid length" }
    } ?: error("No media master key is configured")

    fun exportForAuthenticatedReveal(): String {
        val key = unwrap()
        return encode(key).also { key.fill(0) }
    }

    fun clear() = secretStore.delete(KEY_NAME)

    private fun encode(key: ByteArray): String = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(text: String): ByteArray = Base64.decode(text.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    companion object {
        const val KEY_BYTES = 32
        private const val KEY_NAME = "media_master_key"
        private const val PENDING_KEY_NAME = "media_master_key_pending"
    }
}
