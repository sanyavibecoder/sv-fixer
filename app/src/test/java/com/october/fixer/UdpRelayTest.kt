package com.october.fixer

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UdpRelayTest {
    @Test
    fun quicToRobloxDoesNotArmTheGameRttProbe() {
        val roblox = byteArrayOf(128.toByte(), 116, 44, 3)
        fun packet(port: Int) = UdpDns(4, byteArrayOf(10, 111, 0, 1), roblox, 50000, port, byteArrayOf(1))

        assertNull(UdpRelay.gameRttProbe(packet(443)))
        assertNotNull(UdpRelay.gameRttProbe(packet(52167)))
    }
}
