package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpstreamRaceTest {

    private val good = byteArrayOf(1)
    private val other = byteArrayOf(2)
    private val empty = byteArrayOf(0)
    private val accept = { r: ByteArray -> r[0].toInt() != 0 }

    private fun after(ms: Long, reply: ByteArray?): (Int) -> ByteArray? = { Thread.sleep(ms); reply }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val at = System.nanoTime()
        val value = block()
        return Pair(value, (System.nanoTime() - at) / 1_000_000)
    }

    @Test
    fun fastFirstUpstreamWinsWithoutStartingOthers() {
        val started = AtomicInteger()
        val slow: (Int) -> ByteArray? = { started.incrementAndGet(); Thread.sleep(1500); other }
        val (result, ms) = timed {
            UpstreamRace.run(listOf(0, 1, 2), listOf(after(20, good), slow, slow), accept, hedgeMs = 300, deadlineMs = 2000)
        }
        assertSame(good, result.reply)
        assertEquals(0, result.index)
        assertTrue(result.accepted)
        assertTrue("took $ms ms", ms < 250)
        assertEquals(0, started.get())
    }

    @Test
    fun followsTheGivenOrder() {
        val result = UpstreamRace.run(listOf(2, 0, 1), listOf(after(20, other), after(20, other), after(20, good)), accept, 300, 2000)
        assertSame(good, result.reply)
        assertEquals(2, result.index)
    }

    @Test
    fun slowFirstUpstreamIsOvertakenByTheHedge() {
        val loserDone = CountDownLatch(1)
        val first: (Int) -> ByteArray? = { Thread.sleep(900); loserDone.countDown(); other }
        val (result, ms) = timed {
            UpstreamRace.run(listOf(0, 1), listOf(first, after(20, good)), accept, hedgeMs = 150, deadlineMs = 2000)
        }
        assertSame(good, result.reply)
        assertEquals(1, result.index)
        assertTrue("took $ms ms", ms in 150..600)
        // the loser finishes on its own later and cannot change what was returned
        assertTrue(loserDone.await(2, TimeUnit.SECONDS))
        assertSame(good, result.reply)
        assertEquals(1, result.index)
    }

    @Test
    fun failureStartsTheNextUpstreamAtOnce() {
        val (result, ms) = timed {
            UpstreamRace.run(listOf(0, 1, 2), listOf(after(10, null), after(10, null), after(10, good)), accept, hedgeMs = 1000, deadlineMs = 3000)
        }
        assertSame(good, result.reply)
        assertEquals(2, result.index)
        assertTrue("took $ms ms", ms < 500)
    }

    @Test
    fun exceptionIsAFailureAndDoesNotKillTheRace() {
        val broken: (Int) -> ByteArray? = { throw IllegalStateException("boom") }
        val (result, ms) = timed {
            UpstreamRace.run(listOf(0, 1), listOf(broken, after(10, good)), accept, hedgeMs = 1000, deadlineMs = 3000)
        }
        assertSame(good, result.reply)
        assertEquals(1, result.index)
        assertTrue("took $ms ms", ms < 500)
    }

    @Test
    fun hangingUpstreamsEndAtTheDeadline() {
        val timeouts = Collections.synchronizedList(ArrayList<Int>())
        val hanging: (Int) -> ByteArray? = { timeouts.add(it); Thread.sleep(3000); good }
        val (result, ms) = timed {
            UpstreamRace.run((0 until 7).toList(), List(7) { hanging }, accept, hedgeMs = 50, deadlineMs = 600)
        }
        assertNull(result.reply)
        assertFalse(result.accepted)
        assertTrue("took $ms ms", ms in 600..1100)
        assertEquals(7, timeouts.size)
        // each attempt is told how much of the deadline is left
        assertTrue(timeouts.toString(), timeouts.all { it in 1..600 })
        assertTrue(timeouts.toString(), timeouts.min() <= 350)
    }

    @Test
    fun unacceptedReplyIsTheLastResort() {
        val (result, ms) = timed {
            UpstreamRace.run(listOf(0, 1, 2), listOf(after(10, empty), after(10, null), after(10, byteArrayOf(0))), accept, hedgeMs = 1000, deadlineMs = 3000)
        }
        assertSame(empty, result.reply) // the first one seen
        assertEquals(0, result.index)
        assertFalse(result.accepted)
        assertTrue("took $ms ms", ms < 500) // everything answered: no reason to wait for the deadline
    }

    @Test
    fun acceptedReplyBeatsAnEarlierUnacceptedOne() {
        val result = UpstreamRace.run(listOf(0, 1), listOf(after(10, empty), after(10, good)), accept, 1000, 3000)
        assertSame(good, result.reply)
        assertTrue(result.accepted)
    }

    @Test
    fun unacceptedReplyIsReturnedAtTheDeadlineWhenTheRestHang() {
        val (result, ms) = timed {
            UpstreamRace.run(listOf(0, 1), listOf(after(10, empty), after(3000, good)), accept, hedgeMs = 50, deadlineMs = 500)
        }
        assertSame(empty, result.reply)
        assertFalse(result.accepted)
        assertTrue("took $ms ms", ms in 500..1000)
    }

    @Test
    fun busyExecutorDoesNotLoseAnUpstream() {
        val rejected = AtomicInteger()
        val executor = Executor { task ->
            if (rejected.getAndIncrement() < 2) throw RejectedExecutionException()
            Thread(task).apply { isDaemon = true }.start()
        }
        val (result, ms) = timed {
            UpstreamRace.run(listOf(0), listOf(after(10, good)), accept, hedgeMs = 50, deadlineMs = 2000, executor = executor)
        }
        assertSame(good, result.reply)
        assertTrue("took $ms ms", ms in 100..600)
    }

    @Test
    fun concurrentRacesDoNotBlockEachOther() {
        // 8 resolver threads, every query hedging over 7 hanging upstreams
        val results = Collections.synchronizedList(ArrayList<Long>())
        val threads = (0 until 8).map {
            Thread {
                val (result, ms) = timed {
                    UpstreamRace.run((0 until 7).toList(), List(7) { after(1500, null) }, accept, hedgeMs = 50, deadlineMs = 600)
                }
                if (result.reply == null) results.add(ms)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(8, results.size)
        assertTrue(results.toString(), results.all { it in 600..1100 })
    }
}
