package app.focale.ml

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {

    private fun flip(hash: Long, vararg bits: Int): Long = bits.fold(hash) { h, bit -> h xor (1L shl bit) }

    @Test
    fun `regroupe les empreintes proches et sépare les autres`() {
        val base = 0x5A3C_96F0_0FF0_A55AL
        val other = 0x0123_4567_89AB_CDEFL
        val groups = DuplicateFinder.group(
            listOf(1L to base, 2L to flip(base, 3, 17, 40), 3L to flip(base, 63), 4L to other, 5L to flip(other, 5)),
            maxDistance = 6,
        )
        assertEquals(2, groups.size)
        assertEquals(setOf(1L, 2L, 3L), groups[0].toSet())
        assertEquals(setOf(4L, 5L), groups[1].toSet())
    }

    @Test
    fun `aucune paire au-delà du seuil`() {
        val base = 0x5A3C_96F0_0FF0_A55AL
        val far = flip(base, 1, 9, 17, 25, 33, 41, 49, 57)
        assertTrue(DuplicateFinder.group(listOf(1L to base, 2L to far), maxDistance = 6).isEmpty())
    }

    @Test
    fun `ignore les images unies et reste rapide sur 20 000 empreintes`() {
        val random = Random(42)
        val hashes = (0 until 20_000).map { it.toLong() to random.nextLong() } + listOf(-1L to 0L, -2L to 0L)
        val start = System.nanoTime()
        val groups = DuplicateFinder.group(hashes)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(groups.none { -1L in it })
        assertTrue("trop lent : $elapsedMs ms", elapsedMs < 5_000)
    }
}
