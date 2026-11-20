package com.october.fixer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPolicyTest {
    @Test fun splitDnsUsesWholeLabelsAndIgnoresCase() {
        assertTrue(DnsPolicy.question(query("ONLINE.SBERBANK.RU"))!!.local)
        assertTrue(DnsPolicy.question(query("www.mts.ru"))!!.local)
        assertFalse(DnsPolicy.question(query("sberbank.ru.example.org"))!!.local)
        assertTrue(DnsPolicy.question(query("notsberbank.ru"))!!.local) // any .ru is local now
        assertFalse(DnsPolicy.question(query("notsberbank.ru.example.org"))!!.local)
        assertFalse(DnsPolicy.question(query("evilvk.com"))!!.local)
        assertTrue(DnsPolicy.question(query("game.brawlstarsgame.com"))!!.game)
        val clashAssets = DnsPolicy.question(query(DnsPolicy.CLASH_ASSETS))!!
        assertFalse(clashAssets.game)
        assertFalse(DnsPolicy.question(query("account.supercell.com"))!!.game)
        assertTrue(DnsPolicy.question(query("tr.rbxcdn.com"))!!.assetA)
        assertFalse(DnsPolicy.question(query("tr.rbxcdn.com", type = 28))!!.assetA)
    }

    @Test fun bootstrapReadsCompressedAddressAnswer() {
        val request = DnsPolicy.addressQuery("dns.nullsproxy.com")
        val answer = request.copyOf().apply {
            this[2] = 0x81.toByte()
            this[7] = 1
        } + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 192.toByte(), 0, 2, 1)
        assertEquals(listOf("192.0.2.1"), DnsPolicy.ipv4Answers(request, answer).map { it.hostAddress })
        assertTrue(DnsPolicy.ipv4Answers(request, answer.copyOf(answer.size - 1)).isEmpty())
    }

    @Test fun replyMustMatchTransactionAndQuestion() {
        val original = query("tr.rbxcdn.com")
        val answer = original.copyOf().apply { this[2] = 0x81.toByte() }
        assertTrue(DnsPolicy.usable(original, answer))
        assertFalse(DnsPolicy.usable(original, answer.copyOf().apply { this[0] = 2 }))
        assertFalse(DnsPolicy.usable(original, answer.copyOf().apply { this[2] = 0x83.toByte() }))
        assertFalse(DnsPolicy.usable(query("images.rbxcdn.com"), answer))
        assertFalse(DnsPolicy.usable(query("tr.rbxcdn.com", type = 28), answer))
        assertNull(DnsPolicy.question(original.copyOf(18)))
    }

    @Test fun gameAnswerIsTheFastestReachableOne() {
        val unreachable = GameDnsAnswer(byteArrayOf(1), "dot_nulls", 40)
        val reachable = GameDnsAnswer(byteArrayOf(2), "udp_xbox", 90)
        val slower = GameDnsAnswer(byteArrayOf(3), "dot_nulls", 120)
        val picked = pickGameAnswer(listOf(slower, unreachable, reachable)) { it[0].toInt() != 1 }
        assertEquals(90, picked!!.ms)
        assertEquals("udp_xbox", picked.source)
    }

    @Test fun gameAnswerIsNullWhenNothingIsReachable() {
        val all = listOf(GameDnsAnswer(byteArrayOf(1), "dot_nulls", 40), GameDnsAnswer(byteArrayOf(2), "udp_xbox", 90))
        assertNull(pickGameAnswer(all) { false })
        assertNull(pickGameAnswer(emptyList()) { true })
    }

    @Test fun preferredSourceWinsWhenReachableRegardlessOfSpeed() {
        val nulls = GameDnsAnswer(byteArrayOf(1), "dot_nulls", 900)
        val xbox = GameDnsAnswer(byteArrayOf(2), "udp_xbox", 30)
        assertEquals("dot_nulls", pickGameAnswer(listOf(xbox, nulls), "dot_nulls") { true }!!.source)
        // preferred but unreachable: the other one is taken
        assertEquals("udp_xbox", pickGameAnswer(listOf(xbox, nulls), "dot_nulls") { it[0].toInt() != 1 }!!.source)
        // no preference: fastest
        assertEquals("udp_xbox", pickGameAnswer(listOf(nulls, xbox)) { true }!!.source)
    }

    private fun query(host: String, type: Int = 1): ByteArray {
        val labels = host.split('.').flatMap { label -> listOf(label.length.toByte()) + label.toByteArray().toList() }
        return byteArrayOf(1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
            labels.toByteArray() + byteArrayOf(0, 0, type.toByte(), 0, 1)
    }
}
