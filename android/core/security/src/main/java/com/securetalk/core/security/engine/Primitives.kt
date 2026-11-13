package com.securetalk.core.security.engine

import java.nio.ByteBuffer

/**
 * Nonce derivation per ARCHITECTURE §3.3:
 * 12-byte nonce = big-endian sequence counter XOR 4-byte per-sender salt (repeated).
 * Never reused for a key: the salt changes on every sender-key rotation (new epoch).
 */
object NonceDeriver {

    fun counterBytes(sequence: Long): ByteArray {
        val counter = ByteArray(12)
        // 64-bit big-endian sequence in the low 8 bytes; high 4 bytes zero.
        ByteBuffer.wrap(counter, 4, 8).putLong(sequence)
        return counter
    }

    fun derive(sequence: Long, salt: ByteArray): ByteArray {
        require(salt.size == 4) { "salt must be 4 bytes" }
        val counter = counterBytes(sequence)
        for (i in counter.indices) {
            counter[i] = (counter[i].toInt() xor salt[i % salt.size].toInt()).toByte()
        }
        return counter
    }

    /** AAD = epoch(4 BE) || sender_device_id(16) || sequence(4 BE) — matches the wire header. */
    fun mediaAad(epoch: Int, senderBytes: ByteArray, sequence: Long): ByteArray {
        require(senderBytes.size == 16)
        val aad = ByteArray(24)
        ByteBuffer.wrap(aad).putInt(epoch).put(senderBytes).putInt(sequence.toInt())
        return aad
    }
}

/**
 * Sliding replay window (ARCHITECTURE §3.3): receivers reject packets with a
 * sequence number already seen; window size 128 as specified.
 *
 * Not thread-safe by design — callers synchronize externally.
 */
class ReplayWindow(private val windowSize: Int = 128) {

    private var highest: Long = -1
    private val seen = HashSet<Long>()

    /** Returns true if [sequence] is new (and marks it seen); false for replays. */
    fun accept(sequence: Long): Boolean {
        if (sequence > highest) {
            val cutoff = sequence - windowSize
            seen.removeAll { it < cutoff }
            highest = sequence
            seen.add(sequence)
            return true
        }
        if (sequence <= highest - windowSize) return false // too old
        return seen.add(sequence)
    }

    fun reset() {
        highest = -1
        seen.clear()
    }
}
