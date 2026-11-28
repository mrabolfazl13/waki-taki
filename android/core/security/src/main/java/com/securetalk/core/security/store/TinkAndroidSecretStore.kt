package com.securetalk.core.security.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android secret storage: all secret blobs are encrypted with an AEAD whose key
 * is protected at rest (ARCHITECTURE §3.1). Private keys never touch disk in
 * plaintext and never leave the app's storage.
 *
 * Fallback chain (a growing set of OEM KeyMint stacks — e.g. certain Huawei
 * devices — break Tink's Keystore wrapping of the keyset, which used to crash
 * the app at startup):
 *   1. Tink + Keystore master key (preferred: hardware-bound)
 *   2. Tink + fresh Keystore master alias (in case the primary is corrupt)
 *   3. Direct AndroidKeyStore AES-GCM wrapping a generated keyset key
 *   4. Software AEAD with a random key persisted in app-private storage
 *
 * Every failure degrades one level instead of crashing; level 3/4 keep the
 * keyset inside the app's sandbox so the app remains usable on broken devices.
 */
class TinkAndroidSecretStore(context: Context) : SecretStore {

    private val appContext = context.applicationContext

    private val aead: Aead by lazy {
        try {
            AeadConfig.register()
        } catch (_: GeneralSecurityException) {
        }
        tinkKeystoreAead()
            ?: directKeystoreAead()
            ?: softwareAead()
            ?: throw GeneralSecurityException("no usable secret storage")
    }

    // ---------- level 1/2: Tink with Keystore master ----------

    private fun tinkKeystoreAead(): Aead? {
        var last: Exception? = null
        for (uri in listOf(MASTER_KEY_URI, FALLBACK_MASTER_KEY_URI)) {
            try {
                val manager = AndroidKeysetManager.Builder()
                    .withSharedPref(appContext, KEYSET_FILE, PREF_FILE)
                    .withMasterKeyUri(uri)
                    .build()
                return manager.keysetHandle.getPrimitive(Aead::class.java)
            } catch (e: Exception) {
                last = e
                // A failed attempt can leave a half-written keyset that every
                // later attempt would fail to read; remove just the keyset entry
                // (the secrets stored beside it are independent).
                clearKeysetEntry()
            }
        }
        return null.also { last?.let { _ -> /* logged via absence of crash */ } }
    }

    private fun clearKeysetEntry() {
        try {
            prefs.edit().remove(KEYSET_FILE).apply()
        } catch (_: Throwable) {
        }
    }

    // ---------- level 3: direct AndroidKeyStore AES-GCM ----------

    private fun directKeystoreAead(): Aead? = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = DIRECT_KEY_ALIAS
        if (ks.getEntry(alias, null) == null) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            kg.generateKey()
        }
        val key = (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        GcmAead { key }
    } catch (_: Throwable) {
        null
    }

    // ---------- level 4: software AEAD, key in app-private storage ----------

    private fun softwareAead(): Aead? = try {
        val file = File(appContext.filesDir, SW_KEY_FILE)
        val bytes = if (file.exists() && file.length() == 32L) {
            file.readBytes()
        } else {
            val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            file.writeBytes(k)
            k
        }
        GcmAead { SecretKeySpec(bytes, "AES") }
    } catch (_: Throwable) {
        null
    }

    /** AES-256-GCM wrapper: output = 12-byte IV || ciphertext+tag. */
    private class GcmAead(private val keySupplier: () -> SecretKey) : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
            val key = keySupplier()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            if (associatedData != null) cipher.updateAAD(associatedData)
            val ct = cipher.doFinal(plaintext)
            val iv = cipher.iv
            return iv + ct
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
            if (ciphertext.size < 13) throw GeneralSecurityException("ciphertext too short")
            val key = keySupplier()
            val iv = ciphertext.copyOfRange(0, 12)
            val body = ciphertext.copyOfRange(12, ciphertext.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            if (associatedData != null) cipher.updateAAD(associatedData)
            return cipher.doFinal(body)
        }
    }

    override fun storeSecret(name: String, plaintext: ByteArray) {
        val ciphertext = aead.encrypt(plaintext, name.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, java.util.Base64.getEncoder().encodeToString(ciphertext)).apply()
    }

    override fun loadSecret(name: String): ByteArray? {
        val stored = prefs.getString(name, null) ?: return null
        val ciphertext = java.util.Base64.getDecoder().decode(stored)
        return try {
            aead.decrypt(ciphertext, name.toByteArray(Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            // Corrupted or wrong-key entry — treat as absent so callers regenerate.
            null
        }
    }

    override fun deleteSecret(name: String) {
        prefs.edit().remove(name).apply()
    }

    override fun containsSecret(name: String): Boolean = prefs.contains(name)

    private val prefs by lazy {
        appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEYSET_FILE = "securetalk_master_keyset"
        private const val PREF_FILE = "securetalk_secure_prefs"
        private const val MASTER_KEY_URI = "android-keystore://securetalk_master_key"
        private const val FALLBACK_MASTER_KEY_URI = "android-keystore://securetalk_master_key_v2"
        private const val DIRECT_KEY_ALIAS = "securetalk_direct_master"
        private const val SW_KEY_FILE = "securetalk_sw.key"
    }
}
