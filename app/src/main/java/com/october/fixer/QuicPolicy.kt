package com.october.fixer

import java.net.InetSocketAddress

/** UDP/443 routing for one TUN reader. Known non-Roblox apps fall back to TCP through byedpi. */
internal class QuicPolicy(
    private val robloxUid: Int?,
    private val fallbackUids: Set<Int>,
    private val nowMs: () -> Long,
    private val ownerUid: (InetSocketAddress, InetSocketAddress) -> Int,
) {
    enum class Route { TCP_FALLBACK, ROBLOX, UNKNOWN }

    private data class Entry(val route: Route, val expiresAt: Long)

    private val cache = object : LinkedHashMap<UdpEndpoints, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UdpEndpoints, Entry>?) = size > 256
    }

    fun route(packet: UdpDns): Route = route(UdpEndpoints(packet.src, packet.srcPort, packet.dst, packet.dstPort))

    fun route(flow: UdpEndpoints): Route {
        val now = nowMs()
        cache[flow]?.takeIf { now < it.expiresAt }?.let { return it.route }
        val uid = try {
            ownerUid(flow.source, flow.destination)
        } catch (_: RuntimeException) {
            // Android can reject the lookup if the VPN permission has just been revoked.
            -1
        }
        val route = when {
            uid < 0 -> Route.UNKNOWN
            uid == robloxUid -> Route.ROBLOX
            uid in fallbackUids -> Route.TCP_FALLBACK
            else -> Route.UNKNOWN
        }
        // Avoid an IPC for every datagram but recheck ownership when a tuple may have been reused.
        cache[flow] = Entry(route, now + 2000)
        return route
    }
}
