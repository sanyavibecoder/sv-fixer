package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Test

class QuicPolicyTest {
    @Test
    fun dropsOnlyWhenOwnerIsAKnownFallbackApp() {
        val expected = mapOf(
            10100 to QuicPolicy.Route.ROBLOX,
            10101 to QuicPolicy.Route.TCP_FALLBACK,
            10102 to QuicPolicy.Route.UNKNOWN,
            -1 to QuicPolicy.Route.UNKNOWN,
        )
        for ((uid, route) in expected) {
            // If apps share a UID, keep Roblox connectivity.
            val policy = QuicPolicy(10100, setOf(10100, 10101), { 0 }) { source, destination ->
                assertEquals("10.111.0.1", source.address.hostAddress)
                assertEquals(50000, source.port)
                assertEquals("192.0.2.3", destination.address.hostAddress)
                assertEquals(443, destination.port)
                uid
            }
            assertEquals(route, policy.route(udp443()))
        }
    }

    @Test
    fun lookupFailureLeavesUdpReachable() {
        val policy = QuicPolicy(10100, setOf(10101), { 0 }) { _, _ -> throw SecurityException() }
        assertEquals(QuicPolicy.Route.UNKNOWN, policy.route(udp443()))
    }

    @Test
    fun rechecksCachedOwnerAfterTupleReuse() {
        var now = 10L
        var calls = 0
        var owner = 10101
        val policy = QuicPolicy(10100, setOf(10101), { now }) { _, _ -> calls++; owner }
        assertEquals(QuicPolicy.Route.TCP_FALLBACK, policy.route(udp443()))
        assertEquals(QuicPolicy.Route.TCP_FALLBACK, policy.route(udp443()))
        assertEquals(1, calls)

        owner = 10100
        assertEquals(QuicPolicy.Route.ROBLOX, policy.route(udp443(sourcePort = 50001)))
        now += 2000
        assertEquals(QuicPolicy.Route.ROBLOX, policy.route(udp443()))
        assertEquals(3, calls)
    }

    private fun udp443(sourcePort: Int = 50000) = UdpDns(
        4, byteArrayOf(10, 111, 0, 1), byteArrayOf(192.toByte(), 0, 2, 3), sourcePort, 443, byteArrayOf(1),
    )
}
