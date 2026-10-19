package app.focale.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaDetailsParsingTest {

    @Test
    fun `coordonnées ISO 6709 des vidéos`() {
        val (lat, lon) = MediaDetailsRepository.parseIso6709("+48.8584+002.2945/")!!
        assertEquals(48.8584, lat, 1e-6)
        assertEquals(2.2945, lon, 1e-6)
        val (lat2, lon2) = MediaDetailsRepository.parseIso6709("-33.8688+151.2093+012.000/")!!
        assertEquals(-33.8688, lat2, 1e-6)
        assertEquals(151.2093, lon2, 1e-6)
    }

    @Test
    fun `coordonnées absentes ou nulles`() {
        assertNull(MediaDetailsRepository.parseIso6709(null))
        assertNull(MediaDetailsRepository.parseIso6709(""))
        assertNull(MediaDetailsRepository.parseIso6709("+00.0000+000.0000/"))
    }

    @Test
    fun `date EXIF`() {
        assertNotNull(MediaDetailsRepository.parseExifDate("2023:07:14 18:42:05"))
        assertNull(MediaDetailsRepository.parseExifDate("pas une date"))
    }
}
