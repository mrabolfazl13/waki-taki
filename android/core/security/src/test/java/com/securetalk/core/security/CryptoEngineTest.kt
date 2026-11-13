package com.securetalk.core.security

import com.securetalk.core.security.engine.BcCryptoEngine
import com.securetalk.core.security.engine.NonceDeriver
import com.securetalk.core.security.engine.ReplayWindow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoEngineTest {

    private val engine = BcCryptoEngine()

    @Test
    fun `x25519 agreement matches both sides`() {
        val (privA, pubA) = engine.generateX25519KeyPair()
        val (privB, pubB) = engine.generateX25519KeyPair()
        val ab = engine.x25519SharedSecret(privA, pubB)
        val ba = engine.x25519SharedSecret(privB, pubA)
        assertArrayEquals(ab, ba)
        assertEquals(32, ab.size)
    }

    @Test
    fun `ed25519 sign verify round trip`() {
        val (seed, pub) = engine.generateEd25519KeyPair()
        val msg = "hello securetalk".toByteArray()
        val sig = engine.ed25519Sign(seed, msg)
        assertTrue(engine.ed25519Verify(pub, msg, sig))
        assertFalse(engine.ed25519Verify(pub, msg + 1, sig))
    }

    @Test
    fun `aes gcm round trip with aad`() {
        val key = engine.randomBytes(32)
        val nonce = engine.randomBytes(12)
        val aad = "epoch|sender|seq".toByteArray()
        val pt = ByteArray(960) { (it % 251).toByte() }
        val ct = engine.aesGcmEncrypt(key, nonce, pt, aad)
        assertArrayEquals(pt, engine.aesGcmDecrypt(key, nonce, ct, aad))
    }

    @Test(expected = Exception::class)
    fun `aes gcm tampered aad fails`() {
        val key = engine.randomBytes(32)
        val nonce = engine.randomBytes(12)
        val ct = engine.aesGcmEncrypt(key, nonce, "data".toByteArray(), "aad".toByteArray())
        engine.aesGcmDecrypt(key, nonce, ct, "wrong".toByteArray())
    }

    @Test
    fun `nonce never repeats for salt+seq and differs per salt`() {
        val salt = engine.randomBytes(4)
        val n1 = NonceDeriver.derive(1, salt)
        val n2 = NonceDeriver.derive(2, salt)
        assertFalse(n1.contentEquals(n2))
        val otherSalt = engine.randomBytes(4)
        assertFalse(n1.contentEquals(NonceDeriver.derive(1, otherSalt)))
        // monotonic counter embedded
        assertArrayEquals(n1, NonceDeriver.derive(1, salt))
    }
}

class ReplayWindowTest {

    @Test
    fun `accepts in order and out of order within window`() {
        val w = ReplayWindow(128)
        assertTrue(w.accept(1)); assertTrue(w.accept(2))
        assertTrue(w.accept(2).not()) // replay
        assertTrue(w.accept(100))
        assertTrue(w.accept(50)) // still in window
        assertFalse(w.accept(1)) // evicted
    }

    @Test
    fun `rejects sequences too far in the past`() {
        val w = ReplayWindow(128)
        repeat(200) { w.accept(it.toLong()) }
        assertFalse(w.accept(0))
        assertTrue(w.accept(199)) // new (not replay)
        assertFalse(w.accept(199)) // replay
        assertTrue(w.accept(201))
    }
}
