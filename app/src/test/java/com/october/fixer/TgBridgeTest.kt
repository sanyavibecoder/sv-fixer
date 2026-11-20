package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class TgBridgeTest {
    @Test fun workerDomainUsesItsExactManagedHostname() {
        assertEquals("my-proxy.my-account.workers.dev", TgBridge.workerHost("my-proxy.my-account.workers.dev"))
        assertEquals("tg.example.org", TgBridge.workerHost("example.org"))
    }

    @Test fun relayErrorsDoNotGuessQuotaOrExposeNetworkDetails() {
        assertEquals("http_429", TgBridge.relayFailure(TgBridge.RelayHttpException(429)))
        assertEquals("http_5xx", TgBridge.relayFailure(TgBridge.RelayHttpException(522)))
        assertEquals("timeout", TgBridge.relayFailure(SocketTimeoutException("user.example.org")))
        assertEquals("dns_failed", TgBridge.relayFailure(UnknownHostException("user.example.org")))
        assertFalse(TgBridge.relayErrorMessage("http_429").orEmpty().contains("квот"))
        assertNull(TgBridge.relayErrorMessage("ok"))
    }

    @Test fun payloadArmsOneDeadlineAndSuccessfulReplyCancelsIt() {
        val timers = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
        try {
            val outcomes = mutableListOf<String>()
            TgBridge.FirstReply(timers, 60_000L, outcomes::add).use { reply ->
                assertTrue(timers.queue.isEmpty())
                reply.expect()
                reply.expect()
                assertEquals(1, timers.queue.size)
                reply.complete("ok")
                assertTrue(timers.queue.isEmpty())
                reply.expect()
                reply.complete("timeout")
                assertTrue(timers.queue.isEmpty())
            }
            assertEquals(listOf("ok"), outcomes)
        } finally {
            timers.shutdownNow()
        }
    }

    @Test fun unansweredPayloadReportsTimeout() {
        val timers = ScheduledThreadPoolExecutor(1)
        try {
            val fired = CountDownLatch(1)
            var outcome: String? = null
            TgBridge.FirstReply(timers, 10L) { code -> outcome = code; fired.countDown() }.use { reply ->
                reply.expect()
                assertTrue(fired.await(2, TimeUnit.SECONDS))
                assertEquals("timeout", outcome)
            }
        } finally {
            timers.shutdownNow()
        }
    }
}
