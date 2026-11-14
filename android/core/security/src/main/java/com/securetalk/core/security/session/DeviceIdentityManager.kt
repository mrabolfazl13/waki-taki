package com.securetalk.core.security.session

import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.PreKeyBundleData
import com.securetalk.core.security.engine.CryptoEngine
import com.securetalk.core.security.store.SecretStore
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Own device identity (ARCHITECTURE §3.1):
 *  - X25519 identity (DH) key pair
 *  - Ed25519 signing key pair
 *  - signed prekey + one-time prekeys
 *
 * Private key material is stored only via [SecretStore], which on Android is
 * Keystore-sealed (see [com.securetalk.core.security.store.TinkAndroidSecretStore]).
 * Public keys are registered with the backend/local host.
 *
 * Pure Kotlin (no Android imports) so it is unit-testable.
 */
class DeviceIdentityManager(
    private val engine: CryptoEngine,
    private val secretStore: SecretStore,
) {

    data class Identity(
        val deviceId: DeviceId,
        val x25519Private: ByteArray,
        val x25519Public: ByteArray,
        val ed25519Seed: ByteArray,
        val ed25519Public: ByteArray,
    )

    data class SignedPreKey(
        val id: Long,
        val private: ByteArray,
        val public: ByteArray,
        val signature: ByteArray, // Ed25519 signature over public
    )

    data class OneTimePreKey(
        val id: Long,
        val private: ByteArray,
        val public: ByteArray,
    )

    data class PublishedBundle(
        val identity: Identity,
        val signedPreKey: SignedPreKey,
        val oneTimePreKeys: List<OneTimePreKey>,
    )

    @Volatile
    private var cached: PublishedBundle? = null

    /** Loads or generates the full identity. Idempotent and safe to call at startup. */
    @Synchronized
    fun ensureIdentity(): PublishedBundle {
        cached?.let { return it }

        val deviceId = DeviceId(secretStore.loadSecret(KEY_DEVICE_ID)?.toString(Charsets.UTF_8) ?: newDeviceId())
        if (!secretStore.containsSecret(KEY_DEVICE_ID)) {
            secretStore.storeSecret(KEY_DEVICE_ID, deviceId.value.toByteArray(Charsets.UTF_8))
        }

        val xPriv = secretStore.loadSecret(KEY_X_PRIV) ?: engine.generateX25519KeyPair().first.also {
            secretStore.storeSecret(KEY_X_PRIV, it)
        }
        val xPub = engine.let { publicX25519(xPriv) }

        val edSeed = secretStore.loadSecret(KEY_ED_SEED) ?: engine.generateEd25519KeyPair().first.also {
            secretStore.storeSecret(KEY_ED_SEED, it)
        }
        val edPub = ed25519Public(edSeed)

        val identity = Identity(deviceId, xPriv, xPub, edSeed, edPub)

        val spkPrivate = secretStore.loadSecret(KEY_SPK_PRIV) ?: engine.generateX25519KeyPair().first.also {
            secretStore.storeSecret(KEY_SPK_PRIV, it)
        }
        val spkPublic = publicX25519(spkPrivate)
        val spkSig = engine.ed25519Sign(edSeed, spkPublic)
        val spkId = (secretStore.loadSecret(KEY_SPK_ID)?.toString(Charsets.UTF_8)?.toLongOrNull() ?: 1L).also {
            secretStore.storeSecret(KEY_SPK_ID, it.toString().toByteArray())
        }
        val signedPreKey = SignedPreKey(spkId, spkPrivate, spkPublic, spkSig)

        val oneTime = if (secretStore.containsSecret(KEY_OTP_POOL)) {
            decodePool(secretStore.loadSecret(KEY_OTP_POOL)!!)
        } else {
            generateOneTimePrekeys(32).also { secretStore.storeSecret(KEY_OTP_POOL, encodePool(it)) }
        }

        val bundle = PublishedBundle(identity, signedPreKey, oneTime)
        cached = bundle
        return bundle
    }

    /** The bundle to publish to the backend key server. */
    fun publicBundle(): PreKeyBundleData {
        val b = ensureIdentity()
        val otp = b.oneTimePreKeys.firstOrNull()
        return PreKeyBundleData(
            deviceId = b.identity.deviceId,
            identitySigningPub = b.identity.x25519Public, // X25519 identity point
            signedPrekeyPub = b.signedPreKey.public,
            prekeySignature = b.signedPreKey.signature,
            oneTimePrekeyPub = otp?.public,
            oneTimePrekeyId = otp?.id,
        )
    }

    /**
     * Ed25519 verify key — published alongside the bundle for prekey-signature
     * verification by peers (kept separate from the X25519 identity point).
     */
    fun signingPublicKey(): ByteArray = ensureIdentity().identity.ed25519Public

    fun verifyPrekeySignature(bundle: PreKeyBundleData, remoteSigningPublicKey: ByteArray): Boolean =
        engine.ed25519Verify(remoteSigningPublicKey, bundle.signedPrekeyPub, bundle.prekeySignature)

    /** Consumes a one-time prekey (when an incoming envelope names it). */
    @Synchronized
    fun consumeOneTimePrekey(id: Long): OneTimePreKey? {
        val pool = decodePool(secretStore.loadSecret(KEY_OTP_POOL) ?: return null)
        val used = pool.firstOrNull { it.id == id } ?: return null
        val remaining = pool.filterNot { it.id == id }
        val topUp = if (remaining.size < REFILL_THRESHOLD) generateOneTimePrekeys(REFILL_COUNT) else emptyList()
        secretStore.storeSecret(KEY_OTP_POOL, encodePool(remaining + topUp))
        cached = null
        return used
    }

    /** Rotates the signed prekey (e.g. after transport switch — §3.4). */
    @Synchronized
    fun rotateSignedPrekey(): SignedPreKey {
        val b = ensureIdentity()
        val (priv, pub) = engine.generateX25519KeyPair()
        val sig = engine.ed25519Sign(b.identity.ed25519Seed, pub)
        val next = SignedPreKey(b.signedPreKey.id + 1, priv, pub, sig)
        secretStore.storeSecret(KEY_SPK_PRIV, priv)
        secretStore.storeSecret(KEY_SPK_ID, next.id.toString().toByteArray())
        cached = null
        return next
    }

    private fun publicX25519(priv: ByteArray): ByteArray =
        org.bouncycastle.crypto.params.X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    private fun ed25519Public(seed: ByteArray): ByteArray =
        org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    private fun generateOneTimePrekeys(count: Int): List<OneTimePreKey> {
        val base = System.currentTimeMillis() / 1000
        return List(count) { i ->
            val (priv, pub) = engine.generateX25519KeyPair()
            OneTimePreKey(base + i, priv, pub)
        }
    }

    private fun encodePool(pool: List<OneTimePreKey>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val len = pool.size
        val buf = ByteBuffer.allocate(4).putInt(len).array()
        out.write(buf)
        pool.forEach {
            out.write(ByteBuffer.allocate(8).putLong(it.id).array())
            out.write(it.private)
        }
        return out.toByteArray()
    }

    private fun decodePool(bytes: ByteArray): List<OneTimePreKey> {
        val buf = ByteBuffer.wrap(bytes)
        val count = buf.int
        return List(count) {
            val id = buf.long
            val priv = ByteArray(32).also { buf.get(it) }
            OneTimePreKey(id, priv, org.bouncycastle.crypto.params.X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded)
        }
    }

    private fun newDeviceId(): String = UUID.randomUUID().toString()

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_X_PRIV = "identity_x25519_priv"
        const val KEY_ED_SEED = "identity_ed25519_seed"
        const val KEY_SPK_PRIV = "signed_prekey_priv"
        const val KEY_SPK_ID = "signed_prekey_id"
        const val KEY_OTP_POOL = "one_time_prekey_pool"
        const val REFILL_THRESHOLD = 16
        const val REFILL_COUNT = 32
    }
}
