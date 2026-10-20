package app.focale.data

import app.focale.core.database.DatedPoint
import app.focale.core.database.TripPlace
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Coordonnées en degrés. */
data class LatLng(val latitude: Double, val longitude: Double)

/** Séjour loin de chez soi, du premier au dernier média pris à plus de [TripDetector.MIN_DISTANCE_KM] de [home]. */
data class Trip(
    /** Dates (ms) du premier et du dernier média pris loin de chez soi. */
    val start: Long,
    val end: Long,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    /** Médias géolocalisés pris loin de chez soi. */
    val photos: Int,
    /** Chez-soi de l'année du départ. */
    val home: LatLng,
)

/** Lieu d'un voyage : ville dominante s'il y en a une, pays le plus fréquent. */
data class TripLocation(val place: String?, val country: String?)

/**
 * Détection des voyages, en logique pure sur des points datés (date, latitude, longitude).
 *
 * Chez soi : la cellule d'une grille d'environ [HOME_CELL_KM] km où l'on a photographié
 * pendant le plus de mois distincts (puis de jours) — un séjour, même long et très
 * photographié, tient en un ou deux mois, la maison revient toute l'année. Il est calculé
 * pour chaque année (un déménagement change de chez-soi) ; une année dont la meilleure
 * cellule couvre moins de [MIN_HOME_MONTHS] mois reprend celui de l'année la plus proche,
 * à défaut celui de toute la bibliothèque. La grille rend le choix indépendant du
 * géocodage (une ville se répartit entre plusieurs localités voisines) ; sa taille, petite
 * devant [MIN_DISTANCE_KM], rend sans effet le découpage d'une ville entre cellules.
 *
 * Voyage : jours consécutifs avec au moins un média à plus de [MIN_DISTANCE_KM] de chez soi,
 * [MAX_GAP_DAYS] jour sans média géolocalisé toléré entre deux, interrompus par une journée
 * passée chez soi ; au moins [MIN_PHOTOS] médias loin de chez soi.
 */
object TripDetector {

    const val HOME_CELL_KM = 25.0
    const val MIN_DISTANCE_KM = 100.0
    const val MAX_GAP_DAYS = 1
    const val MIN_PHOTOS = 5
    const val MIN_HOME_MONTHS = 3

    /** Part des médias situés que doit rassembler une ville pour titrer le voyage. */
    const val PLACE_SHARE = 0.5

    private const val EARTH_RADIUS_KM = 6_371.0
    private const val KM_PER_DEGREE = 111.195
    private const val CELL_DEGREES = HOME_CELL_KM / KM_PER_DEGREE
    private const val DAY_MS = 86_400_000L

    /** Voyages de [points], par date de départ ; les jours sont ceux du fuseau [zone]. */
    fun detect(points: List<DatedPoint>, zone: ZoneId): List<Trip> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.date }
        val dates = localDates(sorted, zone)
        val homes = homes(sorted, dates)

        val byDay = TreeMap<Long, Day>()
        sorted.forEachIndexed { i, point ->
            val home = homes.getValue(dates[i].year)
            // Chaque jour géolocalisé a son entrée : un jour sans média lointain interrompt un voyage.
            val day = byDay.getOrPut(dates[i].toEpochDay()) { Day(home) }
            if (distanceKm(point.latitude, point.longitude, home) > MIN_DISTANCE_KM) day.addFar(point.date)
        }

        val trips = mutableListOf<Trip>()
        var run: Run? = null
        for ((epochDay, day) in byDay) {
            val current = run
            if (current != null && day.far > 0 && epochDay - current.lastDay <= MAX_GAP_DAYS + 1) {
                current.add(epochDay, day)
            } else {
                // Journée passée chez soi ou absence trop longue : le voyage en cours s'arrête.
                current?.closeInto(trips)
                run = if (day.far > 0) Run(epochDay, day) else null
            }
        }
        run?.closeInto(trips)
        return trips
    }

    /** Chez-soi de chaque année présente dans [points] (voir [TripDetector]). */
    fun homes(points: List<DatedPoint>, zone: ZoneId): Map<Int, LatLng> {
        val sorted = points.sortedBy { it.date }
        return homes(sorted, localDates(sorted, zone))
    }

    /**
     * Lieu d'un voyage parmi ses médias situés ([places]) : seuls comptent ceux pris loin de
     * [home]. La ville la plus fréquente le titre si elle en rassemble au moins
     * [PLACE_SHARE] (sinon c'est un circuit : le pays le plus fréquent le résume mieux).
     */
    fun locate(places: List<TripPlace>, home: LatLng): TripLocation {
        val away = places.filter { distanceKm(it.latitude, it.longitude, home) > MIN_DISTANCE_KM }
        val cities = away.mapNotNull { it.place }
        val city = cities.mostFrequent()?.takeIf { (_, count) -> count >= cities.size * PLACE_SHARE }?.first
        val country = away.mapNotNull { it.country }.mostFrequent()?.first
        return TripLocation(city, country)
    }

    /** Distance orthodromique (formule de haversine), en kilomètres. */
    fun distanceKm(latitude: Double, longitude: Double, other: LatLng): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(sqrt(min(1.0, h)))
    }

    /** [points] triés par date ; [dates] : jour local de chacun. */
    private fun homes(points: List<DatedPoint>, dates: List<LocalDate>): Map<Int, LatLng> {
        val perYear = HashMap<Int, HashMap<Long, Cell>>()
        points.forEachIndexed { i, point ->
            perYear.getOrPut(dates[i].year) { HashMap() }
                .getOrPut(cellOf(point.latitude, point.longitude)) { Cell() }
                .add(point, dates[i])
        }
        val yearHomes = TreeMap<Int, LatLng>()
        perYear.forEach { (year, cells) ->
            val best = cells.best()
            if (best.monthCount >= MIN_HOME_MONTHS) yearHomes[year] = best.center
        }
        val overall: LatLng by lazy {
            val merged = HashMap<Long, Cell>()
            perYear.values.forEach { cells -> cells.forEach { (key, cell) -> merged.getOrPut(key) { Cell() }.merge(cell) } }
            merged.best().center
        }
        return perYear.keys.associateWith { year -> yearHomes[year] ?: yearHomes.nearest(year) ?: overall }
    }

    /** Jour local de chaque point ; un même objet pour les points d'un même jour. */
    private fun localDates(points: List<DatedPoint>, zone: ZoneId): List<LocalDate> {
        val rules = zone.rules
        var previous: LocalDate? = null
        return points.map { point ->
            val offset = rules.getOffset(Instant.ofEpochMilli(point.date)).totalSeconds * 1000L
            val epochDay = Math.floorDiv(point.date + offset, DAY_MS)
            previous?.takeIf { it.toEpochDay() == epochDay } ?: LocalDate.ofEpochDay(epochDay).also { previous = it }
        }
    }

    /** Cellule de la grille : bandes de latitude, largeur en longitude corrigée de la latitude. */
    private fun cellOf(latitude: Double, longitude: Double): Long {
        val row = floor((latitude + 90.0) / CELL_DEGREES).toLong()
        val rowLatitude = -90.0 + (row + 0.5) * CELL_DEGREES
        val width = CELL_DEGREES / cos(Math.toRadians(rowLatitude)).coerceAtLeast(0.01)
        val column = floor((longitude + 180.0) / width).toLong()
        return (row shl 32) or column
    }

    /** Plus fréquent et nombre d'occurrences ; à égalité, le premier rencontré. */
    private fun <T> List<T>.mostFrequent(): Pair<T, Int>? =
        groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair()

    private fun TreeMap<Int, LatLng>.nearest(year: Int): LatLng? {
        val before = floorEntry(year)
        val after = ceilingEntry(year)
        return when {
            before == null -> after?.value
            after == null -> before.value
            after.key - year < year - before.key -> after.value
            else -> before.value
        }
    }

    /** Meilleure cellule : mois distincts, puis jours distincts, puis médias ; clé en dernier recours. */
    private fun Map<Long, Cell>.best(): Cell = entries.maxWith(
        compareBy<Map.Entry<Long, Cell>>({ it.value.monthCount }, { it.value.days }, { it.value.count })
            .thenByDescending { it.key },
    ).value

    private class Cell {
        /** Mois de l'année (masque de 12 bits) ; [mergedMonths] cumule ceux des années fusionnées. */
        var months = 0
        var mergedMonths = 0
        var days = 0
        var lastDay: LocalDate? = null
        var count = 0
        var latitudeSum = 0.0
        var longitudeSum = 0.0

        val monthCount: Int get() = mergedMonths + Integer.bitCount(months)
        val center: LatLng get() = LatLng(latitudeSum / count, longitudeSum / count)

        fun add(point: DatedPoint, date: LocalDate) {
            months = months or (1 shl (date.monthValue - 1))
            // Points triés par date : un jour nouveau se reconnaît au précédent.
            if (date != lastDay) {
                days++
                lastDay = date
            }
            count++
            latitudeSum += point.latitude
            longitudeSum += point.longitude
        }

        fun merge(other: Cell) {
            mergedMonths += other.monthCount
            days += other.days
            count += other.count
            latitudeSum += other.latitudeSum
            longitudeSum += other.longitudeSum
        }
    }

    /** Médias d'un jour local, mesurés depuis le chez-soi de son année. */
    private class Day(val home: LatLng) {
        var far = 0
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE

        fun addFar(date: Long) {
            far++
            if (date < first) first = date
            if (date > last) last = date
        }
    }

    private class Run(val firstDay: Long, day: Day) {
        val home = day.home
        var lastDay = firstDay
        var photos = day.far
        var start = day.first
        var end = day.last

        fun add(epochDay: Long, day: Day) {
            lastDay = epochDay
            photos += day.far
            if (day.first < start) start = day.first
            if (day.last > end) end = day.last
        }

        fun closeInto(trips: MutableList<Trip>) {
            if (photos >= MIN_PHOTOS) {
                trips += Trip(start, end, LocalDate.ofEpochDay(firstDay), LocalDate.ofEpochDay(lastDay), photos, home)
            }
        }
    }
}
