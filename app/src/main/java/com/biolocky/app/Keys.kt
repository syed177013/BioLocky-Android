package com.biolocky.app

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore keys (hardware-backed where available, never exportable).
 *  DEVICE: wraps the PIN-protected key so the PIN can't be brute-forced off-device.
 *  BIO:    requires a fingerprint for every single use; invalidated if fingerprints change. */
object Keys {
    private const val DEV = "biolocky_device"
    private const val BIO = "biolocky_bio"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Suppress("DEPRECATION")
    private fun gen(alias: String, bio: Boolean): SecretKey {
        val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (bio) {
            b.setUserAuthenticationRequired(true)
            b.setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            else b.setUserAuthenticationValidityDurationSeconds(-1)
        }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(b.build())
        return g.generateKey()
    }

    private fun key(alias: String, bio: Boolean): SecretKey =
        (store().getKey(alias, null) as SecretKey?) ?: gen(alias, bio)

    fun deviceSeal(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(DEV, false))
        return c.iv + c.doFinal(data)
    }

    fun deviceUnseal(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(DEV, false), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Cipher to hand to BiometricPrompt. Throws KeyPermanentlyInvalidatedException if fingerprints changed. */
    fun bioCipher(encrypt: Boolean, iv: ByteArray? = null): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (encrypt) {
            deleteBio()
            c.init(Cipher.ENCRYPT_MODE, key(BIO, true))
        } else {
            c.init(Cipher.DECRYPT_MODE, key(BIO, true), GCMParameterSpec(128, iv))
        }
        return c
    }

    fun deleteBio() {
        try { store().deleteEntry(BIO) } catch (_: Exception) {}
    }
}
