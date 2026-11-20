package com.october.fixer

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import java.net.Inet6Address
import java.util.Locale

/** Device and network facts for the diagnostics report. Facts only, never addresses or host names. */
object NetworkFacts {
    private const val ROBLOX = "com.roblox.client"
    private const val UNKNOWN = "unknown"

    /** Bucket of a Private DNS host name; the name itself never leaves this function. */
    fun dnsProvider(host: String?): String {
        val h = host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return "none"
        return when {
            "adguard" in h -> "adguard"
            "cloudflare" in h -> "cloudflare"
            "google" in h -> "google"
            "nullsproxy" in h -> "nullsproxy"
            "quad9" in h -> "quad9"
            "xbox-dns" in h -> "xbox"
            else -> "other"
        }
    }

    /** Strict Private DNS is active on the physical network: the system sends DNS over DoT past our resolver. */
    fun privateDnsBlocks(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        val link = linkOf(context.applicationContext) ?: return false
        return link.isPrivateDnsActive && link.privateDnsServerName != null
    }

    fun lines(context: Context): List<String> {
        val c = context.applicationContext
        val link = linkOf(c)
        val out = mutableListOf<String>()
        out += ReportFormat.line("oneui", oneUi())
        out += ReportFormat.line("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else UNKNOWN)
        out += ReportFormat.line("patch", Build.VERSION.SECURITY_PATCH)
        out += ReportFormat.line("user_id", android.os.Process.myUid() / 100000)
        out += ReportFormat.line("private_dns.active", if (Build.VERSION.SDK_INT >= 28 && link != null) link.isPrivateDnsActive else UNKNOWN)
        out += ReportFormat.line("private_dns.provider", if (Build.VERSION.SDK_INT >= 28 && link != null) dnsProvider(link.privateDnsServerName) else UNKNOWN)
        out += ReportFormat.line("net.ipv6", link?.let { hasGlobalIpv6(it) } ?: UNKNOWN)
        out += ReportFormat.line("net.nat64", if (Build.VERSION.SDK_INT >= 30 && link != null) link.nat64Prefix != null else UNKNOWN)
        out += ReportFormat.line("net.other_vpn", otherVpn(c))
        out += ReportFormat.line("battery.unrestricted", safe { c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName) })
        out += ReportFormat.line("battery.bg_restricted", if (Build.VERSION.SDK_INT >= 28) safe { c.getSystemService(ActivityManager::class.java).isBackgroundRestricted } else UNKNOWN)
        out += ReportFormat.line("standby_bucket", if (Build.VERSION.SDK_INT >= 28) safe { bucketName(c.getSystemService(UsageStatsManager::class.java).appStandbyBucket) } else UNKNOWN)
        val roblox = try { c.packageManager.getPackageInfo(ROBLOX, 0) } catch (_: Exception) { null }
        out += ReportFormat.line("roblox.version", roblox?.versionName ?: "none")
        out += ReportFormat.line("roblox.installed", roblox != null)
        return out
    }

    private inline fun safe(block: () -> Any?): Any = try { block() ?: UNKNOWN } catch (_: Exception) { UNKNOWN }

    private fun oneUi(): String = try {
        val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "ro.build.version.oneui") as? String
        v?.takeIf { it.isNotBlank() } ?: "none"
    } catch (_: Throwable) { "none" }

    private fun bucketName(b: Int): String = when (b) {
        UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
        UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
        UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
        UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
        45 -> "restricted"
        else -> "other"
    }

    private fun hasGlobalIpv6(link: LinkProperties): Boolean =
        link.linkAddresses.any { it.address is Inet6Address && isGlobalIpv6(it.address.address) }

    /** A routable IPv6 address: not unspecified, loopback, multicast, link-local, site-local or ULA. */
    fun isGlobalIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (bytes.all { it.toInt() == 0 }) return false
        if (bytes.take(15).all { it.toInt() == 0 } && bytes[15].toInt() == 1) return false
        if (b0 == 0xFF) return false
        if ((b0 and 0xFE) == 0xFC) return false
        if (b0 == 0xFE && (b1 and 0xC0) != 0x00) return false // fe80::/10 and fec0::/10
        return true
    }

    @Suppress("DEPRECATION") // allNetworks is deprecated from 31 but is the only way to list non-default networks
    private fun networks(c: Context): List<Pair<Network, NetworkCapabilities>> = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
    } catch (_: Exception) { emptyList() }

    /** The physical network: the active one unless it is our own VPN, then the best internet network that is not a VPN. */
    private fun linkOf(c: Context): LinkProperties? = try {
        physicalNetwork(c)?.let { c.getSystemService(ConnectivityManager::class.java).getLinkProperties(it) }
    } catch (_: Exception) { null }

    /** The physical network, shared with the comparison probe; null when there is none or the lookup fails. */
    internal fun physicalNetwork(c: Context): Network? = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        if (active != null && activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) active
        else networks(c).filter {
            !it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.maxByOrNull { if (it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 1 else 0 }?.first
    } catch (_: Exception) { null }

    /** Another VPN can only be told apart from ours while ours is down. */
    private fun otherVpn(c: Context): Any =
        if (FixVpnService.running) UNKNOWN
        else networks(c).any { it.second.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
}
