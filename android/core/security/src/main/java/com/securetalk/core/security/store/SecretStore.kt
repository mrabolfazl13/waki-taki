package com.securetalk.core.security.store

/**
 * At-rest storage for secret material. Implementations are responsible for
 * encryption at rest (Android Keystore-backed via Tink on Android).
 */
interface SecretStore {
    fun storeSecret(name: String, plaintext: ByteArray)
    fun loadSecret(name: String): ByteArray?
    fun deleteSecret(name: String)
    fun containsSecret(name: String): Boolean
}
