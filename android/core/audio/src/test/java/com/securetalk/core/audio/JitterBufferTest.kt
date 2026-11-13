package com.securetalk.core.audio

import com.securetalk.core.audio.jitter.JitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {

    @Test
    fun `bounded at max depth dropping oldest`() {
        val jb = JitterBuffer<Int>(minDepth = 2, maxDepth = 5)
        repeat(10) { jb.push(it) }
        assertEquals(5, jb.depth)
        // oldest (0..4) dropped, next pop is 5
        assertEquals(5, jb.pop())
    }

    @Test
    fun `underflow yields null plc signal`() {
        val jb = JitterBuffer<Int>()
        assertNull(jb.pop())
        jb.push(1)
        assertEquals(1, jb.pop())
        assertNull(jb.pop())
    }

    @Test
    fun `target depth adapts up on sustained underflow and stays bounded`() {
        val jb = JitterBuffer<Int>(minDepth = 2, maxDepth = 20)
        repeat(10) { assertNull(jb.pop()) }
        assertTrue(jb.target > 3)
        repeat(30) { jb.push(it) }
        assertTrue(jb.target <= 20)
    }
}
