package com.october.fixer

import android.net.DnsResolver
import android.net.Network
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A valid reply to a game-host query. [source]: "dot_nulls" | "dot_xbox" | "udp_xbox". */
internal data class GameDnsAnswer(val reply: ByteArray, val source: String, val ms: Int)

/**
 * Among answers whose game server accepts a connection ([reachable] gets its reply): the one from [prefer] if it
 * is reachable, regardless of speed; otherwise the fastest. Null when none is reachable.
 */
internal fun pickGameAnswer(candidates: List<GameDnsAnswer>, prefer: String? = null, reachable: (ByteArray) -> Boolean): GameDnsAnswer? {
    val ok = candidates.sortedBy { it.ms }.filter { reachable(it.reply) }
    return ok.firstOrNull { it.source == prefer } ?: ok.firstOrNull()
}

internal object DnsPolicy {
    val gameDomains = listOf("game.clashroyaleapp.com", "game.brawlstarsgame.com")
    const val CLASH_ASSETS = "game-assets.clashroyaleapp.com"
    // byedpi -H list: Clash's CDN normally goes via the relay, but its direct fallback must not be desynced either
    val supercellAssetHosts = listOf("game-assets.brawlstarsgame.com", CLASH_ASSETS)
    const val CLASH_RELAY_IP = "198.18.0.44"
    private val robloxCdn = listOf("rbxcdn.com")

    data class Question(val host: String, val type: Int, val klass: Int) {
        val local get() = RuDomains.isLocal(host)
        val game get() = matches(host, gameDomains)
        val assetA get() = type == 1 && matches(host, robloxCdn)
    }

    fun matches(host: String, domains: List<String>): Boolean =
        domains.any { host == it || host.endsWith(".$it") }

    fun question(packet: ByteArray): Question? {
        if (packet.size < 17 || number(packet, 4) != 1) return null
        var offset = 12
        val labels = ArrayList<String>()
        while (offset < packet.size && offset < 267) {
            val size = packet[offset++].toInt() and 255
            if (size == 0) {
                if (offset + 4 > packet.size) return null
                return Question(labels.joinToString(".").lowercase(Locale.ROOT), number(packet, offset), number(packet, offset + 2))
            }
            if (size > 63 || offset + size > packet.size || offset + size >= 267) return null
            for (i in offset until offset + size) {
                val c = packet[i].toInt() and 255
                if (c !in 33..126 || c == '.'.code) return null
            }
            labels.add(String(packet, offset, size, Charsets.US_ASCII))
            offset += size
        }
        return null
    }

    fun usable(query: ByteArray, reply: ByteArray): Boolean {
        if (query.size < 12 || reply.size < 12 || query[0] != reply[0] || query[1] != reply[1]) return false
        if (reply[2].toInt() and 0xfa != 0x80) return false // response, standard opcode, untruncated
        val rcode = rcode(reply)
        val asked = question(query) ?: return false
        return (rcode == 0 || rcode == 3) && asked == question(reply)
    }

    fun rcode(reply: ByteArray): Int = reply[3].toInt() and 15
    fun answers(reply: ByteArray): Int = number(reply, 6)

    fun clashRelayReply(query: ByteArray, question: Question): ByteArray? {
        if (question.host != CLASH_ASSETS || question.klass != 1) return null
        if (question.type !in setOf(1, 28, 65)) return null
        // The checked QNAME has one length byte per label and a root terminator.
        val questionEnd = 12 + CLASH_ASSETS.length + 2 + 4
        val address = if (question.type == 1) {
            byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 15, 0, 4, 198.toByte(), 18, 0, 44)
        } else {
            byteArrayOf()
        }
        val result = query.copyOf(questionEnd) + address
        result[2] = (0x80 or (query[2].toInt() and 1)).toByte()
        result[3] = 0x80.toByte()
        result[6] = 0
        result[7] = if (address.isEmpty()) 0 else 1
        for (i in 8..11) result[i] = 0
        return result
    }

    fun addressQuery(host: String): ByteArray {
        val id = Random.nextInt(65536)
        val labels = host.split('.').flatMap { listOf(it.length.toByte()) + it.toByteArray(Charsets.US_ASCII).toList() }
        return byteArrayOf((id shr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
            labels.toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
    }

    fun ipv4Answers(query: ByteArray, reply: ByteArray): List<InetAddress> {
        if (!usable(query, reply) || rcode(reply) != 0) return emptyList()
        val addresses = ArrayList<InetAddress>()
        var offset = skipName(reply, 12) + 4
        repeat(minOf(answers(reply), 32)) {
            offset = skipName(reply, offset)
            if (offset < 0 || offset + 10 > reply.size) return emptyList()
            val length = number(reply, offset + 8)
            val start = offset + 10
            if (start + length > reply.size) return emptyList()
            if (number(reply, offset) == 1 && number(reply, offset + 2) == 1 && length == 4) {
                addresses.add(InetAddress.getByAddress(reply.copyOfRange(start, start + 4)))
            }
            offset = start + length
        }
        return addresses
    }

    private fun skipName(packet: ByteArray, start: Int): Int {
        var offset = start
        while (offset in packet.indices) {
            val length = packet[offset++].toInt() and 255
            if (length == 0) return offset
            if (length and 0xc0 == 0xc0) return if (offset < packet.size) offset + 1 else -1
            if (length > 63) return -1
            offset += length
        }
        return -1
    }

    private fun number(data: ByteArray, at: Int): Int = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
}

/** A fresh instance belongs to one VPN session and one underlying Android Network. */
internal class DnsNetwork(val network: Network?) {
    val cache = DnsCache()
    /** Questions every upstream failed for in the last seconds; dropped with the network, like [cache]. */
    val failures = NegativeDnsCache()
    @Volatile var preferred = 0
    // its own @Volatile pair, not the refresh lock: resolver threads must not wait for a slow bootstrap
    @Volatile private var gameWin: Pair<String, Long>? = null
    val rank = UpstreamRank(7) // 4 DoH, then 3 UDP; new network = new rank
    private data class Resolver(val address: InetAddress, val rtt: Long? = null, val retryAt: Long = 0)
    private var endpoints = emptyList<Resolver>()
    private var expiresAt = 0L
    private val executor = Executor { it.run() }

    /** The game DNS source that won on this network less than 10 minutes ago, or null. */
    fun gameWinner(): String? = gameWin?.takeIf { SystemClock.elapsedRealtime() < it.second }?.first

    fun rememberGameWinner(source: String) {
        gameWin = source to SystemClock.elapsedRealtime() + 600_000
    }

    fun system(query: ByteArray): ByteArray? {
        return request { callback, cancel ->
            DnsResolver.getInstance().rawQuery(null, query, DnsResolver.FLAG_EMPTY, executor, cancel, callback)
        }
    }

    fun game(query: ByteArray, bootstrap: (ByteArray) -> ByteArray?): ByteArray? {
        val now = SystemClock.elapsedRealtime()
        val candidates = synchronized(this) {
            if (now >= expiresAt) {
                val system = request<List<InetAddress>> { callback, cancel ->
                    DnsResolver.getInstance().query(null, GAME_RESOLVER, DnsResolver.FLAG_EMPTY, executor, cancel, callback)
                }.orEmpty()
                val addresses = system.ifEmpty {
                    val request = DnsPolicy.addressQuery(GAME_RESOLVER)
                    bootstrap(request)?.let { DnsPolicy.ipv4Answers(request, it) }.orEmpty()
                }.ifEmpty {
                    // Carrier DNS can block the resolver name. These observed endpoints still
                    // have to authenticate as dns.nullsproxy.com before receiving a DNS query.
                    GAME_BOOTSTRAP.map { InetAddress.getByName(it) }
                }
                endpoints = addresses.distinct().map { Resolver(it) }
                expiresAt = now + 300_000
            }
            endpoints.filter { it.retryAt <= now }.sortedBy { it.rtt ?: 0L }.take(3)
        }
        for (candidate in candidates) {
            val start = SystemClock.elapsedRealtime()
            val reply = dot(candidate.address, query)
            val valid = reply != null && DnsPolicy.usable(query, reply) &&
                DnsPolicy.rcode(reply) == 0 && DnsPolicy.answers(reply) > 0
            if (reply != null && !valid) {
                Log.d("Fixer", "Supercell DNS: rejected reply rcode=${DnsPolicy.rcode(reply)} answers=${DnsPolicy.answers(reply)}")
            }
            synchronized(this) {
                val end = SystemClock.elapsedRealtime()
                endpoints = endpoints.map {
                    if (it.address != candidate.address) it
                    else if (valid) it.copy(rtt = end - start, retryAt = 0)
                    else it.copy(retryAt = end + 10_000)
                }
            }
            if (valid) return reply
        }
        return null
    }

    private fun dot(address: InetAddress, query: ByteArray): ByteArray? = try {
        Socket().use { raw ->
            // SV Fixer's UID is excluded from the VPN; the system selects its upstream network.
            raw.connect(InetSocketAddress(address, 853), 900)
            raw.soTimeout = 1000
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(raw, GAME_RESOLVER, 853, true) as SSLSocket).use { tls ->
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(GAME_RESOLVER))
                }
                tls.soTimeout = 1000
                tls.startHandshake()
                DataOutputStream(tls.outputStream).apply {
                    writeShort(query.size)
                    write(query)
                    flush()
                }
                val input = DataInputStream(tls.inputStream)
                val size = input.readUnsignedShort()
                if (size !in 12..4096) null else ByteArray(size).also { input.readFully(it) }
            }
        }
    } catch (e: Exception) {
        Log.d("Fixer", "Supercell DNS: ${e.javaClass.simpleName}")
        null
    }

    private fun <T : Any> request(submit: (DnsResolver.Callback<T>, CancellationSignal) -> Unit): T? {
        val ready = CountDownLatch(1)
        val cancel = CancellationSignal()
        var answer: T? = null
        return try {
            submit(object : DnsResolver.Callback<T> {
                override fun onAnswer(result: T, rcode: Int) {
                    answer = result
                    ready.countDown()
                }
                override fun onError(error: DnsResolver.DnsException) {
                    ready.countDown()
                }
            }, cancel)
            if (ready.await(1200, TimeUnit.MILLISECONDS)) answer else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            cancel.cancel()
        }
    }

    companion object {
        private const val GAME_RESOLVER = "dns.nullsproxy.com"
        private val GAME_BOOTSTRAP = listOf("45.139.239.56", "185.211.245.131", "185.175.46.137")
    }
}
