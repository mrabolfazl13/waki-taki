package com.securetalk.core.security.store

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException

/**
 * Android secret storage: all secret blobs are encrypted with a Tink AEAD whose
 * keyset is sealed under an **Android Keystore** master key (Keystore-backed key
 * storage per ARCHITECTURE §3.1). Private keys themselves never touch disk in
 * plaintext, and never leave the app's storage.
 *
 * Uses only the long-stable Tink Android integration APIs:
 * `AndroidKeysetManager.Builder().withSharedPref(...).withMasterKeyUri(...).build()`.
 */
class TinkAndroidSecretStore(context: Context) : SecretStore {

    private val appContext = context.applicationContext

    private val aead: Aead by lazy {
        try {
            AeadConfig.register()
        } catch (_: GeneralSecurityException) {
            // Already registered — safe to ignore.
        }
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_FILE, PREF_FILE)
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        manager.keysetHandle.getPrimitive(Aead::class.java)
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
    }
}
