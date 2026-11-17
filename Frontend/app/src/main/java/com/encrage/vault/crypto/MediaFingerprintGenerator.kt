package com.encrage.vault.crypto

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

class MediaFingerprintGenerator @Inject constructor(
    private val contentResolver: ContentResolver,
    private val masterKeyManager: MasterKeyManager,
    private val keyDerivation: MediaKeyDerivation,
) {
    suspend fun fingerprint(uri: Uri): String = withContext(Dispatchers.IO) {
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sha256.update(buffer, 0, read)
                }
            } ?: error("MediaStore item is no longer readable")
        } finally { buffer.fill(0) }
        val plaintextHash = sha256.digest()
        val master = masterKeyManager.unwrap()
        val fingerprintKey = keyDerivation.fingerprintKey(master)
        master.fill(0)
        try {
            val fingerprint = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(fingerprintKey, "HmacSHA256"))
                doFinal(plaintextHash)
            }
            Base64.encodeToString(fingerprint, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } finally {
            plaintextHash.fill(0)
            fingerprintKey.fill(0)
        }
    }
}
