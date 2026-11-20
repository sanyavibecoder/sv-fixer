package com.october.fixer

import android.os.SystemClock
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Local TCP endpoint for byedpi's one Clash CDN route. The game owns the inner TLS session. */
internal class ClashAssetRelay(private val relayHost: String, private val onRelayResult: (String) -> Unit) : Closeable {
    private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(0, 8, 20L, TimeUnit.SECONDS, SynchronousQueue())
    @Volatile private var retryAt = 0L
    val port: Int get() = listener.localPort

    init {
        Thread(::accept, "clash-assets-listen").apply { isDaemon = true; start() }
    }

    private fun accept() {
        while (!listener.isClosed) {
            val client = try { listener.accept() } catch (_: Exception) { break }
            clients.add(client)
            try {
                workers.execute {
                    try {
                        client.use(::transfer)
                    } catch (_: Exception) {
                    } finally {
                        clients.remove(client)
                    }
                }
            } catch (_: RejectedExecutionException) {
                clients.remove(client)
                client.close()
            }
        }
    }

    private fun transfer(client: Socket) {
        client.soTimeout = 95_000
        val ws = if (SystemClock.elapsedRealtime() < retryAt) null else try {
            TgBridge.Ws.connectTls(relayHost, "/clash-assets")
        } catch (e: Exception) {
            retryAt = SystemClock.elapsedRealtime() + 15_000
            DnsStats.count("clash_relay_fail")
            if (!listener.isClosed) onRelayResult(TgBridge.relayFailure(e))
            null
        }
        if (ws == null) {
            DnsStats.count("clash_relay_fallback")
            transferDirect(client)
            return
        }
        ws.use {
            DnsStats.count("clash_relay_ok")
            val uploadEnded = upload(client, ws) { data, size -> ws.send(data, 0, size) }
            val out = client.getOutputStream()
            var received = false
            while (true) {
                val chunk = try {
                    ws.read()
                } catch (e: Exception) {
                    if (!received && !listener.isClosed && !uploadEnded.get()) onRelayResult(TgBridge.relayFailure(e))
                    return
                }
                if (chunk == null) {
                    if (!received && !listener.isClosed && !uploadEnded.get()) onRelayResult(ws.closeCode)
                    break
                }
                out.write(chunk)
                if (!received) {
                    received = true
                    if (!listener.isClosed) onRelayResult("ok")
                }
                val ack = chunk.size.toString().toByteArray(Charsets.US_ASCII)
                ws.send(ack, 0, ack.size, opcode = 1)
            }
        }
    }

    private fun transferDirect(client: Socket) {
        Socket().use { upstream ->
            upstream.connect(InetSocketAddress(DnsPolicy.CLASH_ASSETS, 443), 4000)
            upstream.soTimeout = 95_000
            val out = upstream.getOutputStream()
            upload(client, upstream) { bytes, size -> out.write(bytes, 0, size) }
            upstream.getInputStream().copyTo(client.getOutputStream(), 32768)
        }
    }

    private fun upload(client: Socket, upstream: Closeable, send: (ByteArray, Int) -> Unit): AtomicBoolean {
        val ended = AtomicBoolean(false)
        Thread({
            try {
                val buffer = ByteArray(32768)
                val input = client.getInputStream()
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    send(buffer, size)
                }
            } catch (_: Exception) {
            } finally {
                ended.set(true)
                try { upstream.close() } catch (_: Exception) {}
            }
        }, "clash-assets-send").apply { isDaemon = true; start() }
        return ended
    }

    override fun close() {
        try { listener.close() } catch (_: Exception) {}
        workers.shutdownNow()
        for (client in clients) {
            try { client.close() } catch (_: Exception) {}
        }
    }
}
