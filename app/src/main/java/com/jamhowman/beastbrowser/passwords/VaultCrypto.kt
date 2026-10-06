package com.jamhowman.beastbrowser.passwords

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM key in the Android Keystore. Every encrypt/decrypt requires user authentication
 * via [androidx.biometric.BiometricPrompt.CryptoObject] (per-use auth, timeout 0).
 *
 * Uses AUTH_BIOMETRIC_STRONG only — device-credential + CryptoObject is not allowed with
 * timeout 0, and BIOMETRIC_STRONG|DEVICE_CREDENTIAL + CryptoObject needs API 30+.
 */
internal object VaultCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    /** v2: per-use biometric auth. v1 used a 5-minute window incompatible with CryptoObject. */
    private const val ALIAS = "beast_password_vault_v2"
    private const val LEGACY_ALIAS = "beast_password_vault_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    const val IV_SIZE = 12

    fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // Drop the broken v1 key if present (wrong auth params for CryptoObject unlock).
        if (ks.containsAlias(LEGACY_ALIAS)) {
            runCatching { ks.deleteEntry(LEGACY_ALIAS) }
        }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
        // Per-use auth: CryptoObject unlocks the key; invalidatedByBiometricEnrollment works.
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            // API 24-29: -1 = "auth for every use" (biometric CryptoObject). 0 would create a time-bound key
            // with a 0 s window, so cipher init throws UserNotAuthenticatedException and unlock never works.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        if (Build.VERSION.SDK_INT >= 24) {
            builder.setInvalidatedByBiometricEnrollment(true)
        }
        gen.init(builder.build())
        return gen.generateKey()
    }

    fun cipherForEncrypt(): Cipher {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        return c
    }

    fun cipherForDecrypt(iv: ByteArray): Cipher {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return c
    }

    fun encrypt(cipher: Cipher, plain: ByteArray): ByteArray = cipher.doFinal(plain)

    fun decrypt(cipher: Cipher, cipherText: ByteArray): ByteArray = cipher.doFinal(cipherText)

    fun deleteKey() {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        if (ks.containsAlias(LEGACY_ALIAS)) ks.deleteEntry(LEGACY_ALIAS)
    }
}
