package io.github.traushb.actuali.services.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridLogicalClockTest {
    private fun nowMillis() = System.currentTimeMillis()

    /**
     * A logical time slightly ahead of the wall clock but well inside the
     * 5-minute drift window, so send()/receive() take the same-millisecond
     * (counter-increment) path deterministically.
     */
    private fun aheadMillis() = nowMillis() + 60000

    @Test
    fun sendThrowsOnCounterOverflow() = runBlocking {
        val clock = HybridLogicalClock(HLCTimestamp(aheadMillis(), 0xffff, "aaaaaaaaaaaaaaaa"))
        assertThrows(HLCError.CounterOverflow::class.java) { runBlocking { clock.send() } }
        Unit
    }

    @Test
    fun sendSucceedsAtCounterBoundary() = runBlocking {
        val millis = aheadMillis()
        val clock = HybridLogicalClock(HLCTimestamp(millis, 0xfffe, "aaaaaaaaaaaaaaaa"))
        val ts = clock.send()
        assertEquals(0xffff, ts.counter)
        assertEquals(millis, ts.millis)
    }

    @Test
    fun receiveThrowsWhenRemoteCounterOverflows() {
        val clock = HybridLogicalClock("aaaaaaaaaaaaaaaa")
        val remote = HLCTimestamp(aheadMillis(), 0xffff, "bbbbbbbbbbbbbbbb")
        assertThrows(HLCError.CounterOverflow::class.java) { runBlocking { clock.receive(remote) } }
    }

    @Test
    fun receiveThrowsWhenLocalCounterOverflows() {
        val millis = aheadMillis()
        val clock = HybridLogicalClock(HLCTimestamp(millis, 0xffff, "aaaaaaaaaaaaaaaa"))
        assertThrows(HLCError.CounterOverflow::class.java) {
            runBlocking { clock.receive(HLCTimestamp(millis, 5, "bbbbbbbbbbbbbbbb")) }
        }
    }

    @Test
    fun receiveResetsCounterWhenWallClockWins() = runBlocking {
        // Remote at max counter but in the past: wall clock wins, counter resets to 0.
        val clock = HybridLogicalClock("aaaaaaaaaaaaaaaa")
        val remote = HLCTimestamp(nowMillis() - 60000, 0xffff, "bbbbbbbbbbbbbbbb")
        val ts = clock.receive(remote)
        assertEquals(0, ts.counter)
        assertTrue(ts > remote)
    }

    /**
     * Simulates the configure() seeding path: a fresh clock advanced to the
     * max pre-existing message timestamp. The first send() must sort strictly
     * after that maximum, including the same-millisecond case.
     */
    @Test
    fun firstSendAfterRelaunchSortsAfterSeededMaximum() = runBlocking {
        val maxSeen = HLCTimestamp(aheadMillis(), 7, "ffffffffffffffff")
        val clock = HybridLogicalClock("0000000000000000")
        clock.advance(maxSeen)
        val ts = clock.send()
        assertTrue(ts > maxSeen)
        assertEquals(maxSeen.millis, ts.millis)
        assertEquals(maxSeen.counter + 1, ts.counter)
    }

    @Test
    fun firstSendAfterRelaunchSortsAfterPastSeededMaximum() = runBlocking {
        val maxSeen = HLCTimestamp(nowMillis() - 60000, 42, "ffffffffffffffff")
        val clock = HybridLogicalClock("0000000000000000")
        clock.advance(maxSeen)
        val ts = clock.send()
        assertTrue(ts > maxSeen)
        assertTrue(ts.millis > maxSeen.millis)
    }

    @Test
    fun advanceRestoresCounterAndNeverMovesBackwards() = runBlocking {
        val clock = HybridLogicalClock("0000000000000000")
        clock.advance(HLCTimestamp(1_000_000, 9, "ffffffffffffffff"))
        var current = clock.current()
        assertEquals(1_000_000L, current.millis)
        assertEquals(9, current.counter)

        clock.advance(HLCTimestamp(1_000_000, 3, "ffffffffffffffff"))
        current = clock.current()
        assertEquals(9, current.counter)

        clock.advance(HLCTimestamp(999_999, 0xffff, "ffffffffffffffff"))
        current = clock.current()
        assertEquals(1_000_000L, current.millis)
        assertEquals(9, current.counter)
    }

    @Test
    fun sendAfterSeedingAtCounterBoundaryThrows() {
        val clock = HybridLogicalClock("0000000000000000")
        runBlocking { clock.advance(HLCTimestamp(aheadMillis(), 0xffff, "ffffffffffffffff")) }
        assertThrows(HLCError.CounterOverflow::class.java) { runBlocking { clock.send() } }
    }
}
