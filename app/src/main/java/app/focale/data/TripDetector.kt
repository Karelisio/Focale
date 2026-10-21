package app.focale.data

import app.focale.core.database.DatedPoint
import app.focale.core.database.TripPlace
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.TreeMap
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Coordonnées en degrés. */
data class LatLng(val latitude: Double, val longitude: Double)

/** Séjour loin de chez soi, du premier au dernier média pris à plus de [TripDetector.MIN_DISTANCE_KM] des lieux familiers. */
data class Trip(
    /** Dates (ms) du premier et du dernier média pris loin de chez soi. */
    val start: Long,
    val end: Long,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    /** Médias géolocalisés pris loin de chez soi. */
    val photos: Int,
    /** Chez-soi du mois du départ. */
    val home: LatLng,
)

/** Lieu d'un voyage : ville dominante s'il y en a une, pays le plus fréquent. */
data class TripLocation(val place: String?, val country: String?)

/**
 * Détection des voyages, en logique pure sur des points datés (date, latitude, longitude).
 * Seuils mesurés sur les photos géolocalisées de YFCC100M (`tools/trips/`).
 *
 * Lieux familiers : les cellules d'une grille d'environ [HOME_CELL_KM] km photographiées pendant
 * au moins [FAMILIAR_MONTHS] mois distincts, étalés sur au moins [FAMILIAR_SPAN_DAYS] jours, dans
 * les douze mois autour de celui du média (six avant, cinq après) : le domicile, une résidence
 * secondaire, le lieu de travail d'un frontalier, la nouvelle adresse dès un déménagement. Un
 * séjour, même long, tient en deux ou trois mois ; des vacances au même endroit à Pâques et en
 * été aussi. Chez soi : le lieu familier photographié pendant le plus de mois. Un mois sans lieu
 * familier reprend ceux du mois le plus proche qui en a, à défaut le lieu photographié pendant le
 * plus de mois. La grille rend le choix indépendant du géocodage (une ville se répartit entre
 * plusieurs localités voisines) ; sa taille, petite devant [MIN_DISTANCE_KM], rend sans effet le
 * découpage d'une ville entre cellules.
 *
 * Voyage : jours consécutifs avec au moins un média à plus de [MIN_DISTANCE_KM] de tout lieu
 * familier, [MAX_GAP_DAYS] jours sans média géolocalisé tolérés entre deux, interrompus par une
 * journée passée près d'un lieu familier ; au moins [MIN_PHOTOS] médias loin de chez soi, et au
 * moins une nuit sur place, sauf au-delà de [MIN_DAY_TRIP_KM] : un aller-retour dans la journée
 * est une sortie, pas un voyage.
 */
object TripDetector {

    const val HOME_CELL_KM = 25.0

    /**
     * Distance minimale d'un voyage d'au moins une nuit. Un week-end à 80 km en est un ; une
     * grande agglomération (photos à 30 ou 50 km du centre) n'en est pas, d'autant que ses lieux
     * photographiés toute l'année sont familiers.
     */
    const val MIN_DISTANCE_KM = 60.0

    /** Distance minimale d'une seule journée loin de chez soi : deux heures de route au moins. */
    const val MIN_DAY_TRIP_KM = 150.0

    /**
     * Jours sans média géolocalisé tolérés dans un voyage : deux, pour qu'une journée de route et
     * un jour de repos sans photo ne coupent pas un voyage en étapes (le second morceau, revenant
     * souvent au même endroit, ferait un faux voyage). Mesure sur YFCC100M : après 1 à 3 jours
     * sans média, l'utilisateur est à nouveau au même endroit une fois sur deux.
     */
    const val MAX_GAP_DAYS = 2
    const val MIN_PHOTOS = 5

    /** Mois distincts (sur douze) d'un lieu familier. */
    const val FAMILIAR_MONTHS = 4

    /** Étalement minimal, en jours, des photos d'un lieu familier sur ces douze mois. */
    const val FAMILIAR_SPAN_DAYS = 90

    /** Part des médias situés que doit rassembler une ville pour titrer le voyage. */
    const val PLACE_SHARE = 0.5

    private const val WINDOW_BEFORE = 6
    private const val WINDOW_AFTER = 5
    private const val EARTH_RADIUS_KM = 6_371.0
    private const val KM_PER_DEGREE = 111.195
    private const val CELL_DEGREES = HOME_CELL_KM / KM_PER_DEGREE
    private const val DAY_MS = 86_400_000L

    /** Voyages de [points], par date de départ ; les jours sont ceux du fuseau [zone]. */
    fun detect(points: List<DatedPoint>, zone: ZoneId): List<Trip> {
        if (points.isEmpty()) return emptyList()
        val sorted = points.sortedBy { it.date }
        val dates = localDates(sorted, zone)
        val familiar = familiar(sorted, dates)

        val byDay = TreeMap<Long, Day>()
        sorted.forEachIndexed { i, point ->
            val places = familiar.getValue(month(dates[i]))
            // Chaque jour géolocalisé a son entrée : un jour sans média lointain interrompt un voyage.
            val day = byDay.getOrPut(dates[i].toEpochDay()) { Day(places.first()) }
            val away = places.minOf { distanceKm(point.latitude, point.longitude, it) }
            if (away > MIN_DISTANCE_KM) day.addFar(point.date, away)
        }

        val trips = mutableListOf<Trip>()
        var run: Run? = null
        for ((epochDay, day) in byDay) {
            val current = run
            if (current != null && day.far > 0 && epochDay - current.lastDay <= MAX_GAP_DAYS + 1) {
                current.add(epochDay, day)
            } else {
                // Journée près d'un lieu familier ou absence trop longue : le voyage en cours s'arrête.
                current?.closeInto(trips)
                run = if (day.far > 0) Run(epochDay, day) else null
            }
        }
        run?.closeInto(trips)
        return trips
    }

    /** Lieux familiers de chaque mois de [points], chez-soi d'abord (voir [TripDetector]). */
    fun familiarPlaces(points: List<DatedPoint>, zone: ZoneId): Map<YearMonth, List<LatLng>> {
        val sorted = points.sortedBy { it.date }
        return familiar(sorted, localDates(sorted, zone)).mapKeys { (month, _) -> YearMonth.of(month / 12, month % 12 + 1) }
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

    /**
     * Lieux familiers (chez-soi d'abord) de chaque mois présent dans [points], triés par date ;
     * [dates] : jour local de chacun. Mois indexés depuis l'an 0 (année × 12 + mois − 1).
     */
    private fun familiar(points: List<DatedPoint>, dates: List<LocalDate>): Map<Int, List<LatLng>> {
        val byMonth = TreeMap<Int, HashMap<Long, Cell>>()
        points.forEachIndexed { i, point ->
            byMonth.getOrPut(month(dates[i])) { HashMap() }
                .getOrPut(cellOf(point.latitude, point.longitude)) { Cell() }
                .add(point, dates[i])
        }
        val found = TreeMap<Int, List<LatLng>>()
        for (month in byMonth.keys) {
            val window = HashMap<Long, Cell>()
            for ((_, cells) in byMonth.subMap(month - WINDOW_BEFORE, true, month + WINDOW_AFTER, true)) {
                cells.forEach { (key, cell) -> window.getOrPut(key) { Cell() }.merge(cell) }
            }
            val places = window.entries
                .filter { (_, cell) -> cell.months >= FAMILIAR_MONTHS && cell.lastDay - cell.firstDay >= FAMILIAR_SPAN_DAYS }
                .sortedWith(BEST)
                .map { it.value.center }
            if (places.isNotEmpty()) found[month] = places
        }
        val fallback: List<LatLng> by lazy {
            val all = HashMap<Long, Cell>()
            byMonth.values.forEach { cells -> cells.forEach { (key, cell) -> all.getOrPut(key) { Cell() }.merge(cell) } }
            listOf(all.entries.sortedWith(BEST).first().value.center)
        }
        return byMonth.keys.associateWith { month -> found[month] ?: found.nearest(month) ?: fallback }
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

    private fun month(date: LocalDate): Int = date.year * 12 + date.monthValue - 1

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

    /** Valeur du mois le plus proche ; à égale distance, le plus ancien. */
    private fun <T> TreeMap<Int, T>.nearest(month: Int): T? {
        val before = floorEntry(month)
        val after = ceilingEntry(month)
        return when {
            before == null -> after?.value
            after == null -> before.value
            after.key - month < month - before.key -> after.value
            else -> before.value
        }
    }

    /** Lieux du plus au moins familier : mois distincts, puis jours, puis médias ; clé en dernier recours. */
    private val BEST = compareByDescending<Map.Entry<Long, Cell>> { it.value.months }
        .thenByDescending { it.value.days }
        .thenByDescending { it.value.count }
        .thenBy { it.key }

    /** Médias d'une cellule, sur un mois (points triés par date) ou cumulés sur plusieurs. */
    private class Cell {
        var months = 0
        var days = 0
        var count = 0
        var firstDay = Long.MAX_VALUE
        var lastDay = Long.MIN_VALUE
        var latitudeSum = 0.0
        var longitudeSum = 0.0

        val center: LatLng get() = LatLng(latitudeSum / count, longitudeSum / count)

        fun add(point: DatedPoint, date: LocalDate) {
            val day = date.toEpochDay()
            months = 1
            // Points triés par date : un jour nouveau se reconnaît au précédent.
            if (day != lastDay) days++
            firstDay = min(firstDay, day)
            lastDay = max(lastDay, day)
            count++
            latitudeSum += point.latitude
            longitudeSum += point.longitude
        }

        /** Cumul de mois distincts : chaque [other] est la cellule d'un autre mois. */
        fun merge(other: Cell) {
            months += other.months
            days += other.days
            count += other.count
            firstDay = min(firstDay, other.firstDay)
            lastDay = max(lastDay, other.lastDay)
            latitudeSum += other.latitudeSum
            longitudeSum += other.longitudeSum
        }
    }

    /** Médias d'un jour local pris loin de chez soi ; [home] : chez-soi du mois. */
    private class Day(val home: LatLng) {
        var far = 0
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        var farthest = 0.0

        fun addFar(date: Long, distanceKm: Double) {
            far++
            if (date < first) first = date
            if (date > last) last = date
            farthest = max(farthest, distanceKm)
        }
    }

    private class Run(val firstDay: Long, day: Day) {
        val home = day.home
        var lastDay = firstDay
        var photos = day.far
        var start = day.first
        var end = day.last
        var farthest = day.farthest

        fun add(epochDay: Long, day: Day) {
            lastDay = epochDay
            photos += day.far
            if (day.first < start) start = day.first
            if (day.last > end) end = day.last
            farthest = max(farthest, day.farthest)
        }

        fun closeInto(trips: MutableList<Trip>) {
            // Une seule journée : un aller-retour, sauf très loin.
            val night = lastDay > firstDay || farthest > MIN_DAY_TRIP_KM
            if (photos >= MIN_PHOTOS && night) {
                trips += Trip(start, end, LocalDate.ofEpochDay(firstDay), LocalDate.ofEpochDay(lastDay), photos, home)
            }
        }
    }
}
