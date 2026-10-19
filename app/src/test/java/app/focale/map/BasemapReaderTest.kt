package app.focale.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lit le fond de carte réellement embarqué dans l'application. */
class BasemapReaderTest {

    private val basemap = File("src/main/assets/geo/basemap.bin").inputStream().use(BasemapReader::read)

    @Test
    fun `deux niveaux de détail pour les terres et les frontières, lacs détaillés`() {
        val layers = basemap.layers.map { it.kind to it.detailed }
        assertEquals(
            listOf(
                BasemapKind.LAND to false,
                BasemapKind.BORDER to false,
                BasemapKind.LAND to true,
                BasemapKind.LAKE to true,
                BasemapKind.BORDER to true,
            ),
            layers,
        )
        basemap.layers.flatMap { it.rings }.forEach { ring ->
            for (i in ring.indices step 2) {
                assertTrue(ring[i] in -90.0..90.0)
                assertTrue(ring[i + 1] in -180.0..180.0)
            }
        }
    }

    @Test
    fun `noms des pays en anglais et en français`() {
        assertEquals(177, basemap.labels.size)
        val germany = basemap.labels.first { it.nameEn == "Germany" }
        assertEquals("Allemagne", germany.nameFr)
        assertTrue(germany.latitude in 47.0..55.0)
        assertTrue(germany.longitude in 5.0..15.0)
    }
}
