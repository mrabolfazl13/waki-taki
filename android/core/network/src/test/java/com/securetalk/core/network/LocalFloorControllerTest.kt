package com.securetalk.core.network

import com.securetalk.core.network.localhost.LocalFloorController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFloorControllerTest {

    private var time = 0L
    private val controller = LocalFloorController { time }

    @Test
    fun `first requester granted, second queued`() {
        assertTrue(controller.request("ch1", "devA").granted)
        val result = controller.request("ch1", "devB")
        assertFalse(result.granted)
        assertEquals(1, result.queuePosition)
    }

    @Test
    fun `release promotes next in queue`() {
        controller.request("ch1", "devA")
        controller.request("ch1", "devB")
        val next = controller.release("ch1", "devA")
        assertEquals("devB", next)
        assertEquals("devB", controller.state("ch1").holder)
    }

    @Test
    fun `lease expiry promotes next`() {
        controller.request("ch1", "devA")
        controller.request("ch1", "devB")
        time += LocalFloorController.LEASE_TTL_MS + 1
        val next = controller.onLeaseExpired("ch1")
        assertEquals("devB", next)
    }

    @Test
    fun `rate limit rejects after 10 requests in 10 seconds`() {
        repeat(10) { time += 100; controller.request("ch2", "devC") }
        val rejected = controller.request("ch2", "devC")
        assertEquals("rate_limited", rejected.reason)
    }

    @Test
    fun `queue depth 20 enforced`() {
        controller.request("ch1", "holder")
        repeat(20) { controller.request("ch1", "dev$it") }
        val overflow = controller.request("ch1", "dev21")
        assertEquals("queue_full", overflow.reason)
    }

    @Test
    fun `device leaving releases its floors`() {
        controller.request("ch1", "devA")
        val updates = controller.onDeviceLeft("devA")
        assertTrue(updates.containsKey("ch1"))
        assertEquals("idle", controller.state("ch1").state)
    }
}
