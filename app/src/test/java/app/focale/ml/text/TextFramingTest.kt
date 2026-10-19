package app.focale.ml.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextFramingTest {

    @Test
    fun `une photo est réduite à 1600 px sur son côté le plus long`() {
        assertEquals(1600 to 1200, TextFraming.decodeSize(4000, 3000))
        assertEquals(1200 to 1600, TextFraming.decodeSize(3000, 4000))
        assertEquals(1600 to 400, TextFraming.decodeSize(8000, 2000))
    }

    @Test
    fun `une petite image n'est jamais agrandie`() {
        assertEquals(800 to 600, TextFraming.decodeSize(800, 600))
        assertEquals(20 to 20, TextFraming.decodeSize(20, 20))
    }

    @Test
    fun `une capture d'écran haute garde sa largeur d'écran`() {
        assertEquals(1080 to 2400, TextFraming.decodeSize(1080, 2400))
        assertEquals(1200 to 2667, TextFraming.decodeSize(1440, 3200))
        // Capture d'un écran 16:9 : réduite comme une photo.
        assertEquals(900 to 1600, TextFraming.decodeSize(1080, 1920))
    }

    @Test
    fun `une page défilée très longue respecte le plafond de pixels`() {
        val (width, height) = TextFraming.decodeSize(1080, 20_000)
        assertTrue(width.toLong() * height <= TextFraming.MAX_PIXELS)
        assertEquals(20_000.0 / 1080, height.toDouble() / width, 0.05)
    }

    @Test
    fun `une image basse est lue d'un seul tenant`() {
        assertEquals(listOf(TextBand(0, 1600, 0, 1600)), TextFraming.bands(1600))
    }

    @Test
    fun `une capture haute est lue en bandes qui se chevauchent`() {
        val bands = TextFraming.bands(2400)
        assertEquals(listOf(TextBand(0, 1280, 0, 1200), TextBand(1120, 2400, 1200, 2400)), bands)
    }

    @Test
    fun `chaque ligne revient à une seule bande, entièrement visible dans celle-ci`() {
        for (height in listOf(1601, 2400, 5000, 10_541, 23_570)) {
            val bands = TextFraming.bands(height)
            assertEquals(0, bands.first().top)
            assertEquals(height, bands.last().bottom)
            bands.forEach { assertTrue("bande trop haute pour $height", it.height <= TextFraming.BAND_HEIGHT) }
            bands.zipWithNext().forEach { (a, b) ->
                assertEquals("chevauchement pour $height", TextFraming.BAND_OVERLAP, a.bottom - b.top)
                assertEquals("zones propres contiguës pour $height", a.ownBottom, b.ownTop)
            }
            // Une ligne de titre (jusqu'à 140 px) centrée n'importe où tient dans la bande qui la reçoit.
            for (center in 0 until height step 7) {
                val owners = bands.filter { it.owns(center + 0.5f) }
                assertEquals("une seule bande pour y = $center ($height)", 1, owners.size)
                val owner = owners.single()
                assertTrue(center - 70 >= owner.top || owner.top == 0)
                assertTrue(center + 70 <= owner.bottom || owner.bottom == height)
            }
        }
    }
}
