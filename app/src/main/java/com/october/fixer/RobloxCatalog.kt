package com.october.fixer

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.ByteArrayOutputStream

/** Resolves public place links, numeric IDs, or a public experience-name search. */
object RobloxCatalog {
    data class Place(val id: Long, val name: String, val creator: String? = null)

    /** Search public experiences by name via omni-search: at most 20 distinct games, null when Roblox does not answer. */
    internal fun query(input: String): List<Place>? {
        val value = input.trim()
        if (value.isBlank() || value.length > MAX_QUERY || value.contains("://") || value.startsWith("//")) return emptyList()
        val url = "https://apis.roblox.com/search-api/omni-search?searchQuery=${URLEncoder.encode(value, "UTF-8")}&sessionId=0&pageType=all"
        return get(url)?.let(::parseSearch)
    }

    internal fun iconUrls(places: List<Place>): Map<Long, String> {
        val ids = places.map { it.id }.distinct().take(MAX_RESULTS)
        if (ids.isEmpty()) return emptyMap()
        val url = "https://thumbnails.roblox.com/v1/places/gameicons?placeIds=${ids.joinToString(",")}&size=150x150&format=Png&isCircular=false"
        return get(url)?.let { parseIconUrls(it, ids.toSet()) }.orEmpty()
    }

    internal fun parseIconUrls(json: String, expected: Set<Long>): Map<Long, String> {
        val data = try { JSONObject(json).optJSONArray("data") } catch (_: Exception) { null } ?: return emptyMap()
        return buildMap {
            for (i in 0 until data.length()) {
                val entry = data.optJSONObject(i) ?: continue
                val id = entry.optLong("targetId")
                val image = entry.optString("imageUrl")
                if (id in expected && entry.optString("state") == "Completed" && trustedIcon(image) != null) put(id, image)
            }
        }
    }

    internal fun trustedIcon(value: String): java.net.URI? {
        val uri = try { java.net.URI(value) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        return uri.takeIf {
            it.scheme == "https" && host.endsWith(".rbxcdn.com") &&
                it.userInfo == null && it.port == -1 && it.fragment == null
        }
    }

    internal fun cdnAddresses(): List<String> {
        val url = "https://8.8.8.8/resolve?name=tr.rbxcdn.com&type=A&edns_client_subnet=0.0.0.0%2F0"
        val json = get(url) ?: return emptyList()
        val answers = try { JSONObject(json).optJSONArray("Answer") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until answers.length()).mapNotNull { i ->
            val row = answers.optJSONObject(i) ?: return@mapNotNull null
            row.optString("data").takeIf { row.optInt("type") == 1 && IPV4.matches(it) }
        }.distinct().take(3)
    }

    fun extractPlaceId(value: String): Long? {
        val s = value.trim()
        if (s.matches(Regex("[0-9]{1,20}"))) return s.toLongOrNull()?.takeIf { it > 0 }
        val uri = try { java.net.URI(s) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        if (host != "roblox.com" && !host.endsWith(".roblox.com")) return null
        val parts = uri.path.split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() !in setOf("games", "places")) return null
        return parts.getOrNull(1)?.takeIf { it.matches(Regex("[0-9]{1,20}")) }?.toLongOrNull()?.takeIf { it > 0 }
    }

    fun parseSearch(json: String): List<Place> {
        val root = try { JSONObject(json) } catch (_: Exception) { return emptyList() }
        // omni-search groups results by content type; only Game results have joinable rootPlaceIds.
        val groups = root.optJSONArray("searchResults") ?: root.optJSONArray("games") ?: root.optJSONArray("data") ?: return emptyList()
        val places = ArrayList<Place>()
        for (i in 0 until groups.length()) {
            val group = groups.optJSONObject(i) ?: continue
            if (group.has("contentGroupType") && group.optString("contentGroupType") != "Game") continue
            val contents = group.optJSONArray("contents")
            if (contents != null) {
                for (j in 0 until contents.length()) parsePlace(contents.optJSONObject(j))?.let(places::add)
            } else parsePlace(group)?.let(places::add)
        }
        return places.distinctBy { it.id }.take(MAX_RESULTS)
    }

    private fun parsePlace(o: JSONObject?): Place? {
        o ?: return null
        val id = o.optLong("rootPlaceId").takeIf { it > 0 }
            ?: o.optLong("placeId").takeIf { it > 0 }
            ?: return null
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
        val creator = o.optString("creatorName").takeIf { it.isNotBlank() }
            ?: o.optJSONObject("creator")?.optString("name")?.takeIf { it.isNotBlank() }
        return Place(id, name, creator)
    }

    private fun get(url: String): String? {
        repeat(2) { attempt ->
            var c: HttpURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 7000; c.readTimeout = 7000
                c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "SV-Fixer/1.3")
                val code = c.responseCode
                if (code == 200) return c.inputStream.use { stream ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (total < MAX_RESPONSE_BYTES) {
                        val n = stream.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
                        if (n < 0) break
                        out.write(buffer, 0, n); total += n
                    }
                    String(out.toByteArray(), Charsets.UTF_8)
                }
                if (code != 429 && code !in 500..599) return null
            } catch (_: Exception) {
                return null
            } finally { c?.disconnect() }
            if (attempt == 0) try { Thread.sleep(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return null }
        }
        return null
    }

    private const val MAX_QUERY = 100
    private const val MAX_RESULTS = 20
    private const val MAX_RESPONSE_BYTES = 1_048_576
    private val IPV4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
}
