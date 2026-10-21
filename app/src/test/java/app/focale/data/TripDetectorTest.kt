package app.focale.data

import app.focale.core.database.DatedPoint
import app.focale.core.database.TripPlace
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {

    private val utc = ZoneOffset.UTC

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).toInstant(utc).toEpochMilli()

    private fun point(day: LocalDate, hour: Int, place: LatLng) = DatedPoint(at(day, hour), place.latitude, place.longitude)

    private fun days(from: LocalDate, to: LocalDate) = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }

    /** Trois médias par jour (10 h, 13 h, 16 h), de [from] à [to] inclus. */
    private fun stay(place: LatLng, from: LocalDate, to: LocalDate, hours: List<Int> = listOf(10, 13, 16)) =
        days(from, to).flatMap { day -> hours.map { point(day, it, place) } }.toList()

    /** Vie à la maison : une photo le 25 de chaque mois de [year]. */
    private fun homeLife(year: Int, place: LatLng = PARIS) = (1..12).map { point(LocalDate.of(year, it, 25), 12, place) }

    /** Une photo à [place] chaque [dayOfWeek] de [from] à [to] : la vie de tous les jours. */
    private fun weekly(place: LatLng, from: LocalDate, to: LocalDate, dayOfWeek: DayOfWeek = DayOfWeek.WEDNESDAY) =
        days(from, to).filter { it.dayOfWeek == dayOfWeek }.map { point(it, 18, place) }.toList()

    private fun day(month: Int, day: Int, year: Int = 2023) = LocalDate.of(year, month, day)

    private fun spans(points: List<DatedPoint>) = TripDetector.detect(points, utc).map { it.firstDay to it.lastDay }

    @Test
    fun `chez soi est la zone photographiée le plus de mois, même face à un voyage plus fourni`() {
        val points = homeLife(2023) + stay(LISBON, day(6, 10), day(6, 20), hours = (8..20).toList())
        val places = TripDetector.familiarPlaces(points, utc).getValue(YearMonth.of(2023, 6))
        assertTrue(TripDetector.distanceKm(PARIS.latitude, PARIS.longitude, places.single()) < 1.0)
        assertEquals(listOf(day(6, 10) to day(6, 20)), spans(points))
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
    fun `deux jours sans média sont tolérés, pas trois`() {
        val points = homeLife(2023) +
            stay(LISBON, day(6, 10), day(6, 11)) + stay(LISBON, day(6, 14), day(6, 14)) +
            stay(LISBON, day(7, 1), day(7, 2)) + stay(LISBON, day(7, 6), day(7, 7))
        assertEquals(listOf(day(6, 10) to day(6, 14), day(7, 1) to day(7, 2), day(7, 6) to day(7, 7)), spans(points))
    }

    @Test
    fun `une journée passée chez soi sépare deux voyages`() {
        val points = homeLife(2023) + stay(LYON, day(8, 1), day(8, 2)) + point(day(8, 3), 12, PARIS) + stay(LYON, day(8, 4), day(8, 5))
        assertEquals(listOf(day(8, 1) to day(8, 2), day(8, 4) to day(8, 5)), spans(points))
    }

    @Test
    fun `à moins de soixante kilomètres de chez soi, ce n'est pas un voyage`() {
        assertTrue(spans(homeLife(2023) + stay(PARIS.east(55.0), day(6, 10), day(6, 12))).isEmpty())
        assertEquals(1, spans(homeLife(2023) + stay(PARIS.east(65.0), day(6, 10), day(6, 12))).size)
    }

    @Test
    fun `un week-end à 80 km est un voyage`() {
        val saturday = day(9, 16)
        val points = homeLife(2023) + weekly(PARIS, day(1, 1), day(12, 31)) + stay(PARIS.east(80.0), saturday, saturday.plusDays(1))
        assertEquals(listOf(saturday to saturday.plusDays(1)), spans(points))
    }

    @Test
    fun `la grande banlieue n'est pas un voyage`() {
        // Domicile à Paris, travail à 45 km photographié toute l'année : deux lieux familiers.
        val work = PARIS.east(45.0)
        val points = homeLife(2023) + weekly(work, day(1, 1), day(12, 31), DayOfWeek.TUESDAY) +
            // Week-end chez des amis à 55 km au sud, sorties à 50 km.
            stay(PARIS.north(-55.0), day(5, 13), day(5, 14)) +
            stay(PARIS.north(50.0), day(7, 8), day(7, 8), hours = (9..18).toList()) +
            // Au-delà du lieu de travail, mais à moins de 60 km de lui.
            stay(work.east(50.0), day(10, 7), day(10, 8))
        assertTrue(spans(points).isEmpty())
        val places = TripDetector.familiarPlaces(points, utc).getValue(YearMonth.of(2023, 10))
        assertEquals(2, places.size)
    }

    @Test
    fun `un aller-retour dans la journée n'est pas un voyage, sauf très loin`() {
        val outing = stay(PARIS.east(120.0), day(4, 15), day(4, 15), hours = (9..19).toList())
        assertTrue(spans(homeLife(2023) + outing).isEmpty())
        // Une journée à 300 km (au Mont-Saint-Michel, en train à Lyon…) en est un.
        val far = stay(PARIS.east(300.0), day(4, 15), day(4, 15), hours = (9..19).toList())
        assertEquals(listOf(day(4, 15) to day(4, 15)), spans(homeLife(2023) + far))
        // Quatre médias ne suffisent pas, même pour plusieurs jours.
        assertTrue(spans(homeLife(2023) + stay(LISBON, day(4, 15), day(4, 16), hours = listOf(10, 16))).isEmpty())
    }

    @Test
    fun `un voyage de trois semaines en étapes reste un seul voyage`() {
        val stages = stay(LYON, day(7, 1), day(7, 3)) +
            stay(MARSEILLE, day(7, 4), day(7, 7)) +
            // Journée de route sans photo, puis Nice ; deux jours de repos sans photo, puis l'Italie.
            stay(NICE, day(7, 9), day(7, 11)) +
            stay(GENOA, day(7, 14), day(7, 16)) +
            stay(FLORENCE, day(7, 17), day(7, 21))
        val points = homeLife(2023) + weekly(PARIS, day(1, 1), day(6, 30)) + weekly(PARIS, day(7, 23), day(12, 31)) + stages
        val trip = TripDetector.detect(points, utc).single()
        assertEquals(day(7, 1) to day(7, 21), trip.firstDay to trip.lastDay)
        assertEquals(stages.size, trip.photos)
    }

    @Test
    fun `de longues vacances au même endroit restent un voyage`() {
        // Six semaines à la mer : deux mois, sans l'étalement d'un lieu familier.
        val points = weekly(PARIS, day(1, 1), day(7, 9)) + weekly(PARIS, day(8, 21), day(6, 30, 2024)) +
            stay(BIARRITZ, day(7, 10), day(8, 20), hours = listOf(11, 17))
        assertEquals(listOf(day(7, 10) to day(8, 20)), spans(points))
    }

    @Test
    fun `une résidence secondaire n'est pas un voyage, des vacances deux fois par an au même endroit si`() {
        // Un week-end par mois à la campagne, à 150 km.
        val countryHouse = PARIS.north(-150.0)
        val weekends = (1..12).flatMap { month ->
            val saturday = LocalDate.of(2023, month, 1).let { first -> generateSequence(first) { it.plusDays(1) }.first { it.dayOfWeek == DayOfWeek.SATURDAY } }
            stay(countryHouse, saturday, saturday.plusDays(1))
        }
        assertTrue(spans(homeLife(2023) + weekends).isEmpty())
        // Pâques et l'été au même endroit : trois mois, deux voyages.
        val holidays = stay(BIARRITZ, day(4, 8), day(4, 15)) + stay(BIARRITZ, day(7, 25), day(8, 12))
        assertEquals(listOf(day(4, 8) to day(4, 15), day(7, 25) to day(8, 12)), spans(homeLife(2023) + holidays))
    }

    @Test
    fun `un déménagement en cours d'année ne fait pas de la nouvelle ville un voyage`() {
        // Paris jusqu'à fin octobre, puis Bordeaux (500 km) : un séjour à Lisbonne en décembre.
        val points = weekly(PARIS, day(1, 1), day(10, 25)) +
            weekly(BORDEAUX, day(10, 28), day(6, 30, 2024)) + stay(BORDEAUX, day(11, 4), day(11, 5)) +
            stay(LISBON, day(12, 9), day(12, 12))
        assertEquals(listOf(day(12, 9) to day(12, 12)), spans(points))
        val places = TripDetector.familiarPlaces(points, utc)
        assertTrue(TripDetector.distanceKm(PARIS.latitude, PARIS.longitude, places.getValue(YearMonth.of(2023, 6)).first()) < 1.0)
        assertTrue(TripDetector.distanceKm(BORDEAUX.latitude, BORDEAUX.longitude, places.getValue(YearMonth.of(2024, 3)).first()) < 1.0)
    }

    @Test
    fun `après un déménagement, l'ancienne ville devient un voyage`() {
        val points = homeLife(2021, LYON) + stay(LYON, day(7, 10, 2021), day(7, 12, 2021)) +
            homeLife(2022, PARIS) + stay(LYON, day(7, 10, 2022), day(7, 12, 2022))
        val places = TripDetector.familiarPlaces(points, utc)
        assertTrue(TripDetector.distanceKm(LYON.latitude, LYON.longitude, places.getValue(YearMonth.of(2021, 7)).single()) < 1.0)
        assertTrue(TripDetector.distanceKm(PARIS.latitude, PARIS.longitude, places.getValue(YearMonth.of(2022, 7)).single()) < 1.0)
        assertEquals(listOf(day(7, 10, 2022)), TripDetector.detect(points, utc).map { it.firstDay })
    }

    @Test
    fun `un frontalier n'est pas en voyage de l'autre côté de la frontière`() {
        // Domicile à Thionville, travail à Luxembourg (30 km) : deux lieux familiers, deux pays.
        val points = homeLife(2023, THIONVILLE) + weekly(LUXEMBOURG, day(1, 1), day(12, 31), DayOfWeek.MONDAY) +
            stay(LUXEMBOURG, day(3, 10), day(3, 12)) +
            // Strasbourg (145 km) le temps d'un week-end : un voyage.
            stay(STRASBOURG, day(5, 20), day(5, 21))
        assertEquals(listOf(day(5, 20) to day(5, 21)), spans(points))
    }

    @Test
    fun `un mois sans lieu familier reprend ceux du mois le plus proche`() {
        // 2020 : seulement un voyage ; la maison de 2021 sert de référence.
        val points = stay(LISBON, day(8, 1, 2020), day(8, 5, 2020)) + homeLife(2021) + homeLife(2022, LYON)
        val trip = TripDetector.detect(points, utc).single()
        assertEquals(day(8, 1, 2020), trip.firstDay)
        assertTrue(TripDetector.distanceKm(PARIS.latitude, PARIS.longitude, trip.home) < 1.0)
    }

    @Test
    fun `sans lieu familier, la zone photographiée le plus de mois`() {
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
        val MARSEILLE = LatLng(43.2965, 5.3698)
        val NICE = LatLng(43.7102, 7.2620)
        val GENOA = LatLng(44.4056, 8.9463)
        val BORDEAUX = LatLng(44.8378, -0.5792)
        val BIARRITZ = LatLng(43.4832, -1.5586)
        val STRASBOURG = LatLng(48.5734, 7.7521)
        val THIONVILLE = LatLng(49.3579, 6.1681)
        val LUXEMBOURG = LatLng(49.6116, 6.1319)
        val LISBON = LatLng(38.7223, -9.1393)
        val SINTRA = LatLng(38.8029, -9.3817)
        val ROME = LatLng(41.9028, 12.4964)
        val FLORENCE = LatLng(43.7696, 11.2558)
        val VENICE = LatLng(45.4408, 12.3155)

        private const val KM_PER_DEGREE = 111.195

        /** Point à [km] à l'est (vers l'ouest si négatif). */
        fun LatLng.east(km: Double) = LatLng(latitude, longitude + km / (KM_PER_DEGREE * cos(Math.toRadians(latitude))))

        /** Point à [km] au nord (vers le sud si négatif). */
        fun LatLng.north(km: Double) = LatLng(latitude + km / KM_PER_DEGREE, longitude)
    }
}
