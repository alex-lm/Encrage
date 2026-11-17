package com.encrage.vault.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Stores only AES-GCM ciphertext on disk; the non-exportable wrapping key stays in Android Keystore. */
@Singleton
class SecretStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val root = File(context.noBackupFilesDir, "secrets").apply { mkdirs() }

    @Synchronized
    fun put(name: String, secret: ByteArray) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias(name)))
            }
            val ciphertext = cipher.doFinal(secret)
            val output = ByteArray(1 + cipher.iv.size + ciphertext.size)
            output[0] = cipher.iv.size.toByte()
            cipher.iv.copyInto(output, 1)
            ciphertext.copyInto(output, 1 + cipher.iv.size)
            val target = file(name)
            val temporary = File(target.parentFile, "${target.name}.new")
            temporary.outputStream().use { stream ->
                stream.write(output)
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            output.fill(0)
            ciphertext.fill(0)
        } finally {
            secret.fill(0)
        }
    }

    @Synchronized
    fun get(name: String): ByteArray? {
        val bytes = file(name).takeIf(File::exists)?.readBytes() ?: return null
        require(bytes.isNotEmpty()) { "Invalid wrapped secret" }
        val ivLength = bytes[0].toInt() and 0xff
        require(ivLength in 12..32 && bytes.size > 1 + ivLength) { "Invalid wrapped secret" }
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val ciphertext = bytes.copyOfRange(1 + ivLength, bytes.size)
        val key = keyStore.getKey(alias(name), null) as? SecretKey ?: run {
            bytes.fill(0)
            ciphertext.fill(0)
            iv.fill(0)
            return null
        }
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                doFinal(ciphertext)
            }
        } finally {
            bytes.fill(0)
            ciphertext.fill(0)
            iv.fill(0)
        }
    }

    @Synchronized
    fun delete(name: String) {
        file(name).delete()
        keyStore.deleteEntry(alias(name))
    }

    fun contains(name: String): Boolean = file(name).exists()

    private fun getOrCreateKey(alias: String): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) builder.setUnlockedDeviceRequired(true)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(builder.build())
            generateKey()
        }
    }

    private fun alias(name: String) = "encrage.$name.wrap.v1"
    private fun file(name: String) = File(root, "$name.bin")

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
