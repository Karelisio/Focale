package app.focale.data

import app.focale.widget.WidgetPhotoPicker
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWindowsTest {

    private val utc: ZoneId = ZoneOffset.UTC

    @Test
    fun `une fenêtre de sept jours par année passée`() {
        val windows = MemoryWindows.of(LocalDate.of(2026, 10, 19), oldestYear = 2022, zone = utc)
        assertEquals(listOf(1, 2, 3, 4), windows.map { it.yearsAgo })
        val lastYear = windows.first()
        assertEquals(LocalDate.of(2025, 10, 16).atStartOfDay(utc).toInstant().toEpochMilli(), lastYear.from)
        assertEquals(LocalDate.of(2025, 10, 23).atStartOfDay(utc).toInstant().toEpochMilli(), lastYear.to)
    }

    @Test
    fun `29 février centré sur le 28 les années non bissextiles`() {
        val window = MemoryWindows.of(LocalDate.of(2028, 2, 29), oldestYear = 2027, zone = utc).single()
        assertEquals(LocalDate.of(2027, 2, 25).atStartOfDay(utc).toInstant().toEpochMilli(), window.from)
        assertEquals(LocalDate.of(2027, 3, 4).atStartOfDay(utc).toInstant().toEpochMilli(), window.to)
    }

    @Test
    fun `aucune fenêtre pour une bibliothèque de l'année et nombre d'années borné`() {
        assertTrue(MemoryWindows.of(LocalDate.of(2026, 1, 1), oldestYear = 2026, zone = utc).isEmpty())
        assertEquals(10, MemoryWindows.of(LocalDate.of(2026, 1, 1), oldestYear = 1970, zone = utc, maxYears = 10).size)
    }

    @Test
    fun `le choix du widget est stable sur une période et reste dans la liste`() {
        repeat(500) { slot ->
            val index = WidgetPhotoPicker.spread(slot.toLong(), 7)
            assertTrue(index in 0 until 7)
            assertEquals(index, WidgetPhotoPicker.spread(slot.toLong(), 7))
        }
        // Les périodes successives ne parcourent pas la liste dans l'ordre.
        val first = (0L until 6L).map { WidgetPhotoPicker.spread(it, 50) }
        assertTrue(first != first.sorted())
    }
}
