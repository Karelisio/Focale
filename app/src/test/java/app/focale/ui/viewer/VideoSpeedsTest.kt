package app.focale.ui.viewer

import app.focale.ui.viewer.VideoSpeeds.format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VideoSpeedsTest {

    @Test
    fun `huit vitesses de 0,25× à 2× par quarts, la normale comprise`() {
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f), VideoSpeeds.All)
        assertTrue(VideoSpeeds.NORMAL in VideoSpeeds.All)
        assertEquals(1f, VideoSpeeds.NORMAL)
    }

    @Test
    fun `en français, virgule décimale et pas de zéro inutile`() {
        assertEquals(
            listOf("0,25", "0,5", "0,75", "1", "1,25", "1,5", "1,75", "2"),
            VideoSpeeds.All.map { format(it, Locale.FRENCH) },
        )
    }

    @Test
    fun `en anglais, point décimal`() {
        assertEquals(
            listOf("0.25", "0.5", "0.75", "1", "1.25", "1.5", "1.75", "2"),
            VideoSpeeds.All.map { format(it, Locale.ENGLISH) },
        )
    }

    @Test
    fun `d'autres langues gardent leur séparateur`() {
        assertEquals("1,5", format(1.5f, Locale.GERMAN))
        assertEquals("1.75", format(1.75f, Locale.US))
    }
}
