package com.october.fixer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal object RobloxImages {
    private const val CDN = "tr.rbxcdn.com"
    private const val MAX_IMAGE = 512 * 1024
    private val cdnPath = Regex("/[A-Za-z0-9_/-]+")
    private var addresses = emptyList<String>()
    private var refreshAt = 0L

    fun load(value: String): Bitmap? {
        val url = RobloxCatalog.trustedIcon(value) ?: return null
        val bytes = direct(url) ?: fallback(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun direct(url: URI): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = url.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3500
            connection.readTimeout = 4000
            if (connection.responseCode != 200 || connection.contentLength > MAX_IMAGE) return null
            connection.inputStream.use(::readImage)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readImage(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) return out.toByteArray()
            if (out.size() + size > MAX_IMAGE) return null
            out.write(buffer, 0, size)
        }
    }

    private fun fallback(url: URI): ByteArray? {
        val path = url.rawPath ?: return null
        if (url.host != CDN || url.rawQuery != null || !cdnPath.matches(path)) return null
        for (address in cdnAddresses()) {
            if (Thread.currentThread().isInterrupted) return null
            val image = fetchCdnAtAddress(address, path)
            if (image != null) return image
        }
        return null
    }

    @Synchronized
    private fun cdnAddresses(): List<String> {
        val now = SystemClock.elapsedRealtime()
        if (now >= refreshAt) {
            addresses = RobloxCatalog.cdnAddresses()
            refreshAt = now + if (addresses.isEmpty()) 15_000 else 300_000
        }
        return addresses
    }

    // The app's UID uses Android DNS directly. If geo-DNS hides this CDN, use DoH's address while
    // keeping tr.rbxcdn.com as the HTTP host and the authenticated TLS peer.
    private fun fetchCdnAtAddress(address: String, path: String): ByteArray? = try {
        Socket().use { raw ->
            raw.connect(InetSocketAddress(address, 443), 2500)
            raw.soTimeout = 3000
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            (factory.createSocket(raw, CDN, 443, true) as SSLSocket).use { tls ->
                tls.sslParameters = tls.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    serverNames = listOf(SNIHostName(CDN))
                }
                tls.soTimeout = 3000
                tls.startHandshake()
                val request = "GET $path HTTP/1.1\r\nHost: $CDN\r\nAccept: image/png\r\nConnection: close\r\n\r\n"
                tls.outputStream.apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
                readCdnResponse(BufferedInputStream(tls.inputStream))
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun readCdnResponse(input: BufferedInputStream): ByteArray? {
        val headers = ByteArrayOutputStream()
        var last = 0
        repeat(8192) {
            val next = input.read()
            if (next < 0) return null
            headers.write(next)
            last = (last shl 8) or next
            if (last == 0x0d0a0d0a) {
                val lines = headers.toString("US-ASCII").split("\r\n")
                if (lines.first().split(' ').getOrNull(1) != "200") return null
                val size = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: return null
                if (size !in 1..MAX_IMAGE) return null
                return ByteArray(size).also { DataInputStream(input).readFully(it) }
            }
        }
        return null
    }
}
