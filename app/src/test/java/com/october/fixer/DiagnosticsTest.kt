package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test fun readsStoredProbesWithOrWithoutTcpAddressSource() {
        val base = "sys:noerror/0/35ms doh:noerror/4/90ms tcp:2/4"
        val measured = ProbeReading.Measured(DnsOutcome("noerror", 0, 35), DnsOutcome("noerror", 4, 90), 2, 4, null)
        assertEquals(measured, parseProbeReading(base))
        assertEquals(measured.copy(connectSource = "doh"), parseProbeReading("$base via:doh"))
        assertFalse(measured.deadlineExceeded)
    }

    @Test fun distinguishesSkippedAndUnfinishedProbesFromDnsTimeouts() {
        assertEquals(ProbeReading.Skipped, parseProbeReading("skip"))
        val unfinished = parseProbeReading("sys:timeout/0/0ms doh:timeout/0/0ms tcp:0/0") as ProbeReading.Measured
        assertTrue(unfinished.deadlineExceeded)
        val measured = parseProbeReading("sys:timeout/0/3000ms doh:timeout/0/3000ms tcp:0/0") as ProbeReading.Measured
        assertFalse(measured.deadlineExceeded)
        assertNull(parseProbeReading("sys:timeout/0/0ms doh:timeout/0/0ms tcp:1/0"))
    }

    @Test fun countsOnlyCompletedTcpAttemptsBeforeDeadline() {
        val attempts = sequence<Boolean?> {
            yield(true)
            yield(false)
            yield(null)
            error("An attempt started after the deadline")
        }
        assertEquals(1 to 2, Diagnostics.countTcpProbes(attempts))
        assertEquals(0 to 0, Diagnostics.countTcpProbes(sequenceOf(null)))
        assertEquals(0 to 2, Diagnostics.countTcpProbes(sequenceOf(false, false)))
    }

    @Test fun storesCheckedAndSelectedTcpAddressesSeparately() {
        val dns = DnsOutcome("noerror", 4, 10)
        val line = probeLine("www.youtube.com", dns, dns, 1, 4, "doh", connectChecked = 2)
        assertEquals("probe.www_youtube_com=sys:noerror/4/10ms doh:noerror/4/10ms tcp:1/2 via:doh total:4", line)
        assertEquals(ProbeReading.Measured(dns, dns, 1, 4, "doh", 2), parseProbeReading(line.substringAfter('=')))
        assertNull(parseProbeReading(line.substringAfter('=').replace("total:4", "total:1")))
    }

    @Test fun errorCodesAreFixedVocabulary() {
        assertEquals("timeout", Diagnostics.safeCode("timeout"))
        assertEquals("http_429", Diagnostics.safeCode("http_429"))
        assertEquals("unknown", Diagnostics.safeCode("SocketException: secret.example/192.0.2.1"))
        assertEquals("unknown", Diagnostics.safeCode("token=abc123"))
    }

    @Test fun latestVpnErrorSurvivesCleanupAndResetsOnSuccessfulStart() {
        val failed = listOf("1700000000000,vpn_establish,vpn_conflict", "1700000000100,vpn_stop,ok")
        assertEquals("vpn_conflict", Diagnostics.latestVpnError(failed, running = false))
        val restarted = failed + "1700000000200,vpn_running,ok"
        assertEquals("none", Diagnostics.latestVpnError(restarted, running = false))
        val ended = restarted + "1700000000300,service_killed,exit_low_memory"
        assertEquals("exit_low_memory", Diagnostics.latestVpnError(ended, running = false))
        assertEquals("none", Diagnostics.latestVpnError(ended, running = true))
    }

    @Test fun wasKilledOnlyForSameInstallWithFlag() {
        assertTrue(Diagnostics.wasKilled(true, 1000L, 1000L))
        assertEquals(false, Diagnostics.wasKilled(true, 1000L, 2000L))
        assertEquals(false, Diagnostics.wasKilled(false, 1000L, 1000L))
        assertEquals(false, Diagnostics.wasKilled(true, 0L, 1000L))
    }

    @Test fun staleKilledEventsAreDroppedOnlyBeforeLaunch() {
        val old = "1700000000000,service_killed,unknown"
        val fresh = "1700000009000,service_killed,unknown"
        val other = "1700000000500,vpn_running,ok"
        assertEquals(listOf(other, fresh), Diagnostics.withoutKilledBefore(listOf(old, other, fresh), 1700000005000L))
    }

    @Test fun interruptionRemainsPendingUntilAnotherSessionRuns() {
        val killed = sequenceOf("1700000000000,vpn_running,ok", "1700000000100,service_killed,exit_low_memory")
        assertEquals("exit_low_memory", Diagnostics.pendingServiceKill(killed))
        assertEquals(null, Diagnostics.pendingServiceKill(killed + "1700000000200,vpn_request,ok"))
        assertEquals(null, Diagnostics.pendingServiceKill(killed + "1700000000300,vpn_running,ok"))
    }

    @Test fun probeHostListIsFixedAndContainsNoAddressesOrCredentials() {
        val src = Diagnostics::class.java.getDeclaredField("PROBE_HOSTS").apply { isAccessible = true }.get(Diagnostics) as List<*>
        assertEquals(
            listOf(
                "www.youtube.com", "www.roblox.com", "tr.rbxcdn.com", "t0.rbxcdn.com", "c0.rbxcdn.com", "thumbnails.roblox.com",
                "game.clashroyaleapp.com", "game.brawlstarsgame.com", "game-assets.clashroyaleapp.com", "game-assets.brawlstarsgame.com",
            ),
            src,
        )
        assertTrue(src.all { it is String && !it.contains("/") && !it.contains("@") })
    }

    @Test fun probePortIs9339ForGameHostsAnd443Otherwise() {
        listOf("tr.rbxcdn.com", "t0.rbxcdn.com", "c0.rbxcdn.com", "thumbnails.roblox.com").forEach { assertEquals(443, Diagnostics.probePort(it)) }
        assertEquals(9339, Diagnostics.probePort("game.clashroyaleapp.com"))
        assertEquals(9339, Diagnostics.probePort("game.brawlstarsgame.com"))
    }

    @Test fun probeLineHasExactFormat() {
        assertEquals(
            "probe.tr_rbxcdn_com=sys:noerror/0/35ms doh:noerror/4/90ms tcp:4/4",
            probeLine("tr.rbxcdn.com", DnsOutcome("noerror", 0, 35), DnsOutcome("noerror", 4, 90), 4, 4),
        )
    }

    @Test fun timeoutOutcomeRendersZeroAnswers() {
        val line = probeLine("t0.rbxcdn.com", DnsOutcome("timeout", 0, 3000), DnsOutcome("noerror", 2, 80), 0, 2)
        assertEquals("probe.t0_rbxcdn_com=sys:timeout/0/3000ms doh:noerror/2/80ms tcp:0/2", line)
    }

    @Test fun tcpShowsWhenAddressesCameFromSystemDns() {
        val line = probeLine("www.youtube.com", DnsOutcome("noerror", 2, 35), DnsOutcome("timeout", 0, 3000), 1, 2, "sys")
        assertEquals("probe.www_youtube_com=sys:noerror/2/35ms doh:timeout/0/3000ms tcp:1/2 via:sys", line)
    }

    @Test fun aaaaLineShape() {
        val line = probeLine("tr.rbxcdn.com_aaaa", DnsOutcome("noerror", 0, 12), DnsOutcome("noerror", 0, 70), 0, 0)
        assertEquals("probe.tr_rbxcdn_com_aaaa=sys:noerror/0/12ms doh:noerror/0/70ms tcp:0/0", line)
    }

    @Test fun everyProducedLineMatchesReportGrammar() {
        val hosts = listOf(
            "tr.rbxcdn.com", "t0.rbxcdn.com", "c0.rbxcdn.com", "thumbnails.roblox.com", "game.clashroyaleapp.com",
            "game.brawlstarsgame.com", "tr.rbxcdn.com_aaaa", "game-assets.clashroyaleapp.com", "game-assets.brawlstarsgame.com",
        )
        val codes = listOf("noerror", "nxdomain", "servfail", "refused", "timeout", "error")
        for (h in hosts) for (c in codes) {
            val line = probeLine(h, DnsOutcome(c, 32, 25_000), DnsOutcome(c, 0, 99_999), 4, 4)
            assertTrue(line, ReportFormat.LINE.matches(line))
        }
        assertEquals(listOf("noerror", "error", "servfail", "nxdomain", "refused"), listOf(0, 1, 2, 3, 5).map(::rcodeName))
    }

    @Test fun hostSkippedByBudgetRendersSkipNotTimeout() {
        assertEquals(listOf("probe.t0_rbxcdn_com=skip"), Diagnostics.fallbackLines("t0.rbxcdn.com", false))
        assertEquals(listOf("probe.game_assets_clashroyaleapp_com=skip"), Diagnostics.fallbackLines("game-assets.clashroyaleapp.com", false))
        assertEquals(
            listOf("probe.tr_rbxcdn_com=skip", "probe.tr_rbxcdn_com_aaaa=skip"),
            Diagnostics.fallbackLines("tr.rbxcdn.com", false),
        )
        assertEquals(
            "probe.t0_rbxcdn_com=sys:timeout/0/0ms doh:timeout/0/0ms tcp:0/0",
            Diagnostics.fallbackLines("t0.rbxcdn.com", true).single(),
        )
    }
}
