package com.october.fixer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.Icon
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class FixVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var tunOut: FileOutputStream? = null
    private var reader: Thread? = null
    private var sender: Thread? = null
    private var pool: ExecutorService? = null
    private var byedpi: Process? = null
    private var sockTun: FileDescriptor? = null
    private var sockHev: ParcelFileDescriptor? = null
    private var hevStarted = false
    private var relay: UdpRelay? = null
    @Volatile private var destroyed = false
    @Volatile private var latestStartId = 0
    @Volatile private var foreground = false
    private val foregroundLock = Any()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var dnsNetwork = DnsNetwork(null)
    private var gameDnsEnabled = false
    private var clashAssets: ClashAssetRelay? = null
    private var hevWatch: Thread? = null
    private val restartPolicy = RestartPolicy()
    @Volatile private var session = 0 // bumped by every startVpn: a late failure or restart of an older one is ignored
    @Volatile private var restartPending = false // hev and byedpi may die together: one restart, not two failures
    private val worker = Executors.newSingleThreadExecutor() // start/stop take ~1 s and would freeze the UI

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            // stopSelf(startId): a start that came while this stop was queued keeps the service alive
            worker.execute { if (!destroyed) stopVpn(startId = startId) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) { // settings changed: same service, fresh byedpi/tun
            worker.execute { if (!destroyed) restartVpn(startId) }
            return if (foreground) START_STICKY else START_NOT_STICKY
        }
        // Android requires prompt promotion after startForegroundService. Native setup stays on the worker.
        if (!foreground && !promoteToForeground()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        worker.execute {
            if (!destroyed && !running) {
                // A queued stop may have removed the notification while this start waited on the worker.
                if (foreground || promoteToForeground()) startVpn(startId)
                else stopSelf(startId)
            }
        }
        return START_STICKY
    }

    private fun restartVpn(startId: Int) {
        if (running) {
            stopVpn(final = false)
            if (!destroyed) startVpn(startId)
        } else if (!foreground) {
            stopSelf(startId)
        }
    }

    @Synchronized
    private fun startVpn(startId: Int) {
        var stage = "vpn_establish"
        session++
        val mine = session
        restartPending = false
        lastError = null
        notice = null
        tgError = null
        tgLocalCode = "off"
        tgRelayCode = "not_used"
        clashRelayCode = "not_used"
        total = 0; viaDoh = 0; viaUdp = 0; failed = 0; viaTunnel = 0; viaRelay = 0
        try {
            Diagnostics.noteServiceKilled(this)
            if (prepare(this) != null) throw StartFailure("permission_missing", "Разрешение Android на VPN не выдано")
            DnsStats.reset()
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (prefs.contains(LEGACY_KEY_TG)) prefs.edit().remove(LEGACY_KEY_TG).apply()
            val gameApps = SUPERCELL_APPS.filter { pkg ->
                try {
                    packageManager.getPackageInfo(pkg, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
            gameDnsEnabled = gameApps.isNotEmpty()
            val domain = tgDomain(this)
            if (domain.isNotEmpty() && CLASH_APP in gameApps) {
                clashAssets = try {
                    ClashAssetRelay(TgBridge.workerHost(domain)) { code -> onClashRelayResult(mine, code) }
                } catch (_: Exception) {
                    clashRelayCode = "listener_failed"
                    Diagnostics.recordVpnStage(this, "clash_assets_relay", clashRelayCode)
                    notice = CLASH_RELAY_NOTICE // the VPN keeps running without the asset relay
                    null
                }
            }
            dnsNetwork = DnsNetwork(getSystemService(ConnectivityManager::class.java).activeNetwork)
            stage = "byedpi_start"
            startByedpi()
            Diagnostics.recordVpnStage(this, stage, "ok")
            stage = "vpn_establish"
            val fd = establish(gameApps) ?: run {
                if (prepare(this) != null) throw StartFailure("permission_missing", "Android отозвал разрешение на VPN")
                throw StartFailure("vpn_conflict", "Android не создал VPN. Проверьте, не включён ли другой VPN")
            }
            Diagnostics.recordVpnStage(this, stage, "ok")
            tun = fd
            val out = FileOutputStream(fd.fileDescriptor)
            tunOut = out
            relay = UdpRelay({ protect(it) }, { p ->
                try {
                    synchronized(out) { out.write(p) }
                } catch (_: Exception) {
                }
            }, { ip, _, rtt -> onRobloxServer(ip, rtt) })
            stage = "tunnel_start"
            startTunnel()
            Diagnostics.recordVpnStage(this, stage, "ok")
            pool = Executors.newFixedThreadPool(8)
            if (byedpi?.let { isAlive(it) } != true) {
                stage = "byedpi_start"
                throw StartFailure("native_exit", "byedpi завершился при запуске VPN")
            }
            stage = "network_watch"
            watchNetwork()
            if (destroyed) {
                stopVpn(final = false)
                return
            }
            if (domain.isNotEmpty()) startTelegram(domain, mine)
            running = true
            Diagnostics.setVpnAlive(this, true) // after `running`: the activity reads the flag only while not running
            Diagnostics.recordVpnStage(this, "vpn_running", "ok")
            broadcastState()
            reader = Thread({ readLoop(fd, gameApps) }, "fix-reader").also { it.start() }
            sender = Thread({ tunnelToTun() }, "fix-tunnel-rx").also { it.start() }
            hevWatch = Thread({ watchTunnel(mine) }, "fix-hev-watch").also { it.start() }
        } catch (e: Throwable) {
            val code = (e as? StartFailure)?.code ?: when (stage) {
                "byedpi_start" -> "native_start_failed"
                "vpn_establish" -> "establish_failed"
                "tunnel_start" -> "tunnel_failed"
                "network_watch" -> "network_watch_failed"
                else -> "unknown"
            }
            Diagnostics.recordVpnStage(this, stage, code)
            lastError = (e as? StartFailure)?.message ?: when (stage) {
                "byedpi_start" -> "Не удалось запустить byedpi"
                "vpn_establish" -> "Android не смог создать VPN-интерфейс"
                "tunnel_start" -> "Не удалось запустить туннель VPN"
                "network_watch" -> "Не удалось начать отслеживание подключения"
                else -> "Не удалось запустить VPN"
            }
            Log.e(TAG, "start failed", e)
            stopVpn(startId = startId)
        }
    }

    private class StartFailure(val code: String, message: String) : IllegalStateException(message)

    private fun startTelegram(domain: String, mine: Int) {
        try {
            val secret = try {
                TgBridge.parseSecret(tgSecret(this)).also { require(it.size == 16) }
            } catch (_: IllegalArgumentException) {
                throw StartFailure("bad_secret", "Ключ локального прокси повреждён")
            }
            val bridge = TgBridge(domain, secret,
                relayResult = { code -> onTelegramRelayResult(mine, code) },
                listenerFailed = { onTelegramListenerFailed(mine) },
            )
            bridge.start()
            tg = bridge
            tgLocalCode = "listening"
            Diagnostics.recordVpnStage(this, "telegram_start", "ok")
        } catch (e: Exception) {
            Log.w(TAG, "tg bridge failed", e)
            val code = when (e) {
                is StartFailure -> e.code
                is java.net.BindException -> "port_in_use"
                else -> "listener_failed"
            }
            tgError = when (code) {
                "bad_secret" -> e.message
                "port_in_use" -> "Порт ${TgBridge.PORT} занят"
                else -> "Не удалось запустить локальный прокси"
            }
            tgLocalCode = code
            Diagnostics.recordVpnStage(this, "telegram_start", code)
        }
    }

    @Synchronized private fun onTelegramRelayResult(mine: Int, code: String) {
        if (!running || session != mine || tgRelayCode == code) return
        tgRelayCode = code
        tgError = TgBridge.relayErrorMessage(code)
        Diagnostics.recordVpnStage(this, "telegram_relay", code)
        broadcastState(refreshTile = false)
    }

    @Synchronized private fun onTelegramListenerFailed(mine: Int) {
        if (!running || session != mine) return
        tg?.close()
        tg = null
        tgLocalCode = "listener_failed"
        tgError = "Локальный прокси прекратил работу"
        Diagnostics.recordVpnStage(this, "telegram_start", "listener_failed")
        broadcastState(refreshTile = false)
    }

    @Synchronized private fun onClashRelayResult(mine: Int, code: String) {
        if (!running || session != mine || clashRelayCode == code) return
        clashRelayCode = code
        notice = TgBridge.relayErrorMessage(code)?.let { "Clash Royale: $it" }
        Diagnostics.recordVpnStage(this, "clash_assets_relay", code)
        broadcastState(refreshTile = false)
    }

    private fun establish(gameApps: List<String>): ParcelFileDescriptor? {
        val b = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress("10.111.0.1", 32)
            .addDnsServer("10.111.0.2")
            .addRoute("10.111.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(MTU)
            .setBlocking(true)
            .setMetered(false) // a metered VPN makes YouTube and Roblox lower quality and postpone downloads
        // IPv4 only: the phone hops between v4-only Wi-Fi and v6 LTE (v4 via clat), and v6 through
        // byedpi fails with "Network is unreachable" whenever the current network lacks it
        val allowed = mutableSetOf<String>()
        for (pkg in APPS + gameApps) {
            try {
                b.addAllowedApplication(pkg)
                allowed.add(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        // with no allowed app the VPN would take the whole phone, Fixer's own DoH and byedpi sockets included
        if (allowed.isEmpty()) throw StartFailure("no_supported_apps", "Для VPN не найдено ни одного поддерживаемого приложения")
        return b.establish()?.also {
            allowedApps = allowed.size
            routedApps = allowed
        }
    }

    private fun startByedpi() {
        val exe = File(applicationInfo.nativeLibraryDir, "libbyedpi.so")
        if (!exe.isFile) throw StartFailure("native_missing", "В APK отсутствует byedpi для этого устройства")
        // first group: Roblox hosts pass through untouched (desync made its CDN stall)
        // Supercell asset CDNs are not blocked here, but desync stalls them (Brawl Stars stuck at "Загрузка данных")
        val direct = NO_DESYNC_HOSTS + RuDomains.noDesyncHosts() +
            if (gameDnsEnabled) DnsPolicy.gameDomains + DnsPolicy.supercellAssetHosts else emptyList()
        val skip = File(filesDir, "nodesync.txt").apply { writeText(direct.joinToString("\n")) }
        // The game protocol on TCP/9339 does not carry a TLS SNI or HTTP Host.
        val gamePorts = if (gameDnsEnabled) listOf("-V", "9339", "-An") else emptyList()
        val assetRoute = clashAssets?.let {
            listOf("-j", ":${DnsPolicy.CLASH_RELAY_IP}/32", "-V", "443", "-C", "127.0.0.1:${it.port}", "-An")
        }.orEmpty()
        val p = ProcessBuilder(
            listOf(exe.path, "--ip", "127.0.0.1", "--port", SOCKS_PORT.toString()) +
                assetRoute + gamePorts + listOf("-H", skip.path, "-An") + DPI_ARGS
        )
            .redirectErrorStream(true)
            .start()
        byedpi = p
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { Log.d(TAG, "byedpi: $it") }
            } catch (_: Exception) {
            }
            onByedpiExit(p)
        }, "byedpi-log").start()
        if (p.waitFor(300, TimeUnit.MILLISECONDS)) {
            throw StartFailure("native_exit", "byedpi завершился при запуске (код ${p.exitValue()})")
        }
    }

    @Synchronized
    private fun onByedpiExit(p: Process) {
        if (running && byedpi === p) onComponentDied("byedpi_died", "byedpi завершился")
    }

    /** hev has no exit callback: poll it while this session runs. */
    private fun watchTunnel(mine: Int) {
        try {
            while (running && session == mine) {
                Thread.sleep(WATCH_TUNNEL_MS)
                if (!running || session != mine) return
                if (!TProxy.TProxyIsRunning()) {
                    onTunnelExit(mine)
                    return
                }
            }
        } catch (_: InterruptedException) {
            // stopVpn
        }
    }

    @Synchronized
    private fun onTunnelExit(mine: Int) {
        if (running && session == mine) onComponentDied("tunnel_died", "Туннель завершился")
    }

    /** One restart per minute (after 1 s); a second failure inside the window stops the VPN with [message]. */
    private fun onComponentDied(stage: String, message: String) {
        if (restartPending) return
        Diagnostics.recordVpnStage(this, stage, "native_exit")
        restartPending = true
        val mine = session
        val startId = latestStartId
        if (restartPolicy.onFailure(SystemClock.elapsedRealtime())) {
            Log.w(TAG, "$stage: restarting")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // an orderly stop in the meantime has cleared `running`
                    worker.execute { if (!destroyed && running && session == mine) restartVpn(startId) }
                } catch (_: RejectedExecutionException) {
                }
            }, RESTART_DELAY_MS)
        } else {
            // not inline: this may run on fix-hev-watch, which stopVpn interrupts (it would cut its own stop short)
            try {
                worker.execute {
                    if (!destroyed && running && session == mine) {
                        lastError = message
                        stopVpn(startId = startId)
                    }
                }
            } catch (_: RejectedExecutionException) {
            }
        }
    }

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (dnsNetwork.network == network) return
                dnsNetwork = DnsNetwork(network)
                relay?.reset()
                Diagnostics.recordVpnStage(this@FixVpnService, "network_changed", "ok")
            }

            override fun onLost(network: Network) {
                if (dnsNetwork.network != network) return
                dnsNetwork = DnsNetwork(null)
                relay?.reset()
            }
        }
        cm.registerDefaultNetworkCallback(cb) // our uid is outside the VPN, so this is the real network
        netCallback = cb
    }

    private fun isAlive(p: Process): Boolean = try {
        p.exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun startTunnel() {
        val a = FileDescriptor()
        val b = FileDescriptor()
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, a, b)
        sockTun = a
        try {
            for (f in listOf(a, b)) {
                Os.setsockoptInt(f, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, SOCK_BUF)
                Os.setsockoptInt(f, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, SOCK_BUF)
            }
            // A stalled hev must not block the reader that also serves DNS and UDP.
            Os.setsockoptTimeval(a, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, StructTimeval.fromMillis(20))
            sockHev = ParcelFileDescriptor.dup(b)
        } finally {
            Os.close(b)
        }
        val cfg = File(filesDir, "hev.yml")
        cfg.writeText(
            "tunnel:\n  mtu: $MTU\n" +
                "socks5:\n  port: $SOCKS_PORT\n  address: 127.0.0.1\n  udp: 'udp'\n" +
                "misc:\n  log-level: warn\n  log-file: ${File(filesDir, "hev.log").path}\n"
        )
        if (!TProxy.TProxyStartService(cfg.path, sockHev!!.fd)) throw IllegalStateException("hev не запустился")
        hevStarted = true
        Thread.sleep(200)
        if (!TProxy.TProxyIsRunning()) throw IllegalStateException("hev завершился при старте")
    }

    private fun readLoop(fd: ParcelFileDescriptor, gameApps: List<String>) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = tunOut ?: return
        val sock = sockTun ?: return
        val buf = ByteArray(32767)
        val cm = getSystemService(ConnectivityManager::class.java)
        fun packageUid(pkg: String): Int? = try {
            packageManager.getPackageUid(pkg, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        val quicPolicy = QuicPolicy(
            robloxUid = packageUid(ROBLOX),
            fallbackUids = (APPS + gameApps).filter { it != ROBLOX }.mapNotNull(::packageUid).toSet(),
            nowMs = SystemClock::elapsedRealtime,
            ownerUid = { source, destination -> cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, source, destination) },
        )
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (buf[0].toInt() and 0xF0 == 0x60) DnsStats.count("tun_ipv6")
                val udp = PacketUtils.parseUdp(buf, n)
                val req = udp?.takeIf { it.dstPort == 53 }
                if (req != null) {
                    DnsStats.count("tun_dns")
                    pool?.execute {
                        val reply = PacketUtils.buildReply(req, resolve(req.payload))
                        try {
                            synchronized(output) { output.write(reply) }
                        } catch (_: Exception) {
                        }
                    }
                    continue
                }
                if (udp != null) { // game traffic etc.: direct, byedpi's UDP relay broke Roblox joins (error 279)
                    if (udp.dstPort == 443) {
                        when (quicPolicy.route(udp)) {
                            QuicPolicy.Route.TCP_FALLBACK -> {
                                DnsStats.count("quic_drop")
                                continue
                            }
                            QuicPolicy.Route.ROBLOX -> DnsStats.count("quic_roblox")
                            QuicPolicy.Route.UNKNOWN -> DnsStats.count("quic_unknown")
                        }
                    }
                    relay?.send(udp)
                    viaRelay++
                    continue
                }
                // A fragment is not a complete datagram for UdpRelay, but the first header can still require TCP fallback.
                val fragment = PacketUtils.firstIpv4Udp443Fragment(buf, n)
                if (fragment != null && quicPolicy.route(fragment) == QuicPolicy.Route.TCP_FALLBACK) {
                    DnsStats.count("quic_drop")
                    continue
                }
                if (PacketUtils.isTcpTo(buf, n, TUN_DNS, 53)) DnsStats.count("tun_tcp53")
                try {
                    Os.write(sock, buf, 0, n)
                    viaTunnel++
                } catch (_: ErrnoException) {
                    // queue full (send timed out): drop like a congested link
                    DnsStats.count("tun_full")
                }
            }
        } catch (_: Exception) {
            // tun closed or pool shut down
        }
    }

    private fun tunnelToTun() {
        val output = tunOut ?: return
        val sock = sockTun ?: return
        val buf = ByteArray(32767)
        while (!Thread.currentThread().isInterrupted) {
            val n = try {
                Os.read(sock, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) continue else break
            }
            if (n <= 0) break
            try {
                synchronized(output) { output.write(buf, 0, n) }
            } catch (_: Exception) {
            }
        }
    }

    private fun resolve(query: ByteArray): ByteArray {
        val question = DnsPolicy.question(query) ?: return PacketUtils.servfail(query)
        if (question.type == 28) DnsStats.count("tun_dns_aaaa")
        val target = if (question.klass == 1 && question.type == 1) DnsStats.target(question.host) else null
        if (target == null) return resolve(query, question, null)
        val start = SystemClock.elapsedRealtime()
        val reply = resolve(query, question, target)
        if (DnsPolicy.answers(reply) == 0 && DnsPolicy.rcode(reply) != 2) DnsStats.count("returned_empty")
        if (SystemClock.elapsedRealtime() - start > 3000) DnsStats.count("slow_3s")
        return reply
    }

    /** [target] is the [DnsStats] bucket of an A query, or null when the query is not counted. */
    private fun resolve(query: ByteArray, question: DnsPolicy.Question, target: String?): ByteArray {
        val state = dnsNetwork
        total++
        if (clashAssets != null) {
            DnsPolicy.clashRelayReply(query, question)?.let { return it }
        }
        state.cache.get(query)?.let {
            if (target != null) DnsStats.hit(target, "cache", outcome(it, it, "error"), 0)
            return it
        }
        if (state.failures.has(query)) {
            if (target != null) DnsStats.hit(target, "cache", "servfail", 0)
            return PacketUtils.servfail(query)
        }
        if (question.local) {
            val at = SystemClock.elapsedRealtime()
            val answer = attempt(target, "system", query, state.system(query), "error", at)
            if (answer != null) {
                state.cache.put(query, answer)
                return answer
            }
        }
        val gameQuery = gameDnsEnabled && question.game && question.klass == 1 && question.type == 1
        if (gameQuery) {
            val answer = gameAnswer(state, query, target)
            if (answer != null) {
                state.cache.put(query, answer)
                return answer
            }
        }
        // A negative geo-DNS result for Roblox CDN is worth trying on the next upstream: it is kept
        // as a last resort but not accepted. Upstreams are raced by measured speed under one deadline.
        val rank = state.rank
        val upstreams = List(UPSTREAMS.size + UDP_SERVERS.size) { i ->
            { timeoutMs: Int ->
                var failure = "error"
                val at = SystemClock.elapsedRealtime()
                val raw = if (i < UPSTREAMS.size) doh(UPSTREAMS[i], query, timeoutMs) { failure = it }
                else udp(UDP_SERVERS[i - UPSTREAMS.size], query, timeoutMs) { failure = it }
                val name = if (i < UPSTREAMS.size) DOH_NAMES[i] else UDP_NAMES[i - UPSTREAMS.size]
                val r = attempt(target, name, query, raw, failure, at)
                if (r != null) rank.ok(i, (SystemClock.elapsedRealtime() - at).toInt()) else rank.fail(i)
                r
            }
        }
        val race = UpstreamRace.run(
            rank.order(), upstreams,
            accept = { !question.assetA || (DnsPolicy.rcode(it) == 0 && DnsPolicy.answers(it) > 0) },
            hedgeMs = rank.hedgeMs(),
        )
        val r = race.reply
        if (r != null && race.accepted) {
            if (race.index < UPSTREAMS.size) {
                state.preferred = race.index
                viaDoh++
            } else {
                viaUdp++
            }
            if (!gameQuery) state.cache.put(query, r)
            return r
        }
        if (r != null) return r
        failed++
        // only "no upstream answered at all"; an upstream's own SERVFAIL arrives as a reply (r != null) and is not remembered
        state.failures.put(query)
        return PacketUtils.servfail(query)
    }

    /**
     * Null's Proxy (DoT) and Xbox DNS (plain UDP/53: DoT cannot be verified for a bare IP) are asked in parallel.
     * Null's wins if its answer is usable and its server accepts TCP 9339 within [GAME_PREFERRED_MS] of the start;
     * only otherwise Xbox DNS is used (waiting at most [GAME_TOTAL_MS]). Null: use the normal path.
     */
    private fun gameAnswer(state: DnsNetwork, query: ByteArray, target: String?): ByteArray? {
        val reachability = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<ByteArray, Boolean>())
        fun candidate(source: String): Callable<GameDnsAnswer?> = Callable {
            val at = SystemClock.elapsedRealtime()
            val raw = if (source == "dot_nulls") state.game(query) { doh(UPSTREAMS[0], it) }
            else XBOX_DNS.firstNotNullOfOrNull { server ->
                udp(server, query, 1200) {}?.takeIf { DnsPolicy.usable(query, it) && DnsPolicy.rcode(it) == 0 && DnsPolicy.answers(it) > 0 }
            }
            val reply = raw?.takeIf {
                DnsPolicy.usable(query, it) && DnsPolicy.rcode(it) == 0 && DnsPolicy.answers(it) > 0
            }
            val ms = (SystemClock.elapsedRealtime() - at).toInt()
            val reachable = reply != null && gameReachable(query, reply).also { reachability[reply] = it }
            // DnsStats has no "udp_xbox" row: Xbox DNS is counted as dot_xbox. "ok" = a reply and a reachable server.
            if (target != null) DnsStats.hit(target, if (source == "dot_nulls") source else "dot_xbox", if (reachable) "ok" else "error", ms)
            reply?.let { GameDnsAnswer(it, source, ms) }
        }
        val isReachable = { reply: ByteArray -> reachability[reply] == true }
        state.gameWinner()?.let { first ->
            val answer = try { candidate(first).call() } catch (_: Exception) { null }
            if (answer != null && isReachable(answer.reply)) return answer.reply
        }
        val start = SystemClock.elapsedRealtime()
        val nulls: Future<GameDnsAnswer?>
        val xbox: Future<GameDnsAnswer?>
        try {
            nulls = GAME_POOL.submit(candidate("dot_nulls"))
            xbox = GAME_POOL.submit(candidate("udp_xbox"))
        } catch (_: RejectedExecutionException) {
            return null
        }
        fun await(f: Future<GameDnsAnswer?>, until: Long): GameDnsAnswer? =
            try {
                f.get((until - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: Exception) {
                null
            }
        try {
            val first = await(nulls, start + GAME_PREFERRED_MS)
            // Xbox DNS may already be done: it is only chosen if Null's is not reachable
            val early = listOfNotNull(first, if (xbox.isDone) await(xbox, start) else null)
            val winner = pickGameAnswer(early, "dot_nulls", isReachable)
                ?: pickGameAnswer(listOfNotNull(await(xbox, start + GAME_TOTAL_MS)), "dot_nulls", isReachable)
                ?: return null
            state.rememberGameWinner(winner.source)
            return winner.reply
        } finally {
            nulls.cancel(true)
            xbox.cancel(true)
        }
    }

    /** The game protocol's TCP port on the first A address of [reply]. */
    private fun gameReachable(query: ByteArray, reply: ByteArray): Boolean {
        val address = DnsPolicy.ipv4Answers(query, reply).firstOrNull() ?: return false
        return try {
            java.net.Socket().use { it.connect(InetSocketAddress(address, 9339), 1500); true }
        } catch (_: Exception) {
            false
        }
    }

    /** Counts one upstream attempt for a counted [target]; returns [reply] if it is usable, else null. */
    private fun attempt(target: String?, upstream: String, query: ByteArray, reply: ByteArray?, failure: String, startedAt: Long): ByteArray? {
        val usable = reply?.takeIf { DnsPolicy.usable(query, it) }
        if (target != null) {
            DnsStats.hit(target, upstream, outcome(usable, reply, failure), (SystemClock.elapsedRealtime() - startedAt).toInt())
        }
        return usable
    }

    /** [failure] is why there is no reply at all: "timeout" or "error". */
    private fun outcome(usable: ByteArray?, reply: ByteArray?, failure: String): String = when {
        usable != null -> when {
            DnsPolicy.rcode(usable) == 3 -> "nxdomain"
            DnsPolicy.answers(usable) > 0 -> "ok"
            else -> "empty"
        }
        reply == null -> failure
        reply.size >= 12 && DnsPolicy.rcode(reply) == 2 -> "servfail"
        else -> "error"
    }

    private fun failureOf(e: Exception): String = if (e is java.net.SocketTimeoutException) "timeout" else "error"

    private fun udp(host: String, query: ByteArray, timeoutMs: Int, onFailure: (String) -> Unit): ByteArray? {
        var s: DatagramSocket? = null
        return try {
            s = DatagramSocket()
            protect(s)
            s.soTimeout = minOf(2000, timeoutMs)
            val addr = InetAddress.getByName(host)
            s.connect(addr, 53)
            s.send(DatagramPacket(query, query.size, addr, 53))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            if (p.length >= 12) buf.copyOf(p.length) else null
        } catch (e: Exception) {
            onFailure(failureOf(e))
            null
        } finally {
            s?.close()
        }
    }

    private fun doh(url: String, query: ByteArray, timeoutMs: Int = 2500, onFailure: ((String) -> Unit)? = null): ByteArray? {
        var c: HttpURLConnection? = null
        return try {
            // The app UID is outside the VPN. Keep Android's normal socket path here.
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = minOf(2000, timeoutMs)
                readTimeout = minOf(2500, timeoutMs)
                doOutput = true
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
            }
            val (request, ecsAdded) = PacketUtils.withEcsZero(query)
            DnsStats.count(if (ecsAdded) "ecs_added" else "ecs_skipped")
            c.outputStream.use { it.write(request) }
            if (c.responseCode != 200) {
                c.disconnect()
                return null
            }
            // read fully and close the stream (no disconnect) so the TLS connection is reused
            val body = c.inputStream.use { input ->
                val buffer = ByteArray(4097)
                var length = 0
                while (length < buffer.size) {
                    val read = input.read(buffer, length, buffer.size - length)
                    if (read < 0) break
                    length += read
                }
                buffer.copyOf(length)
            }
            if (body.size in 12..4096) body else null
        } catch (e: Exception) {
            onFailure?.invoke(failureOf(e))
            c?.disconnect()
            null
        }
    }

    private fun promoteToForeground(): Boolean = synchronized(foregroundLock) {
        if (foreground) return@synchronized true
        try {
            startForegroundNotification()
            foreground = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "foreground promotion failed", e)
            lastError = "Android не разрешил запустить фоновый сервис VPN"
            Diagnostics.recordVpnStage(this, "vpn_foreground", "foreground_failed")
            broadcastState()
            false
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FixVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        b.setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_shades)
            .setContentIntent(open)
            .setOngoing(true)
        if (Build.VERSION.SDK_INT >= 23) {
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_shades), getString(R.string.notif_stop), stop).build()
            )
        } else {
            @Suppress("DEPRECATION")
            b.addAction(R.drawable.ic_shades, getString(R.string.notif_stop), stop)
        }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    /** The first Roblox game response after a requested join supplies the network RTT. */
    private fun onRobloxServer(ip: ByteArray, rttMs: Int) {
        val token = Mm2.takeJoin() ?: return
        val nm = getSystemService(NotificationManager::class.java)
        val where = Mm2.city(ip) ?: "Сервер"
        val good = rttMs <= Mm2.GOOD_MS
        val result = "$where · сеть $rttMs мс"
        if (Mm2.pickToken != token) return
        Mm2.last = result
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MM2, "Подбор сервера Roblox", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
        val b = Notification.Builder(this, CHANNEL_MM2)
            .setContentTitle(result)
            .setContentText(if (good) "Хорошая задержка" else "Можно попробовать другой сервер")
            .setSmallIcon(R.drawable.ic_shades)
            .setAutoCancel(true)
        if (good) {
            b.setTimeoutAfter(10_000)
        } else {
            val retry = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_SERVER_RETRY, token)
            val action = PendingIntent.getActivity(
                this, 2, retry, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_shades), "Другой сервер", action
            ).build())
        }
        try {
            nm.notify(NOTIF_MM2, b.build())
        } catch (_: SecurityException) {
            // Measured RTT is still available on the main screen.
        }
    }

    @Synchronized
    private fun stopVpn(final: Boolean = true, startId: Int? = null) {
        Diagnostics.setVpnAlive(this, false) // before `running`: see startVpn
        running = false
        session++
        allowedApps = 0
        routedApps = emptySet()
        clashAssets?.close()
        clashAssets = null
        Mm2.disarm()
        dnsNetwork = DnsNetwork(null)
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        netCallback = null
        hevWatch?.interrupt(); hevWatch = null
        reader?.interrupt(); reader = null
        sender?.interrupt()
        sockTun?.let { try { Os.shutdown(it, OsConstants.SHUT_RDWR) } catch (_: ErrnoException) {} }
        pool?.shutdownNow(); pool = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null; tunOut = null
        relay?.close(); relay = null
        tg?.close(); tg = null
        if (hevStarted) {
            try { TProxy.TProxyStopService() } catch (_: Throwable) {}
            hevStarted = false
        }
        byedpi?.let { p ->
            byedpi = null
            p.destroy()
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                p.waitFor(500, TimeUnit.MILLISECONDS)
            }
        }
        sender?.join(500); sender = null
        try { sockHev?.close() } catch (_: Exception) {}
        sockHev = null
        sockTun?.let { try { Os.close(it) } catch (_: Exception) {} }
        sockTun = null
        if (!final) return
        notice = null
        Diagnostics.recordVpnStage(this, "vpn_stop", "ok")
        broadcastState()
        synchronized(foregroundLock) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        if (startId != null) stopSelf(startId) else stopSelf()
    }

    private fun broadcastState(refreshTile: Boolean = true) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        if (!refreshTile) return
        FixTileService.refresh()
        try {
            TileService.requestListeningState(this, ComponentName(this, FixTileService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onRevoke() {
        val startId = latestStartId
        try {
            worker.execute {
                if (!destroyed) {
                    lastError = "Android отозвал разрешение на VPN"
                    Diagnostics.recordVpnStage(this, "vpn_revoked", "permission_missing")
                    stopVpn(startId = startId)
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdown()
        synchronized(this) { if (running) stopVpn() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.october.fixer.STOP"
        const val ACTION_STATE = "com.october.fixer.STATE"
        const val ACTION_RESTART = "com.october.fixer.RESTART"
        const val EXTRA_SERVER_RETRY = "com.october.fixer.SERVER_RETRY"
        const val PREFS = "fixer"
        private const val LEGACY_KEY_TG = "tg"
        const val KEY_TG_DOMAIN = "tg_domain"
        fun tgDomain(c: android.content.Context): String =
            c.getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TG_DOMAIN, null).orEmpty()

        /** The local proxy's secret, made once and kept: Telegram stores it with the proxy entry. */
        @Synchronized fun tgSecret(c: android.content.Context): String {
            val prefs = c.getSharedPreferences(PREFS, MODE_PRIVATE)
            prefs.getString(KEY_TG_SECRET, null)?.let { return it }
            return TgBridge.newSecret().also { prefs.edit().putString(KEY_TG_SECRET, it).apply() }
        }
        private const val KEY_TG_SECRET = "tg_secret"
        private const val CHANNEL = "fixer"
        private const val TAG = "Fixer"
        private const val NOTIF_ID = 1
        private const val NOTIF_MM2 = 2
        private const val CHANNEL_MM2 = "mm2"
        const val ROBLOX = "com.roblox.client"
        private const val MTU = 1500
        private const val RESTART_DELAY_MS = 1000L
        private const val WATCH_TUNNEL_MS = 5000L
        private const val CLASH_RELAY_NOTICE = "Clash Royale: ретранслятор недоступен, обновления игры могут не скачаться"
        private const val GAME_PREFERRED_MS = 1500L
        private const val GAME_TOTAL_MS = 4000L
        private val XBOX_DNS = listOf("111.88.96.50", "111.88.96.51")
        // game-host DNS candidates run here; bounded, daemon threads end by themselves (every attempt has a timeout)
        private val GAME_POOL = ThreadPoolExecutor(
            0, 8, 30, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "game-dns").apply { isDaemon = true } },
        )
        private const val SOCK_BUF = 1 shl 20
        private const val SOCKS_PORT = 21080
        // picked by testing on the user's ISP (Wi-Fi "Bureya_5G"); the simpler disorder/tlsrec presets time out.
        // "-b+500" is --buf-size 500, not an offset; tested on the phone: same speed, and -b 16384 failed some requests
        private val DPI_ARGS = "-s1 -q1 -Y -Ar -s5 -o1+s -At -f-1 -r1+s -As -s1 -o1+s -s-1 -An -b+500".split(" ")
        private val NO_DESYNC_HOSTS = listOf("roblox.com", "rbxcdn.com", "rbx.com", "rbxinfra.com", "roblox.cn")
        private val APPS = listOf(
            "com.google.android.youtube", "com.roblox.client", "com.android.chrome", "com.mi.globalbrowser",
            "com.yandex.browser", "org.mozilla.firefox", "com.opera.browser", "com.sec.android.app.sbrowser",
            "app.revanced.android.youtube",
        )
        internal const val CLASH_APP = "com.supercell.clashroyale"
        internal const val BRAWL_APP = "com.supercell.brawlstars"
        private val SUPERCELL_APPS = listOf(CLASH_APP, BRAWL_APP)
        private val UPSTREAMS = listOf(
            // Google honours ECS 0/0; Cloudflare ignores ECS and answers for its own (Russian) PoP
            "https://8.8.8.8/dns-query",
            "https://8.8.4.4/dns-query",
            "https://1.1.1.1/dns-query",
            "https://1.0.0.1/dns-query",
        )
        private val UDP_SERVERS = listOf("9.9.9.9", "149.112.112.112", "8.8.8.8")
        // DnsStats names, by index into UPSTREAMS and UDP_SERVERS
        private val DOH_NAMES = listOf("doh_g1", "doh_g2", "doh_cf1", "doh_cf2")
        private val UDP_NAMES = listOf("udp_q1", "udp_q2", "udp_g")
        private val TUN_DNS = byteArrayOf(10, 111, 0, 2)

        @Volatile
        var running = false

        @Volatile
        var lastError: String? = null

        /** A low-priority note shown while the VPN runs (the VPN itself works), or null. */
        @Volatile
        var notice: String? = null

        /** Local proxy or relay failure shown while the VPN runs. */
        @Volatile
        var tgError: String? = null

        /** The running Telegram proxy, closed with the VPN. */
        @Volatile
        var tg: TgBridge? = null
            private set

        @Volatile internal var tgLocalCode = "off"
            private set

        @Volatile internal var tgRelayCode = "not_used"
            private set

        @Volatile internal var clashRelayCode = "not_used"
            private set

        @Volatile internal var routedApps: Set<String> = emptySet()
            private set

        /** Apps routed into the current VPN session, 0 while it is off. */
        @Volatile
        var allowedApps = 0

        @Volatile
        var total = 0

        @Volatile
        var viaDoh = 0

        @Volatile
        var viaUdp = 0

        @Volatile
        var failed = 0

        @Volatile
        var viaTunnel = 0

        @Volatile
        var viaRelay = 0
    }
}
