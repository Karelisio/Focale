package app.focale.map

import app.focale.core.database.GeoPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoClusteringTest {

    private fun photo(id: Long, latitude: Double, longitude: Double) =
        GeoPhoto(mediaId = id, latitude = latitude, longitude = longitude, mediaType = 1, dateTaken = 0, dateModified = 0, place = null)

    // Paris, Versailles (≈ 17 km) et Tokyo, de la plus récente à la plus ancienne.
    private val photos = listOf(
        photo(1, 48.8566, 2.3522),
        photo(2, 48.8049, 2.1204),
        photo(3, 35.6762, 139.6503),
    )

    @Test
    fun `vue du monde, Paris et Versailles regroupés et Tokyo à part`() {
        val clusters = PhotoClustering.cluster(photos, worldSizePx = 1024.0, cellPx = 64.0)
        assertEquals(2, clusters.size)
        val france = clusters.first { it.count == 2 }
        assertEquals(1L, france.cover.mediaId)
        assertEquals(48.8049, france.area.south, 1e-9)
        assertEquals(48.8566, france.area.north, 1e-9)
        assertEquals(2.1204, france.area.west, 1e-9)
        assertEquals(2.3522, france.area.east, 1e-9)
        assertEquals((48.8566 + 48.8049) / 2, france.latitude, 1e-9)
    }

    @Test
    fun `zoom de ville, chaque lieu devient un groupe`() {
        val worldAtZoom14 = 256.0 * (1 shl 14)
        val clusters = PhotoClustering.cluster(photos, worldSizePx = worldAtZoom14, cellPx = 64.0)
        assertEquals(3, clusters.size)
        assertTrue(clusters.all { it.count == 1 })
    }

    @Test
    fun `projection de Mercator normalisée`() {
        assertEquals(0.5, PhotoClustering.mercatorX(0.0), 1e-12)
        assertEquals(0.5, PhotoClustering.mercatorY(0.0), 1e-12)
        assertEquals(0.0, PhotoClustering.mercatorX(-180.0), 1e-12)
        assertTrue(PhotoClustering.mercatorY(60.0) < 0.5)
        assertTrue(PhotoClustering.mercatorY(-89.0) < 1.0)
    }
}
