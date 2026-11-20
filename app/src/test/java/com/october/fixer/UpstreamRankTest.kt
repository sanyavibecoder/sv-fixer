package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class UpstreamRankTest {

    private fun hedgeAfter(ms: Int): Int {
        val rank = UpstreamRank(3) { 0L }
        rank.ok(1, ms)
        rank.ok(0, ms + 1) // measured too, so the unmeasured 1000 ms default does not decide the leader
        rank.ok(2, ms + 2)
        return rank.hedgeMs()
    }

    @Test
    fun hedgeIsAdaptiveToTheLeader() {
        assertEquals(350, UpstreamRank(3) { 0L }.hedgeMs())
        assertEquals(350, hedgeAfter(100))
        assertEquals(600, hedgeAfter(400))
        assertEquals(1200, hedgeAfter(2000))
    }

    @Test
    fun keepsTheDefaultOrderWithoutData() {
        assertEquals(listOf(0, 1, 2, 3), UpstreamRank(4).order())
    }

    @Test
    fun fasterUpstreamsGoFirstAndFailedOnesLastFor30Seconds() {
        var clock = 1_000_000L
        val rank = UpstreamRank(4) { clock }
        rank.ok(2, 40)
        rank.ok(0, 200)
        assertEquals(listOf(2, 0, 1, 3), rank.order())
        rank.fail(2)
        assertEquals(listOf(0, 1, 3, 2), rank.order())
        clock += 29_000
        assertEquals(2, rank.order().last())
        clock += 2_000 // 31 s after the failure
        assertEquals(listOf(2, 0, 1, 3), rank.order())
    }

    @Test
    fun timeIsSmoothedWithAlpha03() {
        val rank = UpstreamRank(2) { 0L }
        rank.ok(0, 100)
        rank.ok(1, 150)
        assertEquals(listOf(0, 1), rank.order())
        rank.ok(0, 300) // 0.7 * 100 + 0.3 * 300 = 160
        assertEquals(listOf(1, 0), rank.order())
    }

    @Test
    fun successClearsTheFailure() {
        val rank = UpstreamRank(3) { 0L }
        rank.ok(1, 50)
        rank.fail(1)
        assertEquals(listOf(0, 2, 1), rank.order())
        rank.ok(1, 50)
        assertEquals(listOf(1, 0, 2), rank.order())
    }

    @Test
    fun survivesConcurrentUse() {
        val rank = UpstreamRank(7)
        val error = AtomicReference<Throwable>()
        val threads = (0 until 8).map { t ->
            Thread {
                try {
                    repeat(1000) { n ->
                        val i = (t + n) % 7
                        if (n % 3 == 0) rank.fail(i) else rank.ok(i, n % 400)
                        assertEquals((0 until 7).toList(), rank.order().sorted())
                    }
                } catch (e: Throwable) {
                    error.set(e)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertNull(error.get())
        assertEquals((0 until 7).toList(), rank.order().sorted())
    }
}
