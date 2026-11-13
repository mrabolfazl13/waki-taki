package com.securetalk.core.security.group

import com.securetalk.core.common.domain.SecureSessionManager
import com.securetalk.core.common.model.ChannelId
import com.securetalk.core.common.model.DeviceId
import com.securetalk.core.common.model.KeyEnvelope
import com.securetalk.core.common.model.MediaPacket
import com.securetalk.core.common.model.MediaPacketCodec
import com.securetalk.core.common.domain.GroupKeyManager
import com.securetalk.core.security.engine.CryptoEngine
import com.securetalk.core.security.engine.NonceDeriver
import com.securetalk.core.security.engine.ReplayWindow
import com.securetalk.core.security.session.DeviceIdentityManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/** Transport for sender-key envelopes: REST (`keys/distribute`) online, WS relay on local host. */
interface EnvelopeTransport {
    suspend fun distribute(envelopes: List<KeyEnvelope>): Result<Unit>
    val incoming: Flow<KeyEnvelope>
}

/** Directory of member devices for a channel (Room cache + REST refresh). */
interface ChannelMemberDirectory {
    suspend fun memberDevices(channelId: ChannelId): List<DeviceId>
}

/** Persisted sender-key entry. */
data class SenderKeyEntry(
    val channelId: ChannelId,
    val epoch: Long,
    val sender: DeviceId,
    val seed: ByteArray,
)

/**
 * Signal Sender-Key group media scheme (ARCHITECTURE §3.3):
 *
 *  - Each sender holds a 32-byte sender-key seed per (channel, epoch).
 *  - AES-256 key + 4-byte salt derived via HKDF-SHA256 from the seed.
 *  - Nonce = 12B big-endian sequence counter XOR 4B salt (never reused per key).
 *  - AAD = epoch || sender_device_id(16) || sequence — identical to the wire header.
 *  - Seeds are distributed pairwise-encrypted via [SecureSessionManager].
 *  - Membership change -> rotateEpoch -> new seed, receivers reject stale epochs
 *    and revoked senders; sliding replay window of 128 sequences per sender.
 */
class SenderKeyManager(
    private val engine: CryptoEngine,
    private val sessionManager: SecureSessionManager,
    private val identityManager: DeviceIdentityManager,
    private val envelopeTransport: EnvelopeTransport,
    private val memberDirectory: ChannelMemberDirectory,
) : GroupKeyManager {

    private val mutex = Mutex()

    /** (channel -> epoch) this device's current sender key seeds. */
    private val ownKeys = ConcurrentHashMap<String, Pair<Long, ByteArray>>() // channelId -> (epoch, seed)
    private val peerKeys = ConcurrentHashMap<String, MutableMap<String, SenderKeyEntry>>() // channel -> sender -> entry
    private val replayWindows = ConcurrentHashMap<String, ReplayWindow>() // channel|sender|epoch
    private val revokedSenders = ConcurrentHashMap.newKeySet<String>() // channel|sender
    private val localEpoch = ConcurrentHashMap<String, Long>() // channelId -> epoch
    private val sendSequences = ConcurrentHashMap<String, Long>() // channelId -> next seq

    override suspend fun ensureSenderKey(channelId: ChannelId, members: List<DeviceId>): Result<Unit> = mutex.withLock {
        try {
            val epoch = localEpoch.getOrPut(channelId.value) { 0L }
            if (!ownKeys.containsKey(channelId.value)) {
                ownKeys[channelId.value] = epoch to engine.randomBytes(32)
            }
            distributeToMembers(channelId, epoch)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun rotateEpoch(channelId: ChannelId): Result<Long> = mutex.withLock {
        try {
            val newEpoch = (localEpoch[channelId.value] ?: 0L) + 1
            localEpoch[channelId.value] = newEpoch
            // Fresh seed for the new epoch — new salt means nonces can never repeat.
            ownKeys[channelId.value] = newEpoch to engine.randomBytes(32)
            peerKeys[channelId.value]?.clear()
            distributeToMembers(channelId, newEpoch)
            Result.success(newEpoch)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun distributeToMembers(channelId: ChannelId, epoch: Long) {
        val (_, seed) = ownKeys.getValue(channelId.value)
        val me = identityManager.ensureIdentity().identity.deviceId
        val members = memberDirectory.memberDevices(channelId).filterNot { it == me }
        val envelopes = members.mapNotNull { peer ->
            val session = sessionManager.ensureSession(peer, null).getOrNull() ?: return@mapNotNull null
            val payload = buildSenderKeyPayload(channelId, epoch, me, seed)
            val aad = aadForEnvelope(channelId, epoch)
            val ciphertext = sessionManager.encryptForPeer(peer, payload, aad).getOrNull() ?: return@mapNotNull null
            KeyEnvelope(channelId, epoch, me, peer, ciphertext)
        }
        if (envelopes.isNotEmpty()) envelopeTransport.distribute(envelopes).getOrThrow()
    }

    override suspend fun encryptMedia(channelId: ChannelId, plaintextFrame: ByteArray): Result<MediaPacket> {
        return try {
            val (epoch, seed) = ownKeys[channelId.value]
                ?: return Result.failure(IllegalStateException("No sender key for $channelId"))
            val seq = nextSequence(channelId.value)
            val key = deriveKey(seed)
            val salt = deriveSalt(seed)
            val me = identityManager.ensureIdentity().identity.deviceId
            val senderBytes = MediaPacketCodec.deviceIdToBytes(me)
            val aad = NonceDeriver.mediaAad(epoch.toInt(), senderBytes, seq)
            val nonce = NonceDeriver.derive(seq, salt)
            val ciphertext = engine.aesGcmEncrypt(key, nonce, plaintextFrame, aad)
            Result.success(MediaPacket(epoch.toInt(), me, seq, ciphertext))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun decryptMedia(channelId: ChannelId, packet: MediaPacket): Result<ByteArray> {
        return try {
            val senderKey = peerKeys[channelId.value]?.get(packet.sender.value)
                ?: return Result.failure(IllegalStateException("No sender key for ${packet.sender} at epoch ${packet.epoch}"))
            if (senderKey.epoch != packet.epoch.toLong()) {
                return Result.failure(IllegalStateException("Stale epoch ${packet.epoch}; current ${senderKey.epoch}"))
            }
            if (revokedSenders.contains(revocationKey(channelId.value, packet.sender.value))) {
                return Result.failure(IllegalStateException("Sender ${packet.sender} revoked"))
            }
            val window = replayWindows.getOrPut(replayKey(channelId.value, packet.sender.value, packet.epoch)) {
                ReplayWindow(REPLAY_WINDOW)
            }
            synchronized(window) {
                if (!window.accept(packet.sequence)) {
                    return Result.failure(IllegalStateException("Replay detected: seq ${packet.sequence}"))
                }
            }
            val key = deriveKey(senderKey.seed)
            val salt = deriveSalt(senderKey.seed)
            val senderBytes = MediaPacketCodec.deviceIdToBytes(packet.sender)
            val aad = NonceDeriver.mediaAad(packet.epoch, senderBytes, packet.sequence)
            val nonce = NonceDeriver.derive(packet.sequence, salt)
            Result.success(engine.aesGcmDecrypt(key, nonce, packet.payload, aad))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun handleEnvelopeReceived(envelope: KeyEnvelope): Result<Unit> = mutex.withLock {
        try {
            val me = identityManager.ensureIdentity().identity.deviceId
            if (envelope.recipientDeviceId != me) return Result.success(Unit) // not for us; ignore
            val session = sessionManager.ensureSession(envelope.senderDeviceId, null).getOrNull()
                ?: return Result.failure(IllegalStateException("No session with ${envelope.senderDeviceId}"))
            val aad = aadForEnvelope(envelope.channelId, envelope.epoch)
            val plaintext = sessionManager.decryptFromPeer(envelope.senderDeviceId, envelope.ciphertext, aad)
                .getOrThrow()
            val (channel, epoch, sender, seed) = parseSenderKeyPayload(plaintext)
            peerKeys.getOrPut(channel.value) { ConcurrentHashMap() }[sender.value] =
                SenderKeyEntry(channel, epoch, sender, seed)
            localEpoch.putIfAbsent(channel.value, epoch)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun revokeSender(channelId: ChannelId, sender: DeviceId) {
        revokedSenders.add(revocationKey(channelId.value, sender.value))
    }

    fun currentEpoch(channelId: ChannelId): Long = localEpoch[channelId.value] ?: 0L

    // ---- payload / derivation helpers -------------------------------------------------

    /** channel_id UTF-8 | epoch(8) | sender UTF-8 | seed(32) */
    private fun buildSenderKeyPayload(channel: ChannelId, epoch: Long, sender: DeviceId, seed: ByteArray): ByteArray {
        val ch = channel.value.toByteArray(Charsets.UTF_8)
        val sd = sender.value.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + ch.size + 8 + 4 + sd.size + 32)
        buf.putInt(ch.size).put(ch).putLong(epoch).putInt(sd.size).put(sd).put(seed)
        return buf.array()
    }

    private fun parseSenderKeyPayload(bytes: ByteArray): KeyPayload {
        val buf = ByteBuffer.wrap(bytes)
        val chLen = buf.int
        val ch = ByteArray(chLen).also { buf.get(it) }
        val epoch = buf.long
        val sdLen = buf.int
        val sd = ByteArray(sdLen).also { buf.get(it) }
        val seed = ByteArray(32).also { buf.get(it) }
        return KeyPayload(ChannelId(String(ch, Charsets.UTF_8)), epoch, DeviceId(String(sd, Charsets.UTF_8)), seed)
    }

    private data class KeyPayload(val channel: ChannelId, val epoch: Long, val sender: DeviceId, val seed: ByteArray)

    private fun deriveKey(seed: ByteArray): ByteArray =
        engine.hkdfSha256(seed, ByteArray(32), INFO_KEY, 32)

    private fun deriveSalt(seed: ByteArray): ByteArray =
        engine.hkdfSha256(seed, ByteArray(32), INFO_SALT, 4)

    private fun nextSequence(channelKey: String): Long =
        sendSequences.merge(channelKey, 1L) { _, v -> v + 1 } ?: 1L

    private fun aadForEnvelope(channel: ChannelId, epoch: Long): ByteArray =
        "sk-envelope|${channel.value}|$epoch".toByteArray(Charsets.UTF_8)

    private fun replayKey(channel: String, sender: String, epoch: Long) = "$channel|$sender|$epoch"
    private fun revocationKey(channel: String, sender: String) = "$channel|$sender"

    private companion object {
        const val REPLAY_WINDOW = 128
        val INFO_KEY = "securetalk-sender-key".toByteArray(Charsets.UTF_8)
        val INFO_SALT = "securetalk-sender-salt".toByteArray(Charsets.UTF_8)
    }
}
