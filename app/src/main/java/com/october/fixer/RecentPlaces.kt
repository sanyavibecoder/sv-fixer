package com.october.fixer

import org.json.JSONArray
import org.json.JSONObject

/** Last places a server was picked for: newest first, no duplicates, stored as JSON. */
object RecentPlaces {
    const val MAX = 8

    fun add(list: List<RobloxCatalog.Place>, place: RobloxCatalog.Place): List<RobloxCatalog.Place> =
        (listOf(place) + list.filter { it.id != place.id }).take(MAX)

    fun encode(list: List<RobloxCatalog.Place>): String {
        val array = JSONArray()
        list.forEach { place ->
            array.put(JSONObject().put("id", place.id).put("name", place.name).apply { place.creator?.let { put("creator", it) } })
        }
        return array.toString()
    }

    fun decode(text: String?): List<RobloxCatalog.Place> {
        if (text.isNullOrBlank()) return emptyList()
        val array = try { JSONArray(text) } catch (_: Exception) { return emptyList() }
        val out = ArrayList<RobloxCatalog.Place>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optLong("id").takeIf { it > 0 } ?: continue
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            out.add(RobloxCatalog.Place(id, name, o.optString("creator").takeIf { it.isNotBlank() }))
        }
        return out.distinctBy { it.id }.take(MAX)
    }
}
