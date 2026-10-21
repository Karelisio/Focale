package app.focale.data

import androidx.compose.runtime.Immutable
import app.focale.core.model.DateBucket
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.ml.LabelDictionary
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Pastille de filtre de la recherche. Les pastilles se combinent par ET, entre elles et avec le
 * texte saisi ([SearchFilters.query]).
 */
@Serializable
@Immutable
sealed interface SearchChip {

    /** Photos où figure la personne ; plusieurs personnes : photos où elles sont ensemble. */
    @Serializable
    @SerialName("person")
    data class Person(
        val id: Long,
        /** Nom au moment du choix : libellé d'une recherche mémorisée dont la personne a disparu. */
        val name: String? = null,
    ) : SearchChip

    /** Lieu exact (ville du géocodage hors ligne), dans son pays. */
    @Serializable
    @SerialName("place")
    data class Place(val name: String, val country: String? = null) : SearchChip

    /** Jours [start] à [end] inclus (`LocalDate.toEpochDay`), ou [month] de toutes les années. */
    @Serializable
    @SerialName("period")
    data class Period(
        val start: Long? = null,
        val end: Long? = null,
        /** Mois (1-12), toutes années confondues (« juillet »). */
        val month: Int? = null,
    ) : SearchChip {
        val firstDay: LocalDate? get() = start?.let(LocalDate::ofEpochDay)
        val lastDay: LocalDate? get() = end?.let(LocalDate::ofEpochDay)

        companion object {
            /** Du jour [first] au jour [last] inclus, quel que soit leur ordre. */
            fun days(first: LocalDate, last: LocalDate): Period =
                if (last < first) days(last, first) else Period(start = first.toEpochDay(), end = last.toEpochDay())

            fun year(year: Int): Period = days(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))

            fun month(month: YearMonth): Period = days(month.atDay(1), month.atEndOfMonth())

            /** Un mois de toutes les années (« juillet »). */
            fun everyYear(month: Int): Period = Period(month = month)
        }
    }

    /** Type de média (photos, vidéos, captures, GIF). */
    @Serializable
    @SerialName("type")
    data class Type(val filter: MediaFilter) : SearchChip
}

/**
 * Recherche en cours : texte validé et pastilles. Sérialisée pour l'état sauvegardé de l'écran
 * (rotation, processus tué) et pour l'historique ([historyKey]).
 */
@Serializable
@Immutable
data class SearchFilters(
    /** Texte validé, analysé par [app.focale.ml.SearchQueryParser]. */
    val text: String = "",
    /** Pastilles, dans l'ordre d'ajout. */
    val chips: List<SearchChip> = emptyList(),
) {
    /** Rien à chercher : la page d'exploration s'affiche. */
    val isEmpty: Boolean get() = text.isBlank() && chips.isEmpty()

    /**
     * Ajoute [chip] : une personne de plus (sans doublon) ; un lieu, une période ou un type prend la
     * place de la pastille de même nature.
     */
    operator fun plus(chip: SearchChip): SearchFilters {
        val index = chips.indexOfFirst { it.sameSlot(chip) }
        return copy(chips = if (index >= 0) chips.toMutableList().apply { set(index, chip) } else chips + chip)
    }

    operator fun minus(chip: SearchChip): SearchFilters = copy(chips = chips - chip)

    /**
     * Requête de la recherche : [textQuery], celle du texte validé (`SearchRepository.resolve`,
     * recherche par le sens comprise), restreinte par chacune des pastilles.
     *
     * Personnes : un terme par personne, combinés par ET (photos ensemble, comme `photosTogether`).
     * Lieu : nom exact du lieu et pays. Période et type se combinent à ceux reconnus dans le texte ;
     * deux types ou deux mois inconciliables donnent une requête vide.
     */
    fun query(textQuery: MediaQuery = MediaQuery(), zone: ZoneId = ZoneId.systemDefault()): MediaQuery {
        var query = textQuery
        var contradictory = false
        val terms = textQuery.terms.toMutableList()
        chips.forEach { chip ->
            when (chip) {
                is SearchChip.Person -> terms += SearchTerm(personIds = listOf(chip.id))
                is SearchChip.Place -> {
                    terms += SearchTerm(place = LabelDictionary.normalize(chip.name), exactPlace = true)
                    chip.country?.let { terms += SearchTerm(countries = listOf(it)) }
                }
                is SearchChip.Period -> {
                    chip.month?.let { month ->
                        if (query.month != null && query.month != month) contradictory = true
                        query = query.copy(month = month)
                    }
                    val first = chip.firstDay
                    val last = chip.lastDay
                    if (first != null && last != null) {
                        val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
                        val to = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        // Intersection avec une période du texte ; disjointes, elles ne donnent rien.
                        query = query.copy(
                            dateFrom = maxOf(query.dateFrom ?: from, from),
                            dateTo = minOf(query.dateTo ?: to, to),
                        )
                    }
                }
                is SearchChip.Type -> {
                    val filter = intersect(query.filter, chip.filter)
                    if (filter == null) contradictory = true else query = query.copy(filter = filter)
                }
            }
        }
        query = query.copy(terms = terms.distinct())
        return if (contradictory) query.copy(mediaIds = emptyList()) else query
    }

    /**
     * Personnes d'une recherche mémorisée : une personne disparue depuis ([known] ne la contient
     * plus : fusion, suppression) est remplacée par la seule personne nommée ([named]) de même nom.
     */
    fun relinkPeople(known: Set<Long>, named: Map<Long, String>): SearchFilters {
        val relinked = chips.map { chip ->
            if (chip !is SearchChip.Person || chip.id in known) return@map chip
            val key = chip.name?.let(LabelDictionary::normalize)?.takeIf { it.isNotEmpty() } ?: return@map chip
            val match = named.filterValues { LabelDictionary.normalize(it) == key }.keys.singleOrNull() ?: return@map chip
            SearchChip.Person(match, named.getValue(match))
        }
        return copy(chips = relinked.distinctBy { if (it is SearchChip.Person) it.id else it })
    }

    /**
     * Clé de l'historique : le texte seul quand il n'y a pas de pastille (comme les recherches
     * mémorisées avant elles), sinon toute la recherche en JSON, rendue lisible à l'affichage.
     */
    val historyKey: String
        get() = if (chips.isEmpty()) text.trim() else json.encodeToString(serializer(), copy(text = text.trim()))

    /** Forme sauvegardée (état de l'écran). */
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            classDiscriminator = "kind"
        }

        /** Recherche sauvegardée par [encode] ; vide si absente ou illisible. */
        fun decode(value: String?): SearchFilters =
            value?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: SearchFilters()

        /** Recherche mémorisée sous [key] ; null si elle n'est plus lisible (version future). */
        fun fromHistory(key: String): SearchFilters? =
            if (key.startsWith(JSON_START)) {
                runCatching { json.decodeFromString(serializer(), key) }.getOrNull()
            } else {
                SearchFilters(text = key)
            }

        private const val JSON_START = "{\""
    }
}

/** Même emplacement : la même personne, ou une pastille unique de même nature (lieu, période, type). */
private fun SearchChip.sameSlot(other: SearchChip): Boolean = when (this) {
    is SearchChip.Person -> other is SearchChip.Person && other.id == id
    is SearchChip.Place -> other is SearchChip.Place
    is SearchChip.Period -> other is SearchChip.Period
    is SearchChip.Type -> other is SearchChip.Type
}

/**
 * Médias à la fois de type [a] et [b] ; null s'ils s'excluent. Un GIF et une capture d'écran sont
 * des images (une capture vidéo rangée avec les captures est négligée).
 */
internal fun intersect(a: MediaFilter, b: MediaFilter): MediaFilter? = when {
    a == b || b == MediaFilter.ALL -> a
    a == MediaFilter.ALL -> b
    setOf(a, b) == setOf(MediaFilter.PHOTOS, MediaFilter.GIFS) -> MediaFilter.GIFS
    setOf(a, b) == setOf(MediaFilter.PHOTOS, MediaFilter.SCREENSHOTS) -> MediaFilter.SCREENSHOTS
    else -> null
}

/** Périodes proposées pour une pastille : années puis mois récents qui ont des médias. */
@Immutable
data class PeriodChoices(val years: List<Int> = emptyList(), val months: List<YearMonth> = emptyList()) {
    companion object {
        /** D'après l'histogramme mensuel d'une recherche, les périodes les plus récentes d'abord. */
        fun from(buckets: List<DateBucket>, zone: ZoneId = ZoneId.systemDefault(), recentMonths: Int = 6): PeriodChoices {
            val months = buckets.filter { it.count > 0 }
                .map { YearMonth.from(Instant.ofEpochMilli(it.periodStart).atZone(zone)) }
                .distinct()
                .sortedDescending()
            return PeriodChoices(years = months.map { it.year }.distinct(), months = months.take(recentMonths))
        }
    }
}
