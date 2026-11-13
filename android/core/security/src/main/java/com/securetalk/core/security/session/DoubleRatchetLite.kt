package com.securetalk.core.security.session

import com.securetalk.core.security.engine.CryptoEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * Double-ratchet-LITE (ARCHITECTURE §3.2): a symmetric-chain ratchet seeded by the
 * X3DH shared secret. Both sides derive the same key sequence:
 *
 *   chainKey_0 = HKDF(SK, info="chain-root")
 *   messageKey_n = HKDF(chainKey_n, info="message", n)
 *   chainKey_{n+1} = HKDF(chainKey_n, info="chain")
 *
 * Every message carries its sequence number; receivers cache derived keys for a
 * bounded out-of-order window. Forward secrecy comes from chain advancement and
 * from re-running X3DH (fresh ephemeral keys) whenever a session re-establishes.
 *
 * Note: this is not a full Signal DH ratchet — documented trade-off in
 * docs/DECISIONS.md (D3). Header/body separation still prevents naive replay
 * across sessions because the chain is seeded per-session.
 */
class DoubleRatchetLite(private val engine: CryptoEngine) {

    private class ChainState(var chainKey: ByteArray, var counter: Long = 0)

    private val sendChains = ConcurrentHashMap<String, ChainState>()
    private val recvChains = ConcurrentHashMap<String, ChainState>()
    private val messageKeys = ConcurrentHashMap<String, MutableMap<Long, ByteArray>>()

    companion object {
        private const val MAX_OUT_OF_ORDER = 64
        private const val INFO_CHAIN_ROOT = "securetalk-chain-root"
        private const val INFO_CHAIN = "securetalk-chain"
        private const val INFO_MESSAGE = "securetalk-message"
    }

    fun initializeSend(peer: String, sharedSecret: ByteArray) {
        val ck = engine.hkdfSha256(sharedSecret, ByteArray(32), INFO_CHAIN_ROOT.toByteArray(), 32)
        sendChains[peer] = ChainState(ck, 0)
    }

    fun initializeRecv(peer: String, sharedSecret: ByteArray) {
        val ck = engine.hkdfSha256(sharedSecret, ByteArray(32), INFO_CHAIN_ROOT.toByteArray(), 32)
        recvChains[peer] = ChainState(ck, 0)
        messageKeys[peer] = ConcurrentHashMap()
    }

    /** Derives the next message key and advances the sending chain. */
    fun nextMessageKey(peer: String): Pair<Long, ByteArray> {
        val chain = sendChains[peer] ?: throw IllegalStateException("no send chain for $peer")
        val n = chain.counter
        val mk = engine.hkdfSha256(chain.chainKey, ByteArray(32), INFO_MESSAGE.toByteArray(), 32)
        chain.chainKey = engine.hkdfSha256(chain.chainKey, ByteArray(32), INFO_CHAIN.toByteArray(), 32)
        chain.counter = n + 1
        return n to mk
    }

    /** Message key for [sequence], caching ahead for out-of-order delivery. */
    fun messageKeyFor(peer: String, sequence: Long): ByteArray {
        val cached = messageKeys[peer]?.remove(sequence)
        if (cached != null) return cached
        val chain = recvChains[peer] ?: throw IllegalStateException("no recv chain for $peer")
        if (sequence < chain.counter) throw IllegalStateException("sequence already consumed: $sequence")
        if (sequence - chain.counter > MAX_OUT_OF_ORDER) throw IllegalStateException("out-of-order window exceeded")
        var mk: ByteArray? = null
        while (chain.counter <= sequence) {
            val key = engine.hkdfSha256(chain.chainKey, ByteArray(32), INFO_MESSAGE.toByteArray(), 32)
            messageKeys.getOrPut(peer) { ConcurrentHashMap() }[chain.counter] = key
            chain.chainKey = engine.hkdfSha256(chain.chainKey, ByteArray(32), INFO_CHAIN.toByteArray(), 32)
            chain.counter++
            if (chain.counter == sequence + 1) mk = key
        }
        return mk ?: throw IllegalStateException("failed to derive key for $sequence")
    }

    fun drop(peer: String) {
        sendChains.remove(peer)
        recvChains.remove(peer)
        messageKeys.remove(peer)
    }
}
