package io.github.traushb.actuali.services.sync

import io.github.traushb.actuali.services.L
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed class HLCError : Exception() {
    object ClockDrift : HLCError() {
        override val message: String get() = L("Maximum clock drift exceeded. Check this device's date and time settings.")
    }

    object CounterOverflow : HLCError() {
        override val message: String get() = L("Timestamp counter overflow.")
    }

    object InvalidTimestamp : HLCError() {
        override val message: String get() = L("Timestamp is not valid.")
    }
}

/**
 * Hybrid Logical Clock for CRDT sync. A mutex keeps timestamp generation
 * serial, as the Swift actor does.
 */
class HybridLogicalClock(
    node: String? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var millis: Long = 0
    private var counter: Int = 0
    val node: String = node ?: generateNodeId()
    private val mutex = Mutex()

    /** Maximum allowed clock drift (5 minutes, matching Actual). */
    private val maxDrift: Long = 5 * 60 * 1000

    /** Initialize from a saved timestamp (for restoring state). */
    constructor(timestamp: HLCTimestamp) : this(timestamp.node) {
        millis = timestamp.millis
        counter = timestamp.counter
    }

    suspend fun current(): HLCTimestamp = mutex.withLock { HLCTimestamp(millis, counter, node) }

    /**
     * Advance the clock to at least the given timestamp, keeping our node id.
     * Used to restore persisted state on startup (mirrors upstream setClock on
     * budget load) so that `current` is never behind the local message
     * high-water mark. Never moves the clock backwards.
     */
    suspend fun advance(timestamp: HLCTimestamp) = mutex.withLock {
        if (timestamp.millis > millis || (timestamp.millis == millis && timestamp.counter > counter)) {
            millis = timestamp.millis
            counter = timestamp.counter
        }
    }

    /** Generate a new timestamp for a local event. */
    suspend fun send(): HLCTimestamp = mutex.withLock {
        val now = nowMillis()
        // Calculate new logical time (never goes backward)
        val newMillis = maxOf(millis, now)
        // Increment counter if time didn't advance
        val newCounter = if (millis == newMillis) counter + 1 else 0
        if (newMillis - now > maxDrift) throw HLCError.ClockDrift
        if (newCounter > 0xffff) throw HLCError.CounterOverflow
        millis = newMillis
        counter = newCounter
        HLCTimestamp(millis, counter, node)
    }

    /** Merge a remote timestamp to maintain causality. */
    suspend fun receive(remote: HLCTimestamp): HLCTimestamp = mutex.withLock {
        val now = nowMillis()
        if (remote.millis - now > maxDrift) throw HLCError.ClockDrift

        // New logical time: max of local, remote, and wall clock
        val newMillis = maxOf(maxOf(millis, now), remote.millis)
        val newCounter = when {
            // All three at same time - take max counter + 1
            newMillis == millis && newMillis == remote.millis -> maxOf(counter, remote.counter) + 1
            // Local clock wins - increment local counter
            newMillis == millis -> counter + 1
            // Remote clock wins - increment remote counter
            newMillis == remote.millis -> remote.counter + 1
            // Wall clock wins - reset counter
            else -> 0
        }
        if (newMillis - now > maxDrift) throw HLCError.ClockDrift
        if (newCounter > 0xffff) throw HLCError.CounterOverflow
        millis = newMillis
        counter = newCounter
        HLCTimestamp(millis, counter, node)
    }

    companion object {
        /** A random 16-character hex node ID. */
        fun generateNodeId(): String = UUID.randomUUID().toString().replace("-", "").takeLast(16).lowercase()
    }
}
