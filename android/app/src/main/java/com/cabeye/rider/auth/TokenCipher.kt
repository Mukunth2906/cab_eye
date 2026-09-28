package com.cabeye.rider.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the sign-in token with a key that never leaves the phone's secure hardware.
 *
 * The token is what lets a returning rider skip OTP entirely, so it is the one secret on the
 * device worth protecting. It is sealed with AES-GCM under a key held in the Android Keystore:
 * copying the app's files off the phone yields ciphertext and nothing else.
 *
 * The key is deliberately **not** bound to biometric authentication. Binding it would make every
 * background request (the socket reconnecting mid-ride, say) need a fingerprint, which a blind
 * rider in a moving car cannot give. Instead the fingerprint gates *opening the app*, and the
 * key gates *reading the file*. Plain `javax.crypto`, so no new dependency.
 */
internal object TokenCipher {

    private const val TAG = "CabEye.Auth"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "cabeye.session.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** @return Base64 of iv‖ciphertext, or null if the keystore refused (treated as signed out) */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(iv + sealed, Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "TOKEN encrypt failed: $it") }.getOrNull()

    /** @return the token, or null if it cannot be read — the caller then asks for OTP again */
    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(0, IV_BYTES)))
        String(cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size)), Charsets.UTF_8)
    }.onFailure { Log.w(TAG, "TOKEN decrypt failed: $it") }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
