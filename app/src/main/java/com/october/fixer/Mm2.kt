package com.october.fixer

import android.net.Uri
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

sealed class PickResult {
    data class Server(val jobId: String, val ping: Int?) : PickResult()
    object PlaceNotFound : PickResult()
    object NoServers : PickResult()
    object AllFull : PickResult()
    object RateLimited : PickResult()
    object Offline : PickResult()
}

/** Public Roblox server picker. No account cookie or authenticated endpoint is used. */
object Mm2 {
    const val PLACE_ID = 142823291L
    const val GOOD_MS = 120
    const val COOLDOWN_MS = 20_000L
    private const val CACHE_MS = 60_000L
    private const val ARMED_MS = 90_000L
    private const val MIN_PLAYERS = 3

    class Server(val id: String, val playing: Int, val maxPlayers: Int, val ping: Int?)

    private data class Pool(var servers: List<Server> = emptyList(), var at: Long = 0, val tried: MutableSet<String> = hashSetOf())
    private val pools = HashMap<Long, Pool>()
    @Volatile var lastPick = 0L
    private var armedUntil = 0L
    @Volatile var armedPlaceId = PLACE_ID
        private set
    @Volatile var pickToken: String? = null
        private set
    @Volatile var last: String? = null

    @Synchronized
    fun arm(placeId: Long, now: Long = SystemClock.elapsedRealtime()) {
        armedPlaceId = placeId
        pickToken = UUID.randomUUID().toString()
        armedUntil = now + ARMED_MS
        last = null
    }

    @Synchronized
    fun disarm() {
        armedUntil = 0
        pickToken = null
    }

    @Synchronized
    fun takeJoin(now: Long = SystemClock.elapsedRealtime()): String? {
        if (now >= armedUntil) return null
        armedUntil = 0
        return pickToken
    }

    /**
     * Blocking public server-list request; call from a worker thread. [cancelled] is checked between
     * pages and retries; a cancelled call returns [PickResult.Offline] and the caller drops it.
     */
    fun next(placeId: Long, cancelled: () -> Boolean = { false }): PickResult {
        require(placeId > 0) { "placeId must be positive" }
        val stale = synchronized(this) { val p = pools.getOrPut(placeId) { Pool() }; System.currentTimeMillis() - p.at > CACHE_MS || p.servers.none { it.id !in p.tried } }
        val fetched = if (stale) fetch(placeId, cancelled) else null
        if (cancelled()) return PickResult.Offline
        synchronized(this) {
            val p = pools.getOrPut(placeId) { Pool() }
            var code = 200
            if (fetched != null) {
                if (fetched.code == 200) {
                    p.servers = fetched.servers; p.at = System.currentTimeMillis()
                    if (fetched.servers.all { it.id in p.tried }) p.tried.clear()
                } else code = fetched.code
            }
            // A failed refresh falls back to the old pool unless the place itself is gone.
            if (code != 200 && (code == 400 || code == 404 || p.servers.none { it.id !in p.tried })) {
                return classify(code, 0, 0) ?: PickResult.Offline
            }
            classify(200, p.servers.size, p.servers.count { it.playing < it.maxPlayers })?.let { return it }
            val selected = if (placeId == PLACE_ID) pick(p.servers, p.tried) else pickPlace(p.servers, p.tried)
            return selected?.also { p.tried.add(it.id) }?.let { PickResult.Server(it.id, it.ping) } ?: PickResult.NoServers
        }
    }

    /** Maps an HTTP status and list sizes to a failure; null means the list has open servers to pick from. */
    fun classify(code: Int, servers: Int, open: Int): PickResult? = when {
        code == 400 || code == 404 -> PickResult.PlaceNotFound
        code == 429 -> PickResult.RateLimited
        code != 200 -> PickResult.Offline
        servers == 0 -> PickResult.NoServers
        open == 0 -> PickResult.AllFull
        else -> null
    }

    fun pick(list: List<Server>, skip: Set<String>, random: java.util.Random = java.util.Random()): Server? {
        return pickWithMinimum(list, skip, MIN_PLAYERS, random)
    }

    fun pickPlace(list: List<Server>, skip: Set<String>, random: java.util.Random = java.util.Random()): Server? =
        pickWithMinimum(list, skip, 0, random)

    private fun pickWithMinimum(list: List<Server>, skip: Set<String>, minimumPlayers: Int, random: java.util.Random): Server? {
        val open = list.filter { it.id !in skip && it.playing >= minimumPlayers && it.playing < it.maxPlayers }
        if (open.isEmpty()) return null
        val sorted = open.sortedBy { it.ping ?: Int.MAX_VALUE }
        val top = sorted.take(maxOf(1, (sorted.size + 1) / 2))
        return top[random.nextInt(top.size)]
    }

    private class Fetch(val code: Int, val servers: List<Server>)
    private class Response(val code: Int, val body: String?)

    private fun fetch(placeId: Long, cancelled: () -> Boolean): Fetch {
        var cursor: String? = null
        val all = LinkedHashMap<String, Server>()
        // Bound pagination so a pathological cursor loop or huge place cannot hold the picker thread indefinitely.
        repeat(3) {
            if (cancelled()) return Fetch(-1, all.values.toList())
            val query = buildString {
                append("https://games.roblox.com/v1/games/").append(placeId)
                append("/servers/Public?limit=100&sortOrder=Desc&excludeFullGames=true")
                cursor?.let { append("&cursor=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val response = request(query, cancelled)
            val body = response.body ?: return if (all.isEmpty()) Fetch(response.code, emptyList()) else Fetch(200, all.values.toList())
            val root = try { JSONObject(body) } catch (_: Exception) { return Fetch(200, all.values.toList()) }
            parse(body).forEach { all[it.id] = it }
            val minimum = if (placeId == PLACE_ID) MIN_PLAYERS else 0
            if (all.values.count { it.playing in minimum until it.maxPlayers } >= 40) return Fetch(200, all.values.toList())
            cursor = root.optString("nextPageCursor").takeIf { it.isNotEmpty() && it != "null" } ?: return Fetch(200, all.values.toList())
        }
        return Fetch(200, all.values.toList())
    }

    /** Up to three attempts on 429, 5xx or a network error; the last status (-1 for no answer) is returned. */
    private fun request(url: String, cancelled: () -> Boolean): Response {
        var wait = 500L
        var code = -1
        repeat(3) { attempt ->
            if (cancelled()) return Response(-1, null)
            var c: HttpURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 7000; c.readTimeout = 7000; c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "SV-Fixer/1.3")
                code = c.responseCode
                if (code == 200) return Response(200, c.inputStream.bufferedReader().use { it.readText() })
                if (code != 429 && code !in 500..599) return Response(code, null)
            } catch (_: Exception) {
                code = -1
            } finally { c?.disconnect() }
            if (attempt < 2) try { Thread.sleep(wait) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return Response(-1, null) }
            wait *= 2
        }
        return Response(code, null)
    }

    fun parse(json: String): List<Server> {
        val data = try { JSONObject(json).optJSONArray("data") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val max = o.optInt("maxPlayers"); val playing = o.optInt("playing")
            if (max <= 0 || playing < 0 || playing > max) return@mapNotNull null
            Server(id, playing, max, if (o.isNull("ping")) null else o.optInt("ping").takeIf { it >= 0 })
        }
    }

    fun joinUri(placeId: Long, jobId: String): Uri = Uri.parse("roblox://experiences/start?placeId=$placeId&gameInstanceId=${Uri.encode(jobId)}")
    fun webJoinUri(placeId: Long, jobId: String): Uri = Uri.parse("https://www.roblox.com/games/start?placeId=$placeId&gameInstanceId=${Uri.encode(jobId)}")

    fun isRobloxServer(ip: ByteArray) = ip.size == 4 && ip[0] == 128.toByte() && ip[1] == 116.toByte() && (ip[2].toInt() and 0x80) == 0
    fun city(ip: ByteArray): String? = if (isRobloxServer(ip)) CITIES[ip[2].toInt() and 255] else null
    private val CITIES: Map<Int, String> = buildMap {
        for (n in listOf(5, 44)) put(n, "Франкфурт"); put(21, "Амстердам"); put(13, "Париж"); put(33, "Лондон")
        for (n in listOf(11, 53, 56, 74, 80, 87, 102)) put(n, "Эшберн, США"); put(32, "Нью-Йорк"); put(48, "Чикаго")
        for (n in listOf(45, 127)) put(n, "Майами"); put(95, "Даллас"); for (n in listOf(1, 63)) put(n, "Лос-Анджелес"); put(115, "Сиэтл")
        for (n in listOf(50, 54, 97)) put(n, "Сингапур"); for (n in listOf(55, 120)) put(n, "Токио"); put(51, "Сидней")
    }
}
