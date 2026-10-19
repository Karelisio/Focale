package app.focale.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @Test
    fun `durées de vidéo`() {
        assertEquals("0:00", Formatters.duration(0))
        assertEquals("0:42", Formatters.duration(42_000))
        assertEquals("12:05", Formatters.duration(725_000))
        assertEquals("1:02:33", Formatters.duration(3_753_000))
    }

    @Test
    fun `résolution et mégapixels`() {
        assertEquals("—", Formatters.resolution(0, 100))
        assertEquals("640 × 480", Formatters.resolution(640, 480))
        assertTrue(Formatters.resolution(4000, 3000).startsWith("4000 × 3000 · 12"))
    }
}
