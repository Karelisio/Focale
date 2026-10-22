package app.focale.print

import android.net.Uri
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Mise en page de l'impression : une photo par page, ajustée, dans le sens de la photo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrintLayoutTest {

    /** A4 (8,27 × 11,69 po), marges d'un quart de pouce. */
    private val a4Width = 8268
    private val a4Height = 11693

    private fun page(width: Int, height: Int, margin: Int = 250, dpi: Int = 300) =
        PrintLayout.page(width, height, a4Width, a4Height, margin, dpi)

    @Test
    fun `photo en paysage, page à l'italienne, photo entière et centrée`() {
        val page = page(4000, 3000)
        assertTrue(page.landscape)
        assertEquals(841, page.width)
        assertEquals(595, page.height)
        val photo = page.photo
        // 18 points de marge (un quart de pouce) : 805 × 559 disponibles ; en 4:3, la hauteur limite.
        assertEquals(559f, photo.height, 1f)
        assertEquals(photo.height * 4 / 3, photo.width, 0.01f)
        assertEquals(page.width - photo.right, photo.left, 0.01f)
        assertEquals(page.height - photo.bottom, photo.top, 0.01f)
        assertTrue(photo.top >= 18f)
    }

    @Test
    fun `photo en portrait, carrée, page à la française, ajustée sur ce qui manque`() {
        val portrait = page(3000, 4000)
        assertFalse(portrait.landscape)
        assertEquals(595, portrait.width)
        assertEquals(841, portrait.height)
        assertEquals(595f - 2 * 18, portrait.photo.width, 1f)
        val square = page(2000, 2000)
        assertFalse(square.landscape)
        assertEquals(square.photo.width, square.photo.height, 0.01f)
        assertEquals(595f - 2 * 18, square.photo.width, 1f)
        // Panorama très large : la largeur limite, la photo reste entière.
        val panorama = page(12000, 2000)
        assertTrue(panorama.landscape)
        assertEquals(841f - 2 * 18, panorama.photo.width, 1f)
        assertEquals(panorama.photo.width / 6, panorama.photo.height, 0.01f)
    }

    @Test
    fun `papier donné dans un sens ou dans l'autre, marges de tous côtés`() {
        val turned = PrintLayout.page(4000, 3000, a4Height, a4Width, 250, 300)
        assertEquals(page(4000, 3000), turned)
        val borderless = PrintLayout.page(4000, 3000, a4Width, a4Height, 0, 300)
        assertEquals(595f, borderless.photo.height, 1f)
    }

    @Test
    fun `orientation EXIF, les dimensions affichées décident du sens`() {
        // Capteur 4000 × 3000 tourné d'un quart de tour : photo en portrait.
        val item = MediaItem(
            id = 1, uri = Uri.EMPTY, type = MediaType.IMAGE, mimeType = "image/jpeg", displayName = "IMG.jpg",
            relativePath = "DCIM/Camera/", bucketId = 1, bucketName = "Camera", dateTaken = 0, dateModified = 0, size = 0,
            width = 4000, height = 3000, orientation = 90, durationMs = 0, isGif = false, isScreenshot = false,
        )
        val page = PrintLayout.page(item.displayWidth, item.displayHeight, a4Width, a4Height, 250, 300)
        assertFalse(page.landscape)
        assertTrue(page.photo.height > page.photo.width)
    }

    @Test
    fun `décodage à la résolution utile de l'imprimante`() {
        // 745 × 559 points à 300 ppp : 3106 × 2329 pixels, moins que la photo de 48 Mpx.
        val fine = page(8000, 6000)
        assertEquals(3106, fine.decodeWidth)
        assertEquals(2329, fine.decodeHeight)
        // Résolution de l'imprimante au-delà de 300 ppp : 300 suffisent.
        assertEquals(fine.decodeWidth, page(8000, 6000, dpi = 1200).decodeWidth)
        // Imprimante à 150 ppp : moitié moins.
        assertEquals(1553, page(8000, 6000, dpi = 150).decodeWidth)
        // Photo plus petite que la page : jamais agrandie au décodage.
        val small = page(1200, 900)
        assertEquals(1200, small.decodeWidth)
        assertEquals(900, small.decodeHeight)
        // Résolution inconnue : 300 ppp.
        assertEquals(fine.decodeWidth, page(8000, 6000, dpi = 0).decodeWidth)
        // Grand format : jamais plus de MAX_DECODE_PIXELS.
        val poster = PrintLayout.page(20000, 15000, 33110, 46810, 0, 300)
        assertTrue(poster.decodeWidth.toLong() * poster.decodeHeight <= PrintLayout.MAX_DECODE_PIXELS + 10_000)
    }

    @Test
    fun `une page par photo, vidéos ignorées, plages demandées`() {
        fun item(id: Long, video: Boolean) = MediaItem(
            id = id, uri = Uri.EMPTY, type = if (video) MediaType.VIDEO else MediaType.IMAGE, mimeType = if (video) "video/mp4" else "image/jpeg",
            displayName = "$id", relativePath = "", bucketId = 0, bucketName = "", dateTaken = 0, dateModified = 0, size = 0,
            width = 100, height = 100, orientation = 0, durationMs = 0, isGif = false, isScreenshot = false,
        )
        val selection = listOf(item(1, false), item(2, true), item(3, false), item(4, false))
        assertEquals(listOf(1L, 3L, 4L), PrintSelection.photos(selection).map { it.id })
        assertTrue(PrintSelection.photos(listOf(item(5, true))).isEmpty())
        assertEquals(3, PhotoPrintAdapter.documentInfo("Focale", 3).pageCount)
        // Toutes les pages (0 à Int.MAX_VALUE), puis une sélection.
        assertEquals(listOf(0, 1, 2), PrintPages.indices(listOf(0..Int.MAX_VALUE), 3))
        assertEquals(listOf(0, 2, 3, 4), PrintPages.indices(listOf(2..4, 0..0, 3..3), 6))
        assertEquals(listOf(0..0, 2..4), PrintPages.ranges(listOf(0, 2, 3, 4)))
        assertTrue(PrintPages.ranges(emptyList()).isEmpty())
    }
}
