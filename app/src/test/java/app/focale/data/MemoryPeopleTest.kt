package app.focale.data

import app.focale.core.database.PersonPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryPeopleTest {

    private fun presence(id: Long, name: String?, photos: Int, months: Int = 0) = PersonPresence(id, name, photos, months)

    @Test
    fun `une personne nommée passe avant une personne sans nom plus présente`() {
        val chosen = MemoryPeople.choose(listOf(presence(1, null, 30, months = 12), presence(2, "Alice", 6)), total = 60)
        assertEquals(2L, chosen?.personId)
    }

    @Test
    fun `il faut au moins cinq médias de la fenêtre`() {
        assertNull(MemoryPeople.choose(listOf(presence(1, "Alice", 4)), total = 20))
        assertEquals(1L, MemoryPeople.choose(listOf(presence(1, "Alice", 5)), total = 20)?.personId)
    }

    @Test
    fun `une personne présente presque partout ferait doublon avec le souvenir général`() {
        // 16 médias sur 20 : 80 %, le souvenir général la montre déjà.
        val candidates = listOf(presence(1, "Alice", 16), presence(2, "Bob", 7))
        assertEquals(2L, MemoryPeople.choose(candidates, total = 20)?.personId)
        assertNull(MemoryPeople.choose(listOf(presence(1, "Alice", 5)), total = 6))
    }

    @Test
    fun `une personne sans nom doit revenir sur plusieurs mois`() {
        assertNull(MemoryPeople.choose(listOf(presence(1, null, 12, months = 2)), total = 40))
        assertEquals(1L, MemoryPeople.choose(listOf(presence(1, null, 12, months = 3)), total = 40)?.personId)
    }

    @Test
    fun `la plus présente, puis la plus ancienne à égalité`() {
        val candidates = listOf(presence(3, "Chloé", 8), presence(2, "Bob", 9), presence(1, "Alice", 9))
        assertEquals(1L, MemoryPeople.choose(candidates, total = 40)?.personId)
        assertNull(MemoryPeople.choose(emptyList(), total = 40))
    }
}
