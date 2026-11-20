package com.october.fixer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketUtilsTest {

    private val dnsQuery: ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
        7, 101, 120, 97, 109, 112, 108, 101, // "example"
        3, 99, 111, 109, 0,                  // "com"
        0, 1, 0, 1,
    )

    private fun port(p: ByteArray, o: Int) = ((p[o].toInt() and 0xFF) shl 8) or (p[o + 1].toInt() and 0xFF)

    private fun buildV4Query(): ByteArray {
        val ulen = 8 + dnsQuery.size
        val p = ByteArray(20 + ulen)
        p[0] = 0x45
        p[2] = (p.size shr 8).toByte(); p[3] = p.size.toByte()
        p[8] = 64; p[9] = 17
        byteArrayOf(10, 111, 0, 1).copyInto(p, 12) // src
        byteArrayOf(10, 111, 0, 2).copyInto(p, 16) // dst
        val c = PacketUtils.checksum(p, 0, 20)
        p[10] = (c shr 8).toByte(); p[11] = c.toByte()
        p[20] = 0xC3.toByte(); p[21] = 0x50 // src port 50000
        p[22] = 0; p[23] = 53               // dst port 53
        p[24] = (ulen shr 8).toByte(); p[25] = ulen.toByte()
        dnsQuery.copyInto(p, 28)
        return p
    }

    @Test
    fun ipv4ParseAndReply() {
        val pkt = buildV4Query()
        val req = PacketUtils.parse(pkt, pkt.size)
        assertNotNull(req)
        req!!
        assertEquals(4, req.version)
        assertEquals(50000, req.srcPort)
        assertEquals(53, req.dstPort)
        assertArrayEquals(dnsQuery, req.payload)

        val answer = dnsQuery.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte() }
        val reply = PacketUtils.buildReply(req, answer)

        assertArrayEquals(byteArrayOf(10, 111, 0, 2), reply.copyOfRange(12, 16)) // new src = old dst
        assertArrayEquals(byteArrayOf(10, 111, 0, 1), reply.copyOfRange(16, 20)) // new dst = old src
        assertEquals(53, port(reply, 20))
        assertEquals(50000, port(reply, 22))
        assertEquals(20 + 8 + answer.size, reply.size)
        assertEquals(0, PacketUtils.checksum(reply, 0, 20)) // valid IPv4 header checksum
        assertArrayEquals(answer, reply.copyOfRange(28, reply.size))
        assertNull(PacketUtils.parse(reply, reply.size)) // reply is not destined to port 53
        assertEquals(50000, PacketUtils.parseUdp(reply, reply.size)!!.dstPort) // but is still UDP
    }

    @Test
    fun servfailSetsFlags() {
        val r = PacketUtils.servfail(dnsQuery)
        assertEquals(0x12, r[0].toInt())
        assertEquals(0x34, r[1].toInt())
        assertTrue((r[2].toInt() and 0x80) != 0)
        assertEquals(2, r[3].toInt() and 0x0F)
        assertEquals(dnsQuery.size, r.size)
    }

    @Test
    fun cacheRewritesId() {
        val cache = DnsCache()
        val resp = dnsQuery.copyOf().also { it[2] = 0x81.toByte() }
        cache.put(dnsQuery, resp)
        val q2 = dnsQuery.copyOf().also { it[0] = 0x77; it[1] = 0x66 }
        val got = cache.get(q2)!!
        assertEquals(0x77, got[0].toInt())
        assertEquals(0x66, got[1].toInt())
    }

    /** A response to [dnsQuery] with one A record per TTL, names as compression pointers. */
    private fun response(vararg ttls: Long): ByteArray {
        var r = dnsQuery.copyOf().also { it[2] = 0x81.toByte(); it[3] = 0x80.toByte(); it[7] = ttls.size.toByte() }
        for ((n, ttl) in ttls.withIndex()) {
            r += byteArrayOf(
                0xC0.toByte(), 12, 0, 1, 0, 1,
                (ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte(),
                0, 4, 10, 0, 0, n.toByte(),
            )
        }
        return r
    }

    private fun cachedFor(resp: ByteArray, ms: Long): Boolean {
        val cache = DnsCache()
        cache.put(dnsQuery, resp, now = 1_000_000)
        return cache.get(dnsQuery, now = 1_000_000 + ms) != null
    }

    @Test
    fun cacheLifetimeFollowsTheResponseTtlWithin30To600Seconds() {
        val short = response(5)
        assertTrue(cachedFor(short, 30_000)) // TTL 5 still lives 30 s
        assertFalse(cachedFor(short, 30_001))
        val long = response(3600)
        assertTrue(cachedFor(long, 600_000)) // TTL 3600 is cut to 600 s
        assertFalse(cachedFor(long, 600_001))
        val normal = response(120)
        assertTrue(cachedFor(normal, 120_000))
        assertFalse(cachedFor(normal, 120_001))
    }

    @Test
    fun cacheLifetimeIsTheSmallestAnswerTtl() {
        val r = response(300, 90, 3600)
        assertTrue(cachedFor(r, 90_000))
        assertFalse(cachedFor(r, 90_001))
        assertEquals(600_000, PacketUtils.cacheTtlMs(response(0xFFFFFFFFL, 4000))) // TTL is unsigned 32 bit
    }

    @Test
    fun cacheKeepsRepliesWithoutAnswersFor30Seconds() {
        val noAnswers = response()
        assertTrue(cachedFor(noAnswers, 30_000))
        assertFalse(cachedFor(noAnswers, 30_001))
        // an answer count the bytes do not back up
        val truncated = response(300).copyOf(dnsQuery.size + 6)
        assertEquals(30_000, PacketUtils.cacheTtlMs(truncated))
        assertEquals(30_000, PacketUtils.cacheTtlMs(ByteArray(5)))
    }

    @Test
    fun cacheStaysBounded() {
        val cache = DnsCache(maxEntries = 2)
        val queries = (1..3).map { n -> dnsQuery.copyOf().also { it[13] = n.toByte() } }
        for (q in queries) cache.put(q, q, now = 0)
        assertNull(cache.get(queries[0], now = 1))
        assertNotNull(cache.get(queries[2], now = 1))
    }

    @Test
    fun negativeCacheRemembersForFiveSecondsIgnoringTheId() {
        var clock = 1000L
        val neg = NegativeDnsCache(clock = { clock })
        assertFalse(neg.has(dnsQuery))
        neg.put(dnsQuery)
        val retry = dnsQuery.copyOf().also { it[0] = 9; it[1] = 9 } // same question, new ID
        assertTrue(neg.has(retry))
        clock += 4999
        assertTrue(neg.has(dnsQuery))
        clock += 1
        assertFalse(neg.has(dnsQuery))
    }

    @Test
    fun negativeCacheStaysBoundedAndSeparatesQuestions() {
        val neg = NegativeDnsCache(maxEntries = 2, clock = { 0L })
        val queries = (1..3).map { n -> dnsQuery.copyOf().also { it[13] = n.toByte() } }
        for (q in queries) neg.put(q)
        assertFalse(neg.has(queries[0]))
        assertTrue(neg.has(queries[1]))
        assertTrue(neg.has(queries[2]))
    }

    @Test
    fun ecsZeroAddedToPlainAndOptQueries() {
        // header (1 question) + "a.b" A IN
        val plain = byteArrayOf(0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a'.code.toByte(), 1, 'b'.code.toByte(), 0, 0, 1, 0, 1)
        val (withOpt, added) = PacketUtils.withEcsZero(plain)
        assertTrue(added)
        assertEquals(1, port(withOpt, 10))
        assertEquals(plain.size + 11 + 8, withOpt.size)
        assertEquals(41, port(withOpt, plain.size + 1))
        assertEquals(8, port(withOpt, plain.size + 11))

        // already has an empty OPT: option is appended and rdlen grows
        val again = withOpt.copyOf(plain.size + 11)
        again[again.size - 1] = 0; again[10] = 0; again[11] = 1
        val (r, addedToOpt) = PacketUtils.withEcsZero(again)
        assertTrue(addedToOpt)
        assertEquals(8, port(r, plain.size + 9))
        assertArrayEquals(withOpt, r)
        // idempotent: a query that already carries ECS is left alone
        val (same, addedAgain) = PacketUtils.withEcsZero(r)
        assertFalse(addedAgain)
        assertArrayEquals(r, same)
    }

    @Test
    fun ecsZeroSkippedForQueriesItCannotExtend() {
        val plain = byteArrayOf(0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a'.code.toByte(), 1, 'b'.code.toByte(), 0, 0, 1, 0, 1)
        // two questions, a truncated header, trailing bytes that are not an OPT record
        val twoQuestions = plain.copyOf().also { it[5] = 2 }
        for (q in listOf(twoQuestions, plain.copyOf(8), plain + byteArrayOf(1, 2, 3))) {
            val (out, added) = PacketUtils.withEcsZero(q)
            assertFalse(added)
            assertArrayEquals(q, out)
        }
    }

    @Test
    fun tcpToTheTunnelResolverIsRecognised() {
        val dns = byteArrayOf(10, 111, 0, 2)
        val tcp = ByteArray(40)
        tcp[0] = 0x45; tcp[9] = 6
        dns.copyInto(tcp, 16)
        tcp[22] = 0; tcp[23] = 53
        assertTrue(PacketUtils.isTcpTo(tcp, tcp.size, dns, 53))
        assertFalse(PacketUtils.isTcpTo(tcp.copyOf().also { it[23] = 80 }, tcp.size, dns, 53))
        assertFalse(PacketUtils.isTcpTo(tcp.copyOf().also { it[19] = 3 }, tcp.size, dns, 53))
        assertFalse(PacketUtils.isTcpTo(tcp.copyOf().also { it[9] = 17 }, tcp.size, dns, 53))
        assertFalse(PacketUtils.isTcpTo(tcp, 20, dns, 53))
        assertFalse(PacketUtils.isTcpTo(ByteArray(60).also { it[0] = 0x60; it[6] = 6 }, 60, dns, 53))
    }

    private fun udpPacket(v6: Boolean, proto: Int, dport: Int, frag: Int = 0): ByteArray {
        val hl = if (v6) 40 else 20
        val p = ByteArray(hl + 8 + 20)
        if (v6) {
            p[0] = 0x60; p[4] = 0; p[5] = 28; p[6] = proto.toByte(); p[7] = 64
        } else {
            p[0] = 0x45; p[2] = 0; p[3] = p.size.toByte(); p[6] = (frag shr 8).toByte(); p[7] = frag.toByte()
            p[8] = 64; p[9] = proto.toByte()
        }
        p[hl] = 0xC3.toByte(); p[hl + 1] = 0x50
        p[hl + 2] = (dport shr 8).toByte(); p[hl + 3] = dport.toByte()
        p[hl + 5] = 28
        return p
    }

    @Test
    fun udp443UsesTheUdpParserForIpv4AndIpv6() {
        for (v6 in listOf(false, true)) {
            val packet = udpPacket(v6, 17, 443)
            val udp = PacketUtils.parseUdp(packet)!!
            assertEquals(50000, udp.srcPort)
            assertEquals(443, udp.dstPort)
            assertEquals(20, udp.payload.size)
            assertNull(PacketUtils.parse(packet))
            assertNull(PacketUtils.parseUdp(udpPacket(v6, 6, 443)))
            assertNull(PacketUtils.parseUdp(packet, if (v6) 44 else 20))
        }
        assertNull(PacketUtils.parseUdp(udpPacket(false, 17, 443, frag = 0x00B9)))
    }

    @Test
    fun firstUdp443FragmentCanBeClassifiedButHasNoRelayPayload() {
        val fragment = udpPacket(false, 17, 443, frag = 0x2000)
        byteArrayOf(10, 111, 0, 1, 192.toByte(), 0, 2, 3).copyInto(fragment, 12)
        assertNull(PacketUtils.parseUdp(fragment))
        val endpoints = PacketUtils.firstIpv4Udp443Fragment(fragment, fragment.size)!!
        assertEquals("10.111.0.1", endpoints.source.address.hostAddress)
        assertEquals(50000, endpoints.source.port)
        assertEquals("192.0.2.3", endpoints.destination.address.hostAddress)
        assertEquals(443, endpoints.destination.port)
        val policy = QuicPolicy(10100, setOf(10101), { 0 }) { _, _ -> 10101 }
        assertEquals(QuicPolicy.Route.TCP_FALLBACK, policy.route(endpoints))

        val later = udpPacket(false, 17, 443, frag = 0x2001)
        assertNull(PacketUtils.firstIpv4Udp443Fragment(later, later.size))
        assertNull(PacketUtils.firstIpv4Udp443Fragment(fragment, 27))
        val otherPort = udpPacket(false, 17, 444, frag = 0x2000)
        assertNull(PacketUtils.firstIpv4Udp443Fragment(otherPort, otherPort.size))
    }
}
