package com.securetalk.core.audio.jitter

/**
 * Adaptive, bounded jitter buffer over encoded frames (pure Kotlin, unit-testable).
 *
 * - Target depth adapts between [minDepth] and [maxDepth] frames (20 ms each),
 *   bounded at 400 ms (20 frames) max per spec.
 * - [pop] returns the next frame; when empty it returns null (caller renders a
 *   PLC/silence frame and counts a concealment event).
 * - Persistent underflow increases the target depth; sustained overflow (fast
 *   drain streak without underflow) decreases it.
 */
class JitterBuffer<T>(
    private val minDepth: Int = 2,
    private val maxDepth: Int = 20, // 20 x 20ms = 400 ms ceiling
) {

    private val queue = ArrayDeque<T>()
    private var targetDepth = 3
    private var underflowStreak = 0
    private var overflowStreak = 0

    val depth: Int get() = queue.size
    val target: Int get() = targetDepth
    val isPriming: Boolean get() = queue.size < targetDepth

    fun push(item: T) {
        if (queue.size >= maxDepth) {
            queue.removeFirst() // drop oldest to keep latency bounded
            overflowStreak++
        } else {
            overflowStreak = 0
        }
        queue.addLast(item)
    }

    /** Returns next item, or null when buffering/empty (PLC event). */
    fun pop(): T? {
        val item = if (queue.isEmpty()) {
            underflowStreak++
            null
        } else {
            underflowStreak = maxOf(underflowStreak - 1, 0)
            queue.removeFirst()
        }
        adapt()
        return item
    }

    private fun adapt() {
        if (underflowStreak >= 3 && targetDepth < maxDepth) {
            targetDepth++
            underflowStreak = 0
        } else if (overflowStreak >= 5 && targetDepth > minDepth) {
            targetDepth--
            overflowStreak = 0
        }
    }

    fun clear() {
        queue.clear()
        underflowStreak = 0
        overflowStreak = 0
    }
}
