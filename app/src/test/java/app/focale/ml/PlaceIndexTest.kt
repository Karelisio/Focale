package app.focale.ml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PlaceIndexTest {

    companion object {
        private lateinit var index: PlaceIndex

        @BeforeClass
        @JvmStatic
        fun load() {
            index = File("src/main/assets/geo/cities.bin").inputStream().use(PlaceIndex::read)
        }
    }

    @Test
    fun `la base complète est chargée`() {
        assertTrue(index.size > 150_000)
    }

    @Test
    fun `villes connues`() {
        val paris = index.nearest(48.8566, 2.3522)!!
        assertEquals("FR", paris.countryCode)
        assertTrue(paris.distanceKm < 5)
        assertEquals("JP", index.nearest(35.6762, 139.6503)!!.countryCode)
        assertEquals("BR", index.nearest(-22.9068, -43.1729)!!.countryCode)
    }

    @Test
    fun `antiméridien et océan`() {
        assertEquals("FJ", index.nearest(-17.7134, 178.0650)!!.countryCode)
        assertNull(index.nearest(-35.0, -30.0))
    }
}
