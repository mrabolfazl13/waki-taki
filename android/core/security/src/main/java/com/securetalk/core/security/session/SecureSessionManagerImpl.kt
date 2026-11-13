package com.securetalk.core.security.session

import com.securetalk.core.common.domain.SecureSessionManager
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.PreKeyBundleData
import com.securetalk.core.security.engine.CryptoEngine
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Provides remote prekey bundles for session establishment (REST in online mode, host in local mode). */
interface PreKeyBundleDirectory {
    suspend fun bundleFor(deviceId: DeviceId): Result<PreKeyBundleData?>
}

/**
 * Pairwise double-ratchet-lite sessions for key-establishment and control
 * messages (sender-key distribution envelopes) — ARCHITECTURE §3.2/§3.3.
 *
 * Envelope framing:
 * ```
 * x3dh_header: version(1) | ephemeral_pub(32) | initiator_ik_pub(32) | opk_id(8)
 * ratchet_seq: seq(8 BE)
 * body:        AES-256-GCM(ciphertext || tag), nonce = 12B BE(seq), AAD = caller-supplied
 * ```
 * The first envelope sent on a session includes the X3DH header; subsequent ones
 * omit it. The receiver verifies the prekey signature against the remote Ed25519
 * signing key before accepting a session.
 */
class SecureSessionManagerImpl(
    private val engine: CryptoEngine,
    private val identityManager: DeviceIdentityManager,
    private val bundleDirectory: PreKeyBundleDirectory,
) : SecureSessionManager {

    data class SessionState(
        val remoteIdentity: DeviceId,
        val ratchet: DoubleRatchetLite,
        /** Pending X3DH header to prefix on the next outgoing message (null after first). */
        @Volatile var pendingHeader: ByteArray? = null,
    )

    private val sessions = ConcurrentHashMap<String, SessionState>()

    override suspend fun ensureSession(peer: DeviceId, remoteBundle: PreKeyBundleData?): Result<Unit> {
        if (sessions.containsKey(peer.value)) return Result.success(Unit)
        val bundle = remoteBundle ?: bundleDirectory.bundleFor(peer).getOrNull()
            ?: return Result.failure(IllegalStateException("No prekey bundle for $peer"))

        val identity = identityManager.ensureIdentity()
        val (sk, envelope) = X3dhLite.initiate(engine, identity.x25519Private, bundle)
        val ratchet = DoubleRatchetLite(engine)
        ratchet.initializeSend(peer.value, sk)
        ratchet.initializeRecv(peer.value, sk) // same SK on both chains; counters separate directions
        sessions[peer.value] = SessionState(peer, ratchet, envelope.headerBytes)
        return Result.success(Unit)
    }

    override suspend fun encryptForPeer(peer: DeviceId, plaintext: ByteArray, aad: ByteArray): Result<ByteArray> {
        val session = sessions[peer.value]
            ?: return Result.failure(IllegalStateException("No session for $peer — call ensureSession"))
        return try {
            val (seq, mk) = session.ratchet.nextMessageKey(peer.value)
            val header = session.pendingHeader
            session.pendingHeader = null
            val nonce = ByteArray(12)
            ByteBuffer.wrap(nonce, 4, 8).putLong(seq)
            val body = engine.aesGcmEncrypt(mk, nonce, plaintext, aad)
            val seqBytes = ByteBuffer.allocate(8).putLong(seq).array()
            val out = (header ?: ByteArray(0)) + seqBytes + body
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun decryptFromPeer(peer: DeviceId, ciphertext: ByteArray, aad: ByteArray): Result<ByteArray> {
        val session = sessions[peer.value]
            ?: return Result.failure(IllegalStateException("No session for $peer"))
        return try {
            var offset = 0
            if (ciphertext.size >= 1 + 32 + 32 + 8 + 8 && ciphertext[0] == X3dhLite.VERSION) {
                // Incoming X3DH header: recompute the shared secret and reset the recv chain.
                offset = 1 + 32 + 32 + 8 // 73
            }
            if (offset > 0) {
                val identity = identityManager.ensureIdentity()
                val header = ciphertext.copyOfRange(0, offset)
                val opkId = ByteBuffer.wrap(header, 65, 8).long
                val opkPriv = if (opkId >= 0) identityManager.consumeOneTimePrekey(opkId)?.private else null
                val sk = X3dhLite.respond(
                    engine,
                    identity.x25519Private,
                    identity.signedPreKey.private,
                    opkPriv,
                    header,
                )
                session.ratchet.initializeRecv(peer.value, sk)
            }
            val seq = ByteBuffer.wrap(ciphertext, offset, 8).long
            val mk = session.ratchet.messageKeyFor(peer.value, seq)
            val nonce = ByteArray(12)
            ByteBuffer.wrap(nonce, 4, 8).putLong(seq)
            val body = ciphertext.copyOfRange(offset + 8, ciphertext.size)
            Result.success(engine.aesGcmDecrypt(mk, nonce, body, aad))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resetSession(peer: DeviceId) {
        sessions.remove(peer.value)?.ratchet?.drop(peer.value)
    }

    override suspend fun resetAll() {
        sessions.keys.toList().forEach { resetSession(DeviceId(it)) }
    }
}
