package app.focale.people

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceGeometryTest {

    @Test
    fun `recouvrement de deux cadres`() {
        val box = FaceBox(0.1f, 0.1f, 0.3f, 0.3f)
        assertEquals(1f, box.iou(box), 1e-6f)
        assertEquals(0f, box.iou(FaceBox(0.5f, 0.5f, 0.7f, 0.7f)), 0f)
        // Moitié décalée : intersection 0,02, union 0,06.
        assertEquals(1f / 3, box.iou(FaceBox(0.2f, 0.1f, 0.4f, 0.3f)), 1e-5f)
    }

    @Test
    fun `une nouvelle analyse retrouve les visages au même endroit`() {
        val previous = listOf(FaceBox(0.1f, 0.1f, 0.3f, 0.3f), FaceBox(0.6f, 0.2f, 0.8f, 0.4f))
        val detected = listOf(
            FaceBox(0.61f, 0.21f, 0.81f, 0.41f), // deuxième visage, à peine déplacé
            FaceBox(0.4f, 0.6f, 0.5f, 0.7f), // nouveau visage
            FaceBox(0.11f, 0.1f, 0.3f, 0.31f), // premier visage
        )
        assertArrayEquals(intArrayOf(1, -1, 0), FaceGeometry.matchPrevious(previous, detected))
    }

    @Test
    fun `un ancien visage n'est repris qu'une fois`() {
        val previous = listOf(FaceBox(0.1f, 0.1f, 0.3f, 0.3f))
        val detected = listOf(FaceBox(0.12f, 0.1f, 0.32f, 0.3f), FaceBox(0.1f, 0.1f, 0.3f, 0.3f))
        assertArrayEquals(intArrayOf(-1, 0), FaceGeometry.matchPrevious(previous, detected))
    }

    @Test
    fun `le recadrage est carré, agrandi et tient dans l'image`() {
        // Visage de 200 × 240 px au centre d'une photo 1000 × 800.
        val centered = FaceGeometry.squareCrop(FaceBox(0.4f, 0.35f, 0.6f, 0.65f), 1000, 800)
        assertEquals(centered.width, centered.height)
        assertEquals(384, centered.width)
        assertEquals(500, (centered.left + centered.right) / 2)
        // Visage collé au bord : le carré est décalé vers l'intérieur.
        val corner = FaceGeometry.squareCrop(FaceBox(0f, 0f, 0.1f, 0.1f), 1000, 800)
        assertEquals(0, corner.left)
        assertEquals(0, corner.top)
        // Visage immense : le carré se limite au petit côté de l'image.
        val huge = FaceGeometry.squareCrop(FaceBox(0f, 0f, 1f, 1f), 1000, 800)
        assertEquals(800, huge.width)
        assertTrue(huge.right <= 1000 && huge.bottom <= 800)
    }

    @Test
    fun `visages trop petits, de profil ou flous écartés`() {
        assertTrue(FaceQuality.acceptsPose(120, yaw = 10f, pitch = -5f))
        assertFalse(FaceQuality.acceptsPose(30, yaw = 0f, pitch = 0f))
        assertFalse(FaceQuality.acceptsPose(200, yaw = 60f, pitch = 0f))
        assertFalse(FaceQuality.acceptsPose(200, yaw = 0f, pitch = -45f))
        assertFalse(FaceQuality.acceptsSharpness(4.0))
        assertTrue(FaceQuality.acceptsSharpness(80.0))
    }

    @Test
    fun `la qualité préfère les grands visages nets et de face`() {
        val best = FaceQuality.score(300, 0f, 0f, 600.0)
        val small = FaceQuality.score(60, 0f, 0f, 600.0)
        val turned = FaceQuality.score(300, 30f, 0f, 600.0)
        val soft = FaceQuality.score(300, 0f, 0f, 20.0)
        assertEquals(1f, best, 1e-6f)
        assertTrue(small < best && turned < best && soft < best)
        assertTrue(listOf(best, small, turned, soft).all { it in 0f..1f })
    }
}
