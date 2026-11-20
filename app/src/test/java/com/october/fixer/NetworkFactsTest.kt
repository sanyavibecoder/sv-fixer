package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkFactsTest {
    @Test fun knownProviders() {
        assertEquals("adguard", NetworkFacts.dnsProvider("dns.adguard-dns.com"))
        assertEquals("cloudflare", NetworkFacts.dnsProvider("1dot1dot1dot1.cloudflare-dns.com"))
        assertEquals("google", NetworkFacts.dnsProvider("dns.google"))
        assertEquals("nullsproxy", NetworkFacts.dnsProvider("dns.nullsproxy.com"))
        assertEquals("quad9", NetworkFacts.dnsProvider("dns.quad9.net"))
        assertEquals("xbox", NetworkFacts.dnsProvider("dns.xbox-dns.ru"))
    }

    @Test fun noneAndOther() {
        assertEquals("none", NetworkFacts.dnsProvider(null))
        assertEquals("other", NetworkFacts.dnsProvider("my.home.lan"))
    }

    @Test fun caseInsensitive() = assertEquals("google", NetworkFacts.dnsProvider("DNS.Google"))

    private fun ip(s: String) = java.net.InetAddress.getByName(s).address

    @Test fun globalIpv6() {
        assertEquals(true, NetworkFacts.isGlobalIpv6(ip("2a00:1450::1")))
        assertEquals(false, NetworkFacts.isGlobalIpv6(ip("fd12:3456::1")))
        assertEquals(false, NetworkFacts.isGlobalIpv6(ip("fc00::1")))
        assertEquals(false, NetworkFacts.isGlobalIpv6(ip("fe80::1")))
        assertEquals(false, NetworkFacts.isGlobalIpv6(ip("fec0::1")))
        assertEquals(false, NetworkFacts.isGlobalIpv6(ip("::1")))
        assertEquals(false, NetworkFacts.isGlobalIpv6(ip("1.2.3.4")))
    }
}
