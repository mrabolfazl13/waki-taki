package com.securetalk.core.network.localhost

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Authoritative floor controller for the LOCAL HOST (ARCHITECTURE §4).
 * Single speaker, 60 s lease TTL, 3 s rule is client-side, rate limit 10 requests
 * /10 s/device, queue depth 20. This is a faithful port of the server rules so
 * offline mode behaves identically. Pure Kotlin — unit-testable on the JVM.
 */
class LocalFloorController(private val clock: () -> Long = System::currentTimeMillis) {

    data class FloorState(
        val channelId: String,
        val state: String, // idle | speaking
        val holder: String?,
        val leaseExpiresMs: Long,
    )

    data class RequestResult(val granted: Boolean, val queuePosition: Int?, val reason: String?)

    private data class ChannelFloor(
        var holder: String? = null,
        var leaseExpiresAt: Long = 0,
        val queue: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
        val requestTimestamps: MutableMap<String, MutableList<Long>> = HashMap(),
    )

    private val floors = HashMap<String, ChannelFloor>()

    @Synchronized
    fun request(channelId: String, deviceId: String): RequestResult {
        val floor = floors.getOrPut(channelId) { ChannelFloor() }
        val now = clock()

        // rate limit 10 requests / 10 s / device
        val stamps = floor.requestTimestamps.getOrPut(deviceId) { mutableListOf() }
        stamps.removeAll { it < now - RATE_WINDOW_MS }
        if (stamps.size >= RATE_LIMIT) {
            return RequestResult(false, null, "rate_limited")
        }
        stamps.add(now)

        expireIfDue(floor, now)

        val current = floor.holder
        if (current == null) {
            grant(channelId, floor, deviceId, now)
            return RequestResult(true, null, null)
        }
        if (current == deviceId) {
            return RequestResult(true, null, null) // already holding
        }
        if (floor.queue.contains(deviceId)) {
            return RequestResult(false, positionOf(floor, deviceId) + 1, "queued")
        }
        if (floor.queue.size >= QUEUE_DEPTH) {
            return RequestResult(false, null, "queue_full")
        }
        floor.queue.add(deviceId)
        return RequestResult(false, floor.queue.size, "queued")
    }

    @Synchronized
    fun release(channelId: String, deviceId: String): String? {
        val floor = floors.getOrPut(channelId) { ChannelFloor() }
        if (floor.holder != deviceId) return null
        return promoteNext(channelId, floor, clock())
    }

    /** Called by the host on audio-activity heartbeat to renew the lease (§4). */
    @Synchronized
    fun renewLease(channelId: String, deviceId: String): Boolean {
        val floor = floors[channelId] ?: return false
        if (floor.holder != deviceId) return false
        floor.leaseExpiresAt = clock() + LEASE_TTL_MS
        return true
    }

    /** Returns the next holder to grant, or null; caller broadcasts floor.granted/event. */
    @Synchronized
    fun onLeaseExpired(channelId: String): String? {
        val floor = floors[channelId] ?: return null
        if (floor.holder == null) return null
        return promoteNext(channelId, floor, clock())
    }

    @Synchronized
    fun onDeviceLeft(deviceId: String): Map<String, String?> {
        // remove from queues; release held floors; return channelId -> new holder (or null)
        val updates = mutableMapOf<String, String?>()
        floors.entries.forEach { (channelId, floor) ->
            floor.queue.remove(deviceId)
            if (floor.holder == deviceId) {
                updates[channelId] = promoteNext(channelId, floor, clock())
            }
        }
        return updates
    }

    /** channelId -> current holder, for authorization checks. */
    @Synchronized
    fun holders(): Map<String, String> {
        val now = clock()
        return floors.entries.mapNotNull { (id, floor) ->
            if (floor.holder == null || now > floor.leaseExpiresAt) null else id to floor.holder!!
        }.toMap()
    }

    @Synchronized
    fun state(channelId: String): FloorState {
        val floor = floors.getOrPut(channelId) { ChannelFloor() }
        expireIfDue(floor, clock())
        return FloorState(
            channelId,
            if (floor.holder == null) "idle" else "speaking",
            floor.holder,
            floor.leaseExpiresAt,
        )
    }

    private fun grant(channelId: String, floor: ChannelFloor, deviceId: String, now: Long) {
        floor.holder = deviceId
        floor.leaseExpiresAt = now + LEASE_TTL_MS
    }

    private fun promoteNext(channelId: String, floor: ChannelFloor, now: Long): String? {
        val previous = floor.holder
        floor.holder = null
        floor.leaseExpiresAt = 0
        val next = floor.queue.poll()
        if (next != null) {
            grant(channelId, floor, next, now)
        }
        return next
    }

    private fun expireIfDue(floor: ChannelFloor, now: Long) {
        // expiry handled by caller via onLeaseExpired; here we only need queue validity
        if (floor.holder != null && now > floor.leaseExpiresAt) {
            // treat as expired; caller must call onLeaseExpired to promote
        }
    }

    private fun positionOf(floor: ChannelFloor, deviceId: String): Int {
        var i = 0
        for (item in floor.queue) {
            if (item == deviceId) return i
            i++
        }
        return -1
    }

    companion object {
        const val LEASE_TTL_MS = 60_000L
        private const val RATE_LIMIT = 10
        private const val RATE_WINDOW_MS = 10_000L
        const val QUEUE_DEPTH = 20
    }
}
