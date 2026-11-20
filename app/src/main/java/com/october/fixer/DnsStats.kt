package com.october.fixer

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.atomic.LongAdder

/**
 * In-memory DNS counters of one VPN session. Called from the tunnel's reader and resolver threads,
 * so nothing here locks, allocates per call, or touches storage. Only fixed vocabulary reaches the report.
 */
object DnsStats {
    private class Cell {
        val hits = LongAdder()
        val ms = LongAdder()
    }

    private const val MAX_DNS_LINES = 24
    private val targets = listOf(
        "rbx_tr", "rbx_t", "rbx_c", "rbx_images", "rbx_thumbnails", "rbx_apis", "rbx_web", "clash", "brawl", "youtube",
    )
    private val upstreams = listOf(
        "cache", "system", "dot_nulls", "dot_xbox", "doh_g1", "doh_g2", "doh_cf1", "doh_cf2", "udp_q1", "udp_q2", "udp_g",
    )
    private val outcomes = listOf("ok", "empty", "timeout", "error", "servfail", "nxdomain")

    // Never modified after init, so reads need no synchronization.
    private val targetIndex = targets.withIndex().associate { it.value to it.index }
    private val upstreamIndex = upstreams.withIndex().associate { it.value to it.index }
    private val outcomeIndex = outcomes.withIndex().associate { it.value to it.index }
    /** Report key of every cell, in cell order: (target, upstream, outcome). */
    private val keys = targets.flatMap { t -> upstreams.flatMap { u -> outcomes.map { o -> "dns.$t.$u.$o" } } }
    private val cells = AtomicReferenceArray<Cell?>(keys.size) // filled on first hit

    private val counters: Map<String, LongAdder> = listOf(
        "tun_dns", "tun_dns_aaaa", "tun_tcp53", "tun_ipv6", "ecs_added", "ecs_skipped", "returned_empty", "slow_3s",
        "clash_relay_ok", "clash_relay_fallback", "clash_relay_fail", "quic_drop", "quic_roblox", "quic_unknown", "tun_full",
    ).associateWith { LongAdder() }
    /** Counters included only when a packet uses these paths. */
    private val optionalCounters = setOf(
        "clash_relay_ok", "clash_relay_fallback", "clash_relay_fail", "quic_drop", "quic_roblox", "quic_unknown", "tun_full",
    )
    private val lastQueryAt = AtomicLong(0)
    /** When the counters were last zeroed (VPN session start); 0 = never since the process started. */
    private val resetAt = AtomicLong(0)

    private const val RBXCDN = ".rbxcdn.com"
    private val youtube = arrayOf("youtube.com", "googlevideo.com", "ytimg.com", "youtu.be")
    private val youtubeDotted = Array(youtube.size) { "." + youtube[it] }

    /** The report bucket of a lowercase host name, or null for names that are not counted. */
    fun target(host: String): String? {
        if (host.endsWith(RBXCDN)) {
            return when (host.length - RBXCDN.length) {
                2 -> when {
                    host[0] == 't' && host[1] == 'r' -> "rbx_tr"
                    host[1] !in '0'..'9' -> null
                    host[0] == 't' -> "rbx_t"
                    host[0] == 'c' -> "rbx_c"
                    else -> null
                }
                6 -> if (host == "images.rbxcdn.com") "rbx_images" else null
                else -> null
            }
        }
        if (host == "thumbnails.roblox.com") return "rbx_thumbnails"
        if (host == "apis.roblox.com") return "rbx_apis"
        if (host == "www.roblox.com" || host == "roblox.com") return "rbx_web"
        if (host == "clashroyaleapp.com" || host.endsWith(".clashroyaleapp.com")) return "clash"
        if (host == "brawlstarsgame.com" || host.endsWith(".brawlstarsgame.com")) return "brawl"
        for (i in youtube.indices) {
            if (host == youtube[i] || host.endsWith(youtubeDotted[i])) return "youtube"
        }
        return null
    }

    /** One attempt to answer a query for [target]; strings outside the vocabulary are ignored. */
    fun hit(target: String, upstream: String, outcome: String, ms: Int) {
        val t = targetIndex[target] ?: return
        val u = upstreamIndex[upstream] ?: return
        val o = outcomeIndex[outcome] ?: return
        val i = (t * upstreams.size + u) * outcomes.size + o
        var cell = cells.get(i)
        if (cell == null) {
            cells.compareAndSet(i, null, Cell())
            cell = cells.get(i) ?: return // only a concurrent reset() empties it again
        }
        cell.hits.increment()
        cell.ms.add(ms.coerceIn(0, 60_000).toLong())
    }

    fun count(name: String) {
        val counter = counters[name] ?: return
        counter.increment()
        if (name == "tun_dns") lastQueryAt.set(System.currentTimeMillis())
    }

    /**
     * Report lines: `tun.*` first (a report cut by its size limit loses the tail), then the busiest
     * [MAX_DNS_LINES] non-zero `dns.*` rows sorted by key.
     */
    fun lines(nowMs: Long): List<String> = tunLines(nowMs) + dnsLines()

    /** The `tun.*` part of [lines]. */
    fun tunLines(nowMs: Long): List<String> {
        val tun = counters.entries
            .filter { it.key !in optionalCounters || it.value.sum() > 0 }
            .map { ReportFormat.line("tun.${it.key}", it.value.sum()) }
        val last = lastQueryAt.get()
        val age = if (last == 0L) "none" else ((nowMs - last).coerceAtLeast(0) / 1000).toString()
        val since = resetAt.get()
        val sessionAge = if (since == 0L) "none" else ((nowMs - since).coerceAtLeast(0) / 1000).toString()
        return tun + ReportFormat.line("tun.last_query_age_s", age) + ReportFormat.line("tun.stats_age_s", sessionAge)
    }

    /** The `dns.*` part of [lines]: the busiest [MAX_DNS_LINES] rows. */
    fun dnsLines(): List<String> =
        keys.indices
            .mapNotNull { i -> cells.get(i)?.let { Triple(keys[i], it.hits.sum(), it.ms.sum()) } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<String, Long, Long>> { it.second }.thenBy { it.first })
            .take(MAX_DNS_LINES)
            .sortedBy { it.first }
            .map { (key, hits, ms) -> ReportFormat.line(key, "$hits/avg${ms / hits}") }

    fun reset() {
        for (i in 0 until cells.length()) cells.set(i, null)
        counters.values.forEach { it.reset() }
        lastQueryAt.set(0)
        resetAt.set(System.currentTimeMillis())
    }
}
