package app.focale.data

import app.focale.core.database.DatedPoint
import app.focale.core.database.TripPlace
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {

    private val utc = ZoneOffset.UTC

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).toInstant(utc).toEpochMilli()

    private fun point(day: LocalDate, hour: Int, place: LatLng) = DatedPoint(at(day, hour), place.latitude, place.longitude)

    /** Trois médias par jour (10 h, 13 h, 16 h), de [from] à [to] inclus. */
    private fun stay(place: LatLng, from: LocalDate, to: LocalDate, hours: List<Int> = listOf(10, 13, 16)) =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .flatMap { day -> hours.map { point(day, it, place) } }
            .toList()

    /** Vie à la maison : une photo le 25 de chaque mois de [year]. */
    private fun homeLife(year: Int, place: LatLng = PARIS) = (1..12).map { point(LocalDate.of(year, it, 25), 12, place) }

    private fun day(month: Int, day: Int, year: Int = 2023) = LocalDate.of(year, month, day)

    @Test
    fun `chez soi est la zone photographiée le plus de mois, même face à un voyage plus fourni`() {
        val points = homeLife(2023) + stay(LISBON, day(6, 10), day(6, 20), hours = (8..20).toList())
        val home = TripDetector.homes(points, utc).getValue(2023)
        assertTrue(TripDetector.distanceKm(PARIS.latitude, PARIS.longitude, home) < 1.0)
        assertEquals(listOf(day(6, 10) to day(6, 20)), TripDetector.detect(points, utc).map { it.firstDay to it.lastDay })
    }

    @Test
    fun `un voyage va du premier au dernier média pris loin de chez soi`() {
        val departure = day(6, 10)
        val back = day(6, 15)
        val points = homeLife(2023) +
            point(departure, 8, PARIS) + point(departure, 15, LISBON) + point(departure, 18, LISBON) +
            stay(LISBON, day(6, 11), day(6, 14)) +
            point(back, 9, LISBON) + point(back, 21, PARIS)
        val trip = TripDetector.detect(points.shuffled(Random(7)), utc).single()
        assertEquals(at(departure, 15), trip.start)
        assertEquals(at(back, 9), trip.end)
        assertEquals(departure, trip.firstDay)
        assertEquals(back, trip.lastDay)
        assertEquals(2 + 12 + 1, trip.photos)
    }

    @Test
    fun `un jour sans média est toléré, pas deux`() {
        val points = homeLife(2023) +
            stay(LISBON, day(6, 10), day(6, 11)) + stay(LISBON, day(6, 13), day(6, 13)) +
            stay(LISBON, day(7, 1), day(7, 2)) + stay(LISBON, day(7, 5), day(7, 6))
        val trips = TripDetector.detect(points, utc).map { it.firstDay to it.lastDay }
        assertEquals(listOf(day(6, 10) to day(6, 13), day(7, 1) to day(7, 2), day(7, 5) to day(7, 6)), trips)
    }

    @Test
    fun `une journée passée chez soi sépare deux voyages`() {
        val points = homeLife(2023) + stay(LYON, day(8, 1), day(8, 2)) + point(day(8, 3), 12, PARIS) + stay(LYON, day(8, 4), day(8, 5))
        val trips = TripDetector.detect(points, utc).map { it.firstDay to it.lastDay }
        assertEquals(listOf(day(8, 1) to day(8, 2), day(8, 4) to day(8, 5)), trips)
    }

    @Test
    fun `à moins de cent kilomètres de chez soi, ce n'est pas un voyage`() {
        // Un degré de latitude vaut 111 km.
        val near = LatLng(PARIS.latitude + 0.8, PARIS.longitude)
        val far = LatLng(PARIS.latitude + 1.0, PARIS.longitude)
        assertTrue(TripDetector.detect(homeLife(2023) + stay(near, day(6, 10), day(6, 12)), utc).isEmpty())
        assertEquals(1, TripDetector.detect(homeLife(2023) + stay(far, day(6, 10), day(6, 12)), utc).size)
    }

    @Test
    fun `il faut au moins cinq médias loin de chez soi`() {
        val four = stay(LISBON, day(6, 10), day(6, 11), hours = listOf(10, 16))
        assertTrue(TripDetector.detect(homeLife(2023) + four, utc).isEmpty())
        val five = four + point(day(6, 11), 19, LISBON)
        assertEquals(5, TripDetector.detect(homeLife(2023) + five, utc).single().photos)
    }

    @Test
    fun `après un déménagement, l'ancienne ville devient un voyage`() {
        val points = homeLife(2021, LYON) + stay(LYON, day(7, 10, 2021), day(7, 12, 2021)) +
            homeLife(2022, PARIS) + stay(LYON, day(7, 10, 2022), day(7, 12, 2022))
        val homes = TripDetector.homes(points, utc)
        assertTrue(TripDetector.distanceKm(LYON.latitude, LYON.longitude, homes.getValue(2021)) < 1.0)
        assertTrue(TripDetector.distanceKm(PARIS.latitude, PARIS.longitude, homes.getValue(2022)) < 1.0)
        assertEquals(listOf(day(7, 10, 2022)), TripDetector.detect(points, utc).map { it.firstDay })
    }

    @Test
    fun `une année sans chez-soi reprend celui de l'année la plus proche`() {
        // 2020 : seulement un voyage ; la maison de 2021 sert de référence.
        val points = stay(LISBON, day(8, 1, 2020), day(8, 5, 2020)) + homeLife(2021) + homeLife(2022, LYON)
        val trip = TripDetector.detect(points, utc).single()
        assertEquals(day(8, 1, 2020), trip.firstDay)
        assertTrue(TripDetector.distanceKm(PARIS.latitude, PARIS.longitude, trip.home) < 1.0)
    }

    @Test
    fun `sans année complète, la zone la plus fréquente de toute la bibliothèque`() {
        val points = listOf(point(day(1, 25), 12, PARIS), point(day(2, 25), 12, PARIS)) + stay(LISBON, day(6, 10), day(6, 12))
        assertEquals(listOf(day(6, 10)), TripDetector.detect(points, utc).map { it.firstDay })
        assertTrue(TripDetector.detect(emptyList(), utc).isEmpty())
    }

    @Test
    fun `la ville qui domine le voyage le titre, sinon son pays`() {
        fun places(name: String?, country: String?, place: LatLng, count: Int) =
            List(count) { TripPlace(place.latitude, place.longitude, name, country) }
        // Les photos prises à la maison pendant le séjour ne comptent pas.
        val lisbon = places("Lisbon", "PT", LISBON, 6) + places("Sintra", "PT", SINTRA, 2) + places("Paris", "FR", PARIS, 10)
        assertEquals(TripLocation("Lisbon", "PT"), TripDetector.locate(lisbon, PARIS))
        val tour = places("Rome", "IT", ROME, 3) + places("Florence", "IT", FLORENCE, 3) + places("Venice", "IT", VENICE, 2)
        assertEquals(TripLocation(null, "IT"), TripDetector.locate(tour, PARIS))
        val located = TripDetector.locate(places(null, null, LISBON, 4), PARIS)
        assertNull(located.place)
        assertNull(located.country)
    }

    private companion object {
        val PARIS = LatLng(48.8566, 2.3522)
        val LYON = LatLng(45.7640, 4.8357)
        val LISBON = LatLng(38.7223, -9.1393)
        val SINTRA = LatLng(38.8029, -9.3817)
        val ROME = LatLng(41.9028, 12.4964)
        val FLORENCE = LatLng(43.7696, 11.2558)
        val VENICE = LatLng(45.4408, 12.3155)
    }
}
