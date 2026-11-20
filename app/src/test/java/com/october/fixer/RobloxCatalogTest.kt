package com.october.fixer

import org.junit.Assert.*
import org.junit.Test

class RobloxCatalogTest {
    @Test fun acceptsNumericPlaceIdsAndRobloxGameLinks() {
        assertEquals(142823291L, RobloxCatalog.extractPlaceId("142823291"))
        assertEquals(123456L, RobloxCatalog.extractPlaceId("https://www.roblox.com/games/123456/some-game?privateServerLinkCode=x"))
        assertEquals(123456L, RobloxCatalog.extractPlaceId("https://www.roblox.com/places/123456"))
    }

    @Test fun rejectsAmbiguousOrForeignUrls() {
        assertNull(RobloxCatalog.extractPlaceId("0"))
        assertNull(RobloxCatalog.extractPlaceId("https://example.com/games/123"))
        assertNull(RobloxCatalog.extractPlaceId("https://roblox.com/users/123/profile"))
        assertNull(RobloxCatalog.extractPlaceId("https://evilroblox.com/games/123"))
    }

    @Test fun parsesPublicCatalogResults() {
        val places = RobloxCatalog.parseSearch("""{"games":[{"rootPlaceId":123,"name":"Example","creator":{"name":"Maker"}},{"placeId":456,"name":"Other"}]}""")
        assertEquals(2, places.size)
        assertEquals(RobloxCatalog.Place(123, "Example", "Maker"), places[0])
        assertEquals(456L, places[1].id)
    }

    @Test fun parsesOmniSearchGameGroupsAndDeduplicatesRootPlaces() {
        val places = RobloxCatalog.parseSearch("""{"searchResults":[{"contentGroupType":"User","contents":[{"rootPlaceId":8,"name":"Not a game"}]},{"contentGroupType":"Game","contents":[{"rootPlaceId":123,"name":"Example","creatorName":"Maker"},{"rootPlaceId":123,"name":"Duplicate"},{"universeId":9,"name":"No root place"}]}]}""")
        assertEquals(1, places.size)
        assertEquals(RobloxCatalog.Place(123, "Example", "Maker"), places.single())
    }

    @Test fun parsesOmniSearchFixture() {
        val json = javaClass.getResourceAsStream("/omni-search.json")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val places = RobloxCatalog.parseSearch(json)
        assertTrue(places.isNotEmpty())
        assertTrue(places.first().id > 0)
        assertTrue(places.first().name.isNotBlank())
        assertEquals(RobloxCatalog.Place(142823291L, "Murder Mystery 2", "Example Studio"), places.first())
    }

    @Test fun iconsAreMatchedByPlaceAndRestrictedToRobloxCdn() {
        val icons = RobloxCatalog.parseIconUrls(
            """{"data":[
                {"targetId":222,"state":"Completed","imageUrl":"https://tr.rbxcdn.com/b"},
                {"targetId":111,"state":"Completed","imageUrl":"https://tr.rbxcdn.com/a"},
                {"targetId":333,"state":"Pending","imageUrl":"https://tr.rbxcdn.com/c"},
                {"targetId":444,"state":"Completed","imageUrl":"https://tr.rbxcdn.com.example.org/d"},
                {"targetId":555,"state":"Completed","imageUrl":"https://tr.rbxcdn.com/e"}
            ]}""",
            setOf(111L, 222L, 333L, 444L)
        )
        assertEquals(mapOf(111L to "https://tr.rbxcdn.com/a", 222L to "https://tr.rbxcdn.com/b"), icons)
        assertNull(RobloxCatalog.trustedIcon("http://tr.rbxcdn.com/a"))
        assertNull(RobloxCatalog.trustedIcon("https://tr.rbxcdn.com:81/a"))
    }
}
