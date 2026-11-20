package com.october.fixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentPlacesTest {
    private fun place(id: Long) = RobloxCatalog.Place(id, "Game $id", if (id % 2 == 0L) "Maker" else null)

    @Test
    fun duplicateMovesToFrontWithoutRepeat() {
        val list = RecentPlaces.add(listOf(place(1), place(2), place(3)), place(3))
        assertEquals(listOf(3L, 1L, 2L), list.map { it.id })
    }

    @Test
    fun ninthEvictsOldest() {
        var list = emptyList<RobloxCatalog.Place>()
        for (id in 1L..9L) list = RecentPlaces.add(list, place(id))
        assertEquals(RecentPlaces.MAX, list.size)
        assertEquals(9L, list.first().id)
        assertTrue(list.none { it.id == 1L })
    }

    @Test
    fun garbageDecodesToEmpty() {
        assertTrue(RecentPlaces.decode("{{").isEmpty())
        assertTrue(RecentPlaces.decode(null).isEmpty())
        assertTrue(RecentPlaces.decode("").isEmpty())
    }

    @Test
    fun roundTrips() {
        val list = listOf(place(2), place(1), RobloxCatalog.Place(142823291L, "Murder Mystery 2"))
        assertEquals(list, RecentPlaces.decode(RecentPlaces.encode(list)))
    }
}
