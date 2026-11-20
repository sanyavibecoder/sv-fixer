package com.october.fixer

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Hard limit for one DNS query, all upstreams together. */
const val DNS_DEADLINE_MS = 6000

/** Shortest (and default, without measurements) time the leading upstream gets alone before the next one starts. */
const val HEDGE_MS = 350

/**
 * Orders [size] upstreams by smoothed response time. Failed ones go last for 30 s, unmeasured ones count as
 * 1000 ms. Shared by all DNS worker threads of one network.
 */
class UpstreamRank(private val size: Int, private val now: () -> Long = System::currentTimeMillis) {
    private val ewma = DoubleArray(size) { Double.NaN }
    private val failedUntil = LongArray(size)

    @Synchronized
    fun order(): List<Int> {
        val at = now()
        return (0 until size).sortedWith(
            compareBy<Int>({ if (failedUntil[it] > at) 1 else 0 }, { if (ewma[it].isNaN()) UNMEASURED_MS else ewma[it] }, { it })
        )
    }

    /**
     * How long the leader (first of [order]) gets alone before the next upstream is hedged in:
     * 1.5 x its smoothed time, clamped to [HEDGE_MS]..[MAX_HEDGE_MS]; [HEDGE_MS] while it has no measurement.
     */
    @Synchronized
    fun hedgeMs(): Int {
        val leader = order().firstOrNull() ?: return HEDGE_MS
        val e = ewma[leader]
        if (e.isNaN()) return HEDGE_MS
        return (HEDGE_FACTOR * e).toInt().coerceIn(HEDGE_MS, MAX_HEDGE_MS)
    }

    @Synchronized
    fun ok(index: Int, ms: Int) {
        ewma[index] = if (ewma[index].isNaN()) ms.toDouble() else ALPHA * ms + (1 - ALPHA) * ewma[index]
        failedUntil[index] = 0
    }

    @Synchronized
    fun fail(index: Int) {
        failedUntil[index] = now() + FAIL_PENALTY_MS
    }

    private companion object {
        const val ALPHA = 0.3
        const val HEDGE_FACTOR = 1.5
        const val MAX_HEDGE_MS = 1200
        const val UNMEASURED_MS = 1000.0
        const val FAIL_PENALTY_MS = 30_000L
    }
}

/** Races upstreams: the leader first, the others hedged in as time passes, all under one deadline. */
object UpstreamRace {
    class Result(val reply: ByteArray?, val index: Int, val accepted: Boolean)

    /**
     * Separate from the DNS worker pool that calls [run]. Bounded; a saturated pool rejects and [run] retries.
     * Threads are daemons and end by themselves, because every attempt has its own network timeout.
     */
    private val shared: Executor = ThreadPoolExecutor(
        0, 128, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { task -> Thread(task, "dns-race").apply { isDaemon = true } },
    )

    private class Done(val index: Int, val reply: ByteArray?)

    /**
     * [upstreams] are indexed like [order]; each gets the milliseconds left before the deadline (at least 1) as its
     * network timeout and returns a reply, null or throws. Returns the first reply [accept] approves, else the first
     * unapproved reply seen (last resort), else a Result with a null reply. Never waits longer than [deadlineMs].
     * Attempts that lose the race keep running until their own timeout and are ignored.
     */
    fun run(
        order: List<Int>,
        upstreams: List<(Int) -> ByteArray?>,
        accept: (ByteArray) -> Boolean,
        hedgeMs: Int = HEDGE_MS,
        deadlineMs: Int = DNS_DEADLINE_MS,
        executor: Executor = shared,
    ): Result {
        val begin = System.nanoTime()
        fun elapsed() = (System.nanoTime() - begin) / 1_000_000
        val done = ArrayBlockingQueue<Done>(order.size.coerceAtLeast(1))
        var next = 0
        var running = 0
        var fallback: Done? = null
        var startAt = 0L // when the next upstream may be started

        while (true) {
            val now = elapsed()
            if (now >= deadlineMs) break
            if (next < order.size && now >= startAt) {
                val index = order[next]
                val left = (deadlineMs - now).toInt().coerceAtLeast(1)
                try {
                    executor.execute {
                        val reply = try { upstreams[index](left) } catch (_: Throwable) { null }
                        done.add(Done(index, reply))
                    }
                    next++
                    running++
                    startAt = now + hedgeMs
                } catch (_: RejectedExecutionException) {
                    startAt = now + minOf(hedgeMs, BUSY_RETRY_MS)
                }
                continue
            }
            if (running == 0 && next >= order.size) break
            val wakeAt = if (next < order.size) minOf(startAt, deadlineMs.toLong()) else deadlineMs.toLong()
            val finished = done.poll((wakeAt - now).coerceAtLeast(1), TimeUnit.MILLISECONDS) ?: continue
            running--
            val reply = finished.reply
            if (reply != null) {
                if (accept(reply)) return Result(reply, finished.index, true)
                if (fallback == null) fallback = finished
            }
            startAt = 0 // a finished attempt without a usable answer: start the next one at once
        }
        return Result(fallback?.reply, fallback?.index ?: -1, false)
    }

    private const val BUSY_RETRY_MS = 50
}
