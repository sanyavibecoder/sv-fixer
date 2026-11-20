package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuDomainsTest {
    @Test fun russianNamesAreLocal() {
        for (name in listOf(
            "online.sberbank.ru", "GOSUSLUGI.RU", "xn--80aesfpebagmfblc0a.xn--p1ai",
            "sun9-1.userapi.com", "ru", "www.mts.ru.", "vk.com", "m.vk.com", "api.yandex.net"
        )) assertTrue(name, RuDomains.isLocal(name))
    }

    @Test fun foreignNamesAreNotLocal() {
        for (name in listOf(
            "notsberbank.ru.example.org", "evil-ru.com", "youtube.com", "roblox.com",
            "rbxcdn.com", "evilvk.com", "", ".", "a..ru"
        )) assertFalse(name, RuDomains.isLocal(name))
    }

    @Test fun byedpiListHasEveryZone() {
        val hosts = RuDomains.noDesyncHosts()
        assertTrue(hosts.containsAll(RuDomains.tlds + RuDomains.extra))
        assertEquals(hosts.size, hosts.toSet().size)
        assertTrue(hosts.all { it == it.lowercase() && !it.startsWith(".") })
    }
}
