package com.securetalk.core.security.engine

/**
 * Crypto abstraction isolating the primitive choice (ARCHITECTURE §3.2 / DECISIONS D3).
 *
 * Current implementation ([BcCryptoEngine]) uses only well-reviewed constructions:
 *  - X25519 key agreement            (BouncyCastle)
 *  - Ed25519 signatures              (BouncyCastle)
 *  - HKDF-SHA256                     (BouncyCastle)
 *  - AES-256-GCM AEAD, 96-bit nonce  (JDK/JCE provider, no custom primitives)
 *
 * Pure Kotlin — no Android imports — unit-testable on the JVM.
 */
interface CryptoEngine {
    fun randomBytes(n: Int): ByteArray

    /** Returns (privateKey, publicKey), both 32 raw bytes. */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray>

    /** 32-byte shared secret. */
    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray

    /** Returns (seed, publicKey), both 32 raw bytes. */
    fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray>

    fun ed25519Sign(seed: ByteArray, data: ByteArray): ByteArray
    fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray

    /** AES-256-GCM; output = ciphertext || 16-byte tag. [nonce] must be 12 bytes. */
    fun aesGcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
    fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray

    companion object {
        const val KEY_SIZE = 32
        const val NONCE_SIZE = 12
        const val TAG_SIZE = 16
    }
}
