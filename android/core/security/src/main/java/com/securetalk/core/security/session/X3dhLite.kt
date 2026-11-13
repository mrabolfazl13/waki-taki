package com.securetalk.core.security.session

import com.securetalk.core.security.engine.CryptoEngine
import java.nio.ByteBuffer

/**
 * X3DH-style session establishment against published prekeys (ARCHITECTURE §3.1/§3.2),
 * following the Signal X3DH construction with BouncyCastle X25519 primitives:
 *
 *   DH1 = DH(IK_a, SPK_b)     identity -> signed prekey
 *   DH2 = DH(EK_a, SPK_b)     ephemeral -> signed prekey
 *   DH3 = DH(EK_a, IK_b)      ephemeral -> recipient identity
 *   DH4 = DH(EK_a, OPK_b)     ephemeral -> one-time prekey (optional)
 *   SK  = HKDF(F || DH1 || DH2 || DH3 [|| DH4], salt=0, info="securetalk-x3dh")
 *   F   = 32 x 0xFF
 *
 * The initiator's envelope carries the ephemeral public key and identity public
 * key so the receiver recomputes the same SK from its prekey private keys.
 */
object X3dhLite {

    private val F = ByteArray(32) { 0xFF.toByte() }
    private val INFO = "securetalk-x3dh".toByteArray(Charsets.UTF_8)

    data class Envelope(
        val ephemeralPublicKey: ByteArray,
        val initiatorIdentityPublicKey: ByteArray,
        val oneTimePrekeyId: Long?,
        /** Header bytes to transmit alongside the first ratchet message. */
        val headerBytes: ByteArray,
    )

    /**
     * Initiator side. Returns the shared secret [sk] and the envelope to send.
     * The initiator MUST NOT reuse the ephemeral key pair for other sessions.
     */
    fun initiate(
        engine: CryptoEngine,
        initiatorIdentityPrivate: ByteArray,
        bundle: com.securetalk.core.common.model.PreKeyBundleData,
    ): Pair<ByteArray, Envelope> {
        val (ephPriv, ephPub) = engine.generateX25519KeyPair()

        val dh1 = engine.x25519SharedSecret(initiatorIdentityPrivate, bundle.signedPrekeyPub)
        val dh2 = engine.x25519SharedSecret(ephPriv, bundle.signedPrekeyPub)
        val dh3 = engine.x25519SharedSecret(ephPriv, bundle.identitySigningPub)

        // identitySigningPub carries the X25519 identity public point; the Ed25519
        // verify key travels with the bundle registration for prekey signature checks.
        val ikPublicForX3dh = bundle.identitySigningPub

        val dhList = buildList {
            add(dh1); add(dh2); add(dh3)
            bundle.oneTimePrekeyPub?.let { add(engine.x25519SharedSecret(ephPriv, it)) }
        }
        val sk = engine.hkdfSha256(F + dhList.reduce { acc, b -> acc + b }, ByteArray(32), INFO, 32)

        val header = ByteBuffer.allocate(1 + 32 + 32 + 8)
            .put(VERSION)
            .put(ephPub)
            .put(ikPublicForX3dh)
            .putLong(bundle.oneTimePrekeyId ?: -1L)
            .array()

        return sk to Envelope(ephPub, ikPublicForX3dh, bundle.oneTimePrekeyId, header)
    }

    /** Receiver side: recompute SK from the envelope header and own prekey private keys. */
    fun respond(
        engine: CryptoEngine,
        ownIdentityPrivate: ByteArray,
        ownSignedPrekeyPrivate: ByteArray,
        ownOneTimePrekeyPrivate: ByteArray?,
        header: ByteArray,
    ): ByteArray {
        require(header.size >= 1 + 32 + 32 && header[0] == VERSION) { "bad X3DH header" }
        val ephPub = header.copyOfRange(1, 33)
        val initiatorIkPub = header.copyOfRange(33, 65)
        val opkId = ByteBuffer.wrap(header, 65, 8).long

        val dh1 = engine.x25519SharedSecret(ownSignedPrekeyPrivate, initiatorIkPub)
        val dh2 = engine.x25519SharedSecret(ownSignedPrekeyPrivate, ephPub)
        val dh3 = engine.x25519SharedSecret(ownIdentityPrivate, ephPub)
        val dhList = buildList {
            add(dh1); add(dh2); add(dh3)
            if (ownOneTimePrekeyPrivate != null && opkId >= 0) {
                add(engine.x25519SharedSecret(ownOneTimePrekeyPrivate, ephPub))
            }
        }
        return engine.hkdfSha256(F + dhList.reduce { acc, b -> acc + b }, ByteArray(32), INFO, 32)
    }

    const val VERSION: Byte = 1
}
