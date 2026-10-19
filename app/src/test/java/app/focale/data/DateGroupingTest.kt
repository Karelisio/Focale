package app.focale.data

import app.focale.core.model.GroupLevel
import app.focale.core.model.GroupMode
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DateGroupingTest {

    private val zone = ZoneId.of("Europe/Paris")

    private fun ms(year: Int, month: Int, day: Int, hour: Int = 12) =
        LocalDateTime.of(year, month, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `le mode automatique suit le nombre de colonnes`() {
        assertEquals(GroupLevel.DAY, DateGrouping.resolve(GroupMode.AUTO, 2))
        assertEquals(GroupLevel.DAY, DateGrouping.resolve(GroupMode.AUTO, 3))
        assertEquals(GroupLevel.MONTH, DateGrouping.resolve(GroupMode.AUTO, 4))
        assertEquals(GroupLevel.MONTH, DateGrouping.resolve(GroupMode.AUTO, 5))
        assertEquals(GroupLevel.YEAR, DateGrouping.resolve(GroupMode.AUTO, 7))
        assertEquals(GroupLevel.MONTH, DateGrouping.resolve(GroupMode.MONTH, 2))
        assertNull(DateGrouping.resolve(GroupMode.NONE, 4))
    }

    @Test
    fun `deux photos du même jour partagent la même clé`() {
        val morning = ms(2023, 7, 14, hour = 1)
        val evening = ms(2023, 7, 14, hour = 23)
        assertEquals(
            DateGrouping.periodKey(morning, GroupLevel.DAY, zone),
            DateGrouping.periodKey(evening, GroupLevel.DAY, zone),
        )
        assertNotEquals(
            DateGrouping.periodKey(evening, GroupLevel.DAY, zone),
            DateGrouping.periodKey(ms(2023, 7, 15, hour = 0), GroupLevel.DAY, zone),
        )
    }

    @Test
    fun `le début de période est minuit local du premier jour`() {
        val start = DateGrouping.periodStart(ms(2023, 7, 14), GroupLevel.MONTH, zone)
        assertEquals(ms(2023, 7, 1, hour = 0), start)
        val year = DateGrouping.periodStart(ms(2023, 7, 14), GroupLevel.YEAR, zone)
        assertEquals(ms(2023, 1, 1, hour = 0), year)
    }

    @Test
    fun `les mois de deux années différentes sont distincts`() {
        assertNotEquals(
            DateGrouping.periodKey(ms(2022, 7, 1), GroupLevel.MONTH, zone),
            DateGrouping.periodKey(ms(2023, 7, 1), GroupLevel.MONTH, zone),
        )
    }
}
