package app.focale.data

import androidx.paging.PagingData
import androidx.paging.insertSeparators
import androidx.paging.map
import app.focale.core.model.GridItem
import app.focale.core.model.GroupLevel
import app.focale.core.model.GroupMode
import app.focale.core.model.MediaItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calcul des périodes (jour / mois / année) utilisées pour les en-têtes de grille. */
object DateGrouping {

    /**
     * Niveau d'en-tête effectif. En mode automatique, la granularité suit le zoom :
     * peu de colonnes → jours, grille dense → mois puis années.
     */
    fun resolve(mode: GroupMode, columns: Int): GroupLevel? = when (mode) {
        GroupMode.AUTO -> when {
            columns <= 3 -> GroupLevel.DAY
            columns <= 5 -> GroupLevel.MONTH
            else -> GroupLevel.YEAR
        }
        GroupMode.DAY -> GroupLevel.DAY
        GroupMode.MONTH -> GroupLevel.MONTH
        GroupMode.YEAR -> GroupLevel.YEAR
        GroupMode.NONE -> null
    }

    /** Identifiant numérique de la période contenant [epochMs]. */
    fun periodKey(epochMs: Long, level: GroupLevel, zone: ZoneId): Long {
        val date = localDate(epochMs, zone)
        return when (level) {
            GroupLevel.DAY -> date.toEpochDay()
            GroupLevel.MONTH -> date.year * 12L + date.monthValue - 1
            GroupLevel.YEAR -> date.year.toLong()
        }
    }

    /** Début (ms) de la période contenant [epochMs]. */
    fun periodStart(epochMs: Long, level: GroupLevel, zone: ZoneId): Long = millis(firstDay(epochMs, level, zone), zone)

    /** Fin (ms, exclue) de la période contenant [epochMs] : le début de la suivante. */
    fun periodEnd(epochMs: Long, level: GroupLevel, zone: ZoneId): Long = millis(next(firstDay(epochMs, level, zone), level), zone)

    /**
     * Bornes des périodes de [level] qui couvrent [first]..[last] (ms) dans le fuseau [zone] : le
     * début de chacune puis la fin de la dernière, croissants. Chaque période commence à minuit
     * local de son premier jour : un jour de changement d'heure dure 23 ou 25 heures, et ses
     * médias restent sous son en-tête. Sert à compter les médias par plages de dates dans l'index
     * (`MediaQueryBuilder.histogram`), avec les mêmes périodes que les en-têtes.
     */
    fun bounds(first: Long, last: Long, level: GroupLevel, zone: ZoneId): List<Long> {
        val bounds = ArrayList<Long>()
        var day = firstDay(first, level, zone)
        while (true) {
            val start = millis(day, zone)
            bounds += start
            if (start > last) return bounds
            day = next(day, level)
        }
    }

    private fun firstDay(epochMs: Long, level: GroupLevel, zone: ZoneId): LocalDate {
        val date = localDate(epochMs, zone)
        return when (level) {
            GroupLevel.DAY -> date
            GroupLevel.MONTH -> date.withDayOfMonth(1)
            GroupLevel.YEAR -> date.withDayOfYear(1)
        }
    }

    private fun next(day: LocalDate, level: GroupLevel): LocalDate = when (level) {
        GroupLevel.DAY -> day.plusDays(1)
        GroupLevel.MONTH -> day.plusMonths(1)
        GroupLevel.YEAR -> day.plusYears(1)
    }

    /** Début du jour [day] dans [zone] (minuit, ou la première heure qui existe ce jour-là). */
    private fun millis(day: LocalDate, zone: ZoneId): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun localDate(epochMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}

/**
 * Convertit une liste paginée de médias en éléments de grille, en insérant un en-tête
 * au début de chaque période si [level] n'est pas null.
 */
fun PagingData<MediaItem>.toGridItems(
    level: GroupLevel?,
    zone: ZoneId = ZoneId.systemDefault(),
): PagingData<GridItem> {
    val items: PagingData<GridItem> = map { GridItem.Media(it) }
    if (level == null) return items
    return items.insertSeparators { before: GridItem?, after: GridItem? ->
        val next = (after as? GridItem.Media)?.item ?: return@insertSeparators null
        val previous = (before as? GridItem.Media)?.item
        val nextKey = DateGrouping.periodKey(next.dateTaken, level, zone)
        if (previous == null || DateGrouping.periodKey(previous.dateTaken, level, zone) != nextKey) {
            GridItem.Header(
                level = level,
                periodStart = DateGrouping.periodStart(next.dateTaken, level, zone),
                periodKey = nextKey,
            )
        } else {
            null
        }
    }
}
