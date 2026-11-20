package com.october.fixer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.CancellationSignal
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixed-field, bounded diagnostics. No exception text, addresses, user URLs, or log capture. */
object Diagnostics {
    private const val PREFS = "diagnostics_v1"
    private const val KEY_EVENTS = "events"
    private const val KEY_PROBES = "probes"
    private const val KEY_PROBES_AT = "probes_at"
    private const val KEY_VPN_ALIVE = "vpn_alive"
    private const val KEY_VPN_STAMP = "vpn_stamp"
    private const val KEY_VPN_PID = "vpn_pid"
    private const val KEY_VPN_AT = "vpn_at"
    private const val MAX_EVENTS = 16
    private val executor = Executors.newSingleThreadExecutor()
    private val vpnStages = setOf(
        "vpn_request", "vpn_foreground", "vpn_establish", "byedpi_start", "tunnel_start", "network_watch",
        "vpn_running", "vpn_revoked", "service_killed", "tunnel_died", "byedpi_died",
    )
    private val stages = vpnStages + setOf("vpn_stop", "network_changed", "probe", "telegram_start", "telegram_relay", "clash_assets_relay")
    private val codes = setOf(
        "ok", "permission_missing", "start_rejected", "foreground_failed", "vpn_conflict", "establish_failed",
        "no_supported_apps", "native_missing", "native_start_failed", "native_exit", "tunnel_failed",
        "network_watch_failed", "port_in_use", "listener_failed", "bad_secret", "timeout", "dns_failed", "tls_failed",
        "http_error", "http_403", "http_404", "http_429", "http_503", "http_5xx", "relay_protocol", "relay_connect",
        "relay_closed", "relay_upstream", "relay_busy", "exit_low_memory", "exit_user_requested", "exit_crash",
        "exit_anr", "exit_excessive_resource", "exit_signaled", "unknown",
    )

    /** Append a fixed vocabulary event; callers must map platform errors to one of [codes]. */
    @JvmStatic fun recordVpnStage(context: Context, stage: String, code: String) {
        val safeStage = stage.takeIf { it in stages } ?: return
        val safeCode = safeCode(code)
        synchronized(this) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getString(KEY_EVENTS, "")?.lineSequence()?.filter(::isStoredEvent)?.toList().orEmpty()
            val events = (old + "${System.currentTimeMillis()},$safeStage,$safeCode").takeLast(MAX_EVENTS)
            prefs.edit().putString(KEY_EVENTS, events.joinToString("\n")).apply()
        }
    }

    /** True from the moment the VPN runs until an orderly stop; survives the death of the process. */
    @JvmStatic fun setVpnAlive(context: Context, alive: Boolean) = synchronized(this) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_VPN_ALIVE, alive).putLong(KEY_VPN_STAMP, if (alive) installStamp(app) else 0L)
            .putInt(KEY_VPN_PID, if (alive) Process.myPid() else 0)
            .putLong(KEY_VPN_AT, if (alive) System.currentTimeMillis() else 0L).apply()
    }

    /** An active flag from the same installation marks an end that did not go through VPN shutdown. */
    @JvmStatic fun noteServiceKilled(context: Context) {
        synchronized(this) { // the activity and a restarted service may both get here: record once
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val alive = prefs.getBoolean(KEY_VPN_ALIVE, false)
            if (!alive) return
            val stamp = prefs.getLong(KEY_VPN_STAMP, 0L)
            val pid = prefs.getInt(KEY_VPN_PID, 0)
            val startedAt = prefs.getLong(KEY_VPN_AT, 0L)
            if (pid != 0 && pid == Process.myPid()) return
            setVpnAlive(app, false)
            if (wasKilled(alive, stamp, installStamp(app))) {
                recordVpnStage(context, "service_killed", processExitCode(app, pid, startedAt))
            } else { // the process died because the app was updated or reinstalled: drop an earlier false alarm
                val kept = withoutKilledBefore(prefs.getString(KEY_EVENTS, "")?.lineSequence()?.filter(::isStoredEvent)?.toList().orEmpty(), launchAt)
                prefs.edit().putString(KEY_EVENTS, kept.joinToString("\n")).apply()
            }
        }
    }

    /** Wall-clock time this process started using Diagnostics: events older than it come from an earlier process. */
    private val launchAt = System.currentTimeMillis()

    /** A leftover flag means a kill only if the app was not replaced since the flag was set; no stamp means a legacy flag. */
    internal fun wasKilled(aliveFlag: Boolean, storedStamp: Long, currentStamp: Long): Boolean =
        aliveFlag && storedStamp != 0L && storedStamp == currentStamp

    /** Events without `service_killed` rows recorded before [before] (rows of this launch stay). */
    internal fun withoutKilledBefore(events: List<String>, before: Long): List<String> =
        events.filterNot { it.contains(",service_killed,") && (it.substringBefore(',').toLongOrNull() ?: 0L) < before }

    private fun installStamp(context: Context): Long =
        try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }

    private fun processExitCode(context: Context, pid: Int, startedAt: Long): String {
        if (Build.VERSION.SDK_INT < 30 || pid <= 0 || startedAt <= 0L) return "unknown"
        val reason = try {
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(null, pid, 1)
                .firstOrNull()?.takeIf { it.timestamp >= startedAt }?.reason
        } catch (_: Exception) { null }
        return when (reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "exit_low_memory"
            ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> "exit_user_requested"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "exit_crash"
            ApplicationExitInfo.REASON_ANR -> "exit_anr"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "exit_excessive_resource"
            ApplicationExitInfo.REASON_SIGNALED -> "exit_signaled"
            else -> "unknown"
        }
    }

    /** Whether the latest VPN session ended outside its shutdown path. */
    @JvmStatic fun pendingServiceKill(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, "")?.lineSequence()?.filter(::isStoredEvent)?.let(::pendingServiceKill)

    internal fun pendingServiceKill(events: Sequence<String>): String? {
        val row = events.lastOrNull {
            when (it.substringAfter(',').substringBefore(',')) {
                "vpn_running", "vpn_stop", "vpn_request", "vpn_revoked", "service_killed" -> true
                else -> false
            }
        } ?: return null
        return row.takeIf { it.contains(",service_killed,") }?.substringAfterLast(',')?.let(::safeCode)
    }

    /**
     * Compares the phone's resolver with Google DoH (ECS 0/0) for fixed hosts and tries TCP on whichever gives IPv4 addresses,
     * preferring DoH. Runs outside the VPN in at most [PROBE_THREADS] threads and is cut off after [PROBE_BUDGET_MS].
     * Addresses are used in memory only and never reach the report, logs or prefs.
     */
    @JvmStatic fun runProbes(context: Context, callback: (String) -> Unit) {
        if (!probesActive.compareAndSet(false, true)) return // a run is already going: a second press does nothing
        executor.execute {
          try {
            val network = NetworkFacts.physicalNetwork(context)
            val deadline = SystemClock.elapsedRealtime() + PROBE_BUDGET_MS
            val pool = Executors.newFixedThreadPool(PROBE_THREADS)
            val started = java.util.concurrent.atomic.AtomicIntegerArray(PROBE_HOSTS.size)
            val lines = try {
                val tasks = PROBE_HOSTS.mapIndexed { i, host -> Callable { started.set(i, 1); probeHost(host, network, deadline) } }
                pool.invokeAll(tasks, PROBE_BUDGET_MS, TimeUnit.MILLISECONDS).flatMapIndexed { i, f ->
                    try { f.get() } catch (_: Exception) { fallbackLines(PROBE_HOSTS[i], started.get(i) == 1) }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                PROBE_HOSTS.flatMapIndexed { i, host -> fallbackLines(host, started.get(i) == 1) }
            } finally {
                pool.shutdownNow()
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PROBES, lines.joinToString("\n").take(2048))
                .putLong(KEY_PROBES_AT, System.currentTimeMillis()).apply()
            callback(lines.joinToString("\n"))
          } finally {
            probesActive.set(false)
          }
        }
    }

    private val probesActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while a probe run is going. */
    @JvmStatic fun probesRunning(): Boolean = probesActive.get()

    internal fun latestProbeResults(context: Context): List<Pair<String, ProbeReading>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val results = storedProbeLines(prefs.getString(KEY_PROBES, null))
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return PROBE_HOSTS.flatMap { host ->
            val names = if (host == AAAA_HOST) listOf(host, "${host}_aaaa") else listOf(host)
            names.mapNotNull { name -> results[probeKey(name)]?.let(::parseProbeReading)?.let { name to it } }
        }
    }

    private fun storedProbeLines(raw: String?): List<String> = raw?.lineSequence()
        ?.filter { it.startsWith("probe.") && ReportFormat.LINE.matches(it) }
        ?.take(PROBE_HOSTS.size + 1)?.toList().orEmpty()

    private val TIMED_OUT = DnsOutcome("timeout", 0, 0)

    /** Lines of a host whose task did not finish in the budget: `timeout` if it had started, `skip` if it never did. */
    internal fun fallbackLines(host: String, started: Boolean): List<String> {
        fun one(name: String) = if (started) probeLine(name, TIMED_OUT, TIMED_OUT, 0, 0) else ReportFormat.line(probeKey(name), "skip")
        return listOfNotNull(one(host), if (host == AAAA_HOST) one("${host}_aaaa") else null)
    }

    private fun probeHost(host: String, network: Network?, deadline: Long): List<String> {
        val query = DnsPolicy.addressQuery(host)
        val (sys, sysReply) = systemQuery(network, query)
        val (doh, dohReply) = dohQuery(network, query)
        val dohAddresses = dohReply?.let { DnsPolicy.ipv4Answers(query, it) }.orEmpty()
        val addresses = dohAddresses.ifEmpty { sysReply?.let { DnsPolicy.ipv4Answers(query, it) }.orEmpty() }
            .distinct().take(MAX_CONNECT)
        val source = when {
            addresses.isEmpty() -> null
            dohAddresses.isNotEmpty() -> "doh"
            else -> "sys"
        }
        val port = probePort(host)
        val (ok, checked) = countTcpProbes(addresses.asSequence().map { connects(network, it, port, deadline) })
        val out = mutableListOf(probeLine(host, sys, doh, ok, addresses.size, source, connectChecked = checked))
        if (host == AAAA_HOST) {
            val aaaa = query.copyOf().also { it[it.size - 3] = 28 } // QTYPE A (1) becomes AAAA (28)
            out += probeLine("${host}_aaaa", systemQuery(network, aaaa).first, dohQuery(network, aaaa).first, 0, 0)
        }
        return out
    }

    /** One query to the system resolver on [network]: the outcome and the raw reply, if any. */
    private fun systemQuery(network: Network?, query: ByteArray): Pair<DnsOutcome, ByteArray?> {
        val start = SystemClock.elapsedRealtime()
        val ready = CountDownLatch(1)
        val cancel = CancellationSignal()
        var reply: ByteArray? = null
        try {
            DnsResolver.getInstance().rawQuery(network, query, DnsResolver.FLAG_EMPTY, { it.run() }, cancel,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) { reply = answer; ready.countDown() }
                    override fun onError(error: DnsResolver.DnsException) { ready.countDown() }
                })
            val done = ready.await(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val ms = (SystemClock.elapsedRealtime() - start).toInt()
            val r = reply
            return when {
                !done -> DnsOutcome("timeout", 0, ms) to null
                r != null && r.size >= 12 -> DnsOutcome(rcodeName(DnsPolicy.rcode(r)), DnsPolicy.answers(r), ms) to r
                else -> DnsOutcome("error", 0, ms) to null
            }
        } catch (_: Exception) {
            return DnsOutcome("error", 0, (SystemClock.elapsedRealtime() - start).toInt()) to null
        } finally {
            cancel.cancel()
        }
    }

    /** One DoH POST to Google with ECS 0/0 (the real client subnet is never sent). */
    private fun dohQuery(network: Network?, query: ByteArray): Pair<DnsOutcome, ByteArray?> {
        val start = SystemClock.elapsedRealtime()
        var c: HttpURLConnection? = null
        fun ms() = (SystemClock.elapsedRealtime() - start).toInt()
        return try {
            val url = URL("https://8.8.8.8/dns-query")
            c = ((network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = DNS_TIMEOUT_MS.toInt()
                readTimeout = DNS_TIMEOUT_MS.toInt()
                doOutput = true
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
            }
            c.outputStream.use { it.write(PacketUtils.withEcsZero(query).first) }
            if (c.responseCode != 200) return DnsOutcome("error", 0, ms()) to null
            val bytes = ByteArray(4097)
            val size = c.inputStream.use { stream ->
                var length = 0
                while (length < bytes.size) {
                    val read = stream.read(bytes, length, bytes.size - length)
                    if (read == -1) break
                    length += read
                }
                length
            }
            if (size !in 12..4096) return DnsOutcome("error", 0, ms()) to null
            val body = bytes.copyOf(size)
            DnsOutcome(rcodeName(DnsPolicy.rcode(body)), DnsPolicy.answers(body), ms()) to body
        } catch (_: java.net.SocketTimeoutException) {
            DnsOutcome("timeout", 0, ms()) to null
        } catch (_: Exception) {
            DnsOutcome("error", 0, ms()) to null
        } finally {
            c?.disconnect()
        }
    }

    /** TCP port the probe tries for [host]: Supercell game servers listen on 9339, everything else on 443. */
    internal fun probePort(host: String): Int =
        if (host == "game.clashroyaleapp.com" || host == "game.brawlstarsgame.com") 9339 else 443

    /** A null result means the check ended before a full TCP attempt could finish. */
    internal fun countTcpProbes(attempts: Sequence<Boolean?>): Pair<Int, Int> {
        var connected = 0
        var checked = 0
        for (attempt in attempts) {
            if (attempt == null) break
            checked++
            if (attempt) connected++
        }
        return connected to checked
    }

    private fun connects(network: Network?, address: InetAddress, port: Int, deadline: Long): Boolean? {
        val timeout = minOf(CONNECT_TIMEOUT_MS, deadline - SystemClock.elapsedRealtime()).toInt()
        if (timeout <= 0 || Thread.currentThread().isInterrupted) return null
        return try {
            (network?.socketFactory?.createSocket() ?: Socket()).use {
                it.connect(InetSocketAddress(address, port), timeout)
                true
            }
        } catch (_: java.net.SocketTimeoutException) {
            if (timeout < CONNECT_TIMEOUT_MS) null else false
        } catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) null else false
        }
    }

    @JvmStatic fun buildReport(context: Context, vpnRunning: Boolean, vpnErrorCode: String? = null): String {
        val c = context.applicationContext
        val app = try { c.packageManager.getPackageInfo(c.packageName, 0) } catch (_: Exception) { null }
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val privateDns = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val mode = Settings.Global.getString(c.contentResolver, "private_dns_mode")
                when (mode?.lowercase(Locale.ROOT)) { "off" -> "off"; "hostname" -> "strict"; "opportunistic" -> "automatic"; else -> "unknown" }
            } else "unsupported"
        } catch (_: Exception) { "unavailable" }
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (OBSOLETE_KEYS.any { prefs.contains(it) }) {
            prefs.edit().apply { OBSOLETE_KEYS.forEach { remove(it) } }.apply()
        }
        val events = prefs.getString(KEY_EVENTS, "")
            ?.lineSequence()?.filter(::isStoredEvent)?.toList()?.takeLast(MAX_EVENTS).orEmpty()
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            caps != null -> "other"
            else -> "unavailable"
        }
        val lines = mutableListOf(
            "report=sv_fixer_v2",
            ReportFormat.line("app.version", app?.versionName ?: "unknown"),
            ReportFormat.line("app.code", app?.longVersionCode ?: 0L),
            ReportFormat.line("device", "${Build.MANUFACTURER} ${Build.MODEL}"),
            ReportFormat.line("android", Build.VERSION.RELEASE),
            ReportFormat.line("android.api", Build.VERSION.SDK_INT),
            ReportFormat.line("vpn.running", vpnRunning),
            ReportFormat.line("vpn.error_code", vpnErrorCode?.takeIf { it in codes } ?: latestVpnError(events, vpnRunning)),
            ReportFormat.line("vpn.allowed_apps", FixVpnService.allowedApps),
            ReportFormat.line("vpn.route.youtube", routeStatus(c, vpnRunning, "com.google.android.youtube", "app.revanced.android.youtube")),
            ReportFormat.line("vpn.route.roblox", routeStatus(c, vpnRunning, FixVpnService.ROBLOX)),
            ReportFormat.line("vpn.route.brawl", routeStatus(c, vpnRunning, FixVpnService.BRAWL_APP)),
            ReportFormat.line("vpn.route.clash", routeStatus(c, vpnRunning, FixVpnService.CLASH_APP)),
            ReportFormat.line("telegram.local", if (vpnRunning) FixVpnService.tgLocalCode else "off"),
            ReportFormat.line("telegram.relay", if (vpnRunning) FixVpnService.tgRelayCode else "off"),
            ReportFormat.line("clash.relay", if (vpnRunning) FixVpnService.clashRelayCode else "off"),
            ReportFormat.line("relay.config", if (FixVpnService.tgDomain(c).isEmpty()) "unset" else "custom"),
            ReportFormat.line("network.transport", transport),
            ReportFormat.line("network.validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false),
            ReportFormat.line("network.metered", cm?.isActiveNetworkMetered ?: false),
            ReportFormat.line("private_dns", privateDns),
            "probe.scope=outside_vpn",
        )
        val storedProbes = storedProbeLines(prefs.getString(KEY_PROBES, null))
        val now = System.currentTimeMillis()
        val at = prefs.getLong(KEY_PROBES_AT, 0L)
        val probeLines = if (storedProbes.isEmpty() || at <= 0L) storedProbes
        else storedProbes + ReportFormat.line("probe.age_s", ((now - at) / 1000).coerceIn(0L, 999_999L))
        return ReportFormat.assemble(
            lines, NetworkFacts.lines(c), probeLines,
            events.mapIndexed { i, row -> ReportFormat.line("event.${i + 1}", row) },
            DnsStats.tunLines(now), DnsStats.dnsLines(),
        )
    }

    private fun routeStatus(context: Context, vpnRunning: Boolean, vararg packages: String): String {
        if (vpnRunning && packages.any { it in FixVpnService.routedApps }) return "routed"
        val installed = packages.any { pkg ->
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) { false }
        }
        return if (!installed) "not_installed" else if (vpnRunning) "not_routed" else "vpn_off"
    }

    internal fun latestVpnError(events: List<String>, running: Boolean): String {
        if (running) return "none"
        for (row in events.asReversed()) {
            val stage = row.substringAfter(',').substringBefore(',')
            val code = row.substringAfterLast(',')
            if (stage == "vpn_running") return "none"
            if (stage in vpnStages && code != "ok") return safeCode(code)
        }
        return "none"
    }

    private const val AAAA_HOST = "tr.rbxcdn.com"
    private const val PROBE_THREADS = 3
    private const val PROBE_BUDGET_MS = 25_000L
    private const val DNS_TIMEOUT_MS = 3000L
    private const val CONNECT_TIMEOUT_MS = 2000L
    private const val MAX_CONNECT = 4

    private val PROBE_HOSTS = listOf(
        "www.youtube.com", "www.roblox.com",
        "tr.rbxcdn.com", "t0.rbxcdn.com", "c0.rbxcdn.com", "thumbnails.roblox.com",
        "game.clashroyaleapp.com", "game.brawlstarsgame.com",
        "game-assets.clashroyaleapp.com", "game-assets.brawlstarsgame.com",
    )

    internal fun safeCode(code: String): String = code.takeIf { it in codes } ?: "unknown"

    private val OBSOLETE_KEYS = listOf("latest_export", "latest_export_token", "latest_export_expires")

    private val EVENT = Regex("[0-9]{13},[a-z_]{1,24},[a-z0-9_]{1,24}")
    private fun isStoredEvent(value: String): Boolean = EVENT.matches(value)
}
