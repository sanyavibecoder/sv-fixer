package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DnsStatsTest {
    @Before fun clear() = DnsStats.reset()

    @Test fun targetsAreAFixedSetOfHosts() {
        assertEquals("rbx_t", DnsStats.target("t3.rbxcdn.com"))
        assertEquals("rbx_tr", DnsStats.target("tr.rbxcdn.com"))
        assertEquals("rbx_c", DnsStats.target("c0.rbxcdn.com"))
        assertEquals("rbx_images", DnsStats.target("images.rbxcdn.com"))
        assertEquals("rbx_thumbnails", DnsStats.target("thumbnails.roblox.com"))
        assertEquals("rbx_apis", DnsStats.target("apis.roblox.com"))
        assertEquals("rbx_web", DnsStats.target("www.roblox.com"))
        assertEquals("clash", DnsStats.target("game.clashroyaleapp.com"))
        assertEquals("brawl", DnsStats.target("game.brawlstarsgame.com"))
        assertEquals("youtube", DnsStats.target("www.youtube.com"))
        assertEquals("youtube", DnsStats.target("rr3---sn-abc.googlevideo.com"))
        assertNull(DnsStats.target("evilrbxcdn.com"))
        assertNull(DnsStats.target("tx.rbxcdn.com"))
        assertNull(DnsStats.target("t33.rbxcdn.com"))
        assertNull(DnsStats.target("example.com"))
        assertNull(DnsStats.target(""))
    }

    @Test fun hitsAreAggregatedPerTargetUpstreamOutcome() {
        DnsStats.hit("rbx_tr", "doh_g1", "ok", 80)
        DnsStats.hit("rbx_tr", "doh_g1", "ok", 80)
        DnsStats.hit("rbx_tr", "doh_g1", "timeout", 2000)
        val lines = DnsStats.lines(System.currentTimeMillis())
        assertTrue(lines.contains("dns.rbx_tr.doh_g1.ok=2/avg80"))
        assertTrue(lines.contains("dns.rbx_tr.doh_g1.timeout=1/avg2000"))
        assertEquals(2, lines.count { it.startsWith("dns.") })
    }

    @Test fun unknownStringsAreIgnored() {
        DnsStats.hit("secret.example", "doh_g1", "ok", 1)
        DnsStats.hit("rbx_tr", "192.0.2.1", "ok", 1)
        DnsStats.hit("rbx_tr", "doh_g1", "SocketException", 1)
        DnsStats.count("no_such_counter")
        val lines = DnsStats.lines(System.currentTimeMillis())
        assertFalse(lines.any { it.startsWith("dns.") })
        assertFalse(lines.any { it.contains("no_such_counter") })
        DnsStats.hit("clash", "dot_xbox", "ok", 5) // reserved upstream is accepted
        assertTrue(DnsStats.lines(0).contains("dns.clash.dot_xbox.ok=1/avg5"))
    }

    @Test fun eightThreadsLoseNoHits() {
        val threads = List(8) {
            Thread { repeat(1000) { DnsStats.hit("rbx_t", "udp_q1", "ok", 10); DnsStats.count("tun_dns") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val lines = DnsStats.lines(System.currentTimeMillis())
        assertTrue(lines.contains("dns.rbx_t.udp_q1.ok=8000/avg10"))
        assertTrue(lines.contains("tun.tun_dns=8000"))
    }

    @Test fun countersAndQueryAge() {
        assertTrue(DnsStats.lines(System.currentTimeMillis()).contains("tun.last_query_age_s=none"))
        DnsStats.count("tun_ipv6")
        DnsStats.count("ecs_added")
        DnsStats.count("ecs_added")
        DnsStats.count("tun_dns")
        val lines = DnsStats.lines(System.currentTimeMillis() + 4500)
        assertTrue(lines.contains("tun.tun_dns=1"))
        assertTrue(lines.contains("tun.tun_ipv6=1"))
        assertTrue(lines.contains("tun.ecs_added=2"))
        assertTrue(lines.contains("tun.tun_tcp53=0"))
        assertTrue(lines.contains("tun.last_query_age_s=4"))
        DnsStats.reset()
        val after = DnsStats.lines(System.currentTimeMillis())
        assertTrue(after.contains("tun.tun_dns=0"))
        assertTrue(after.contains("tun.last_query_age_s=none"))
    }

    @Test fun linesFitTheReportGrammarAndStayCompact() {
        val targets = listOf("rbx_tr", "rbx_t", "rbx_c", "rbx_images", "rbx_thumbnails", "rbx_apis", "rbx_web", "clash", "brawl", "youtube")
        val upstreams = listOf("cache", "system", "dot_nulls", "dot_xbox", "doh_g1", "doh_g2", "doh_cf1", "doh_cf2", "udp_q1", "udp_q2", "udp_g")
        val outcomes = listOf("ok", "empty", "timeout", "error", "servfail", "nxdomain")
        for (t in targets) for (u in upstreams) for (o in outcomes) DnsStats.hit(t, u, o, 60_000)
        repeat(5) { DnsStats.hit("youtube", "udp_g", "servfail", 3) } // the busiest row must survive the cut
        val lines = DnsStats.lines(System.currentTimeMillis())
        assertTrue(lines.all { ReportFormat.LINE.matches(it) })
        val dns = lines.filter { it.startsWith("dns.") }
        assertEquals(24, dns.size)
        assertEquals(dns.sorted(), dns)
        assertTrue(dns.contains("dns.youtube.udp_g.servfail=6/avg10002"))
        // tun.* comes first: a report cut by its size limit loses dns rows, not these
        assertTrue(lines.take(10).all { it.startsWith("tun.") })
        assertEquals(dns, lines.drop(10))
    }

    @Test fun clashRelayCountersAppearOnlyWhenNonZero() {
        assertFalse(DnsStats.lines(0).any { it.contains("clash_relay") })
        DnsStats.count("clash_relay_ok")
        DnsStats.count("clash_relay_ok")
        DnsStats.count("clash_relay_fallback")
        DnsStats.count("clash_relay_fail")
        val lines = DnsStats.lines(0)
        assertTrue(lines.contains("tun.clash_relay_ok=2"))
        assertTrue(lines.contains("tun.clash_relay_fallback=1"))
        assertTrue(lines.contains("tun.clash_relay_fail=1"))
        assertTrue(lines.all { ReportFormat.LINE.matches(it) })
        DnsStats.reset()
        assertFalse(DnsStats.lines(0).any { it.contains("clash_relay") })
    }

    @Test fun statsAgeShowsSecondsSinceReset() {
        val now = System.currentTimeMillis()
        assertTrue(DnsStats.lines(now + 7500).contains("tun.stats_age_s=7"))
    }

    @Test fun dropCountersAppearOnlyWhenNonZero() {
        assertFalse(DnsStats.lines(0).any { it.contains("quic_drop") || it.contains("tun_full") })
        DnsStats.count("quic_drop")
        DnsStats.count("quic_drop")
        DnsStats.count("tun_full")
        val lines = DnsStats.lines(0)
        assertTrue(lines.contains("tun.quic_drop=2"))
        assertTrue(lines.contains("tun.tun_full=1"))
        assertTrue(lines.all { ReportFormat.LINE.matches(it) })
    }
}
