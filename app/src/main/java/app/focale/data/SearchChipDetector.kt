package app.focale.data

import androidx.compose.runtime.Immutable
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.ml.LabelDictionary
import app.focale.ml.SearchQueryParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Pastille tirée de la saisie : la choisir retire [words] (mots tels que saisis) du texte. */
@Immutable
data class ChipSuggestion(val chip: SearchChip, val words: List<String>)

/**
 * Pastilles proposées pendant la frappe : période et type reconnus par [SearchQueryParser] (mêmes
 * règles que la recherche), personnes nommées et lieux de la bibliothèque dont un mot commence par
 * un mot saisi.
 */
object SearchChipDetector {

    /**
     * Suggestions pour [text], dans cet ordre : période, types, personnes, lieux.
     * @param people personnes nommées (identifiant → nom), comme pour la recherche.
     * @param places lieux de la bibliothèque, les plus photographiés d'abord.
     * @param locale langue de l'application, comme pour la recherche (ordre des dates numériques).
     */
    fun detect(
        text: String,
        people: Map<Long, String>,
        places: List<PlaceSuggestion>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.ROOT,
    ): List<ChipSuggestion> {
        val dates = mutableListOf<String>()
        val types = LinkedHashMap<MediaFilter, MutableList<String>>()
        val persons = LinkedHashMap<Long, MutableList<String>>()
        val placeWords = LinkedHashMap<PlaceSuggestion, MutableList<String>>()
        for (word in words(text)) {
            val query = SearchQueryParser.parse(word, today, zone, people, locale)
            when {
                // Mot vide (« de », « la »…), favoris : pas de pastille.
                query.terms.isEmpty() && (query.dateFrom != null || query.month != null) -> dates += word
                query.terms.isEmpty() && query.filter != MediaFilter.ALL -> types.getOrPut(query.filter) { mutableListOf() } += word
                query.terms.isNotEmpty() -> {
                    query.terms.flatMap { it.personIds }.forEach { persons.getOrPut(it) { mutableListOf() } += word }
                    val keys = LabelDictionary.normalize(word).split(' ').filter { it.length >= MIN_PREFIX }
                    places.filter { place -> keys.any { key -> placeWords(place).any { it.startsWith(key) } } }
                        .forEach { placeWords.getOrPut(it) { mutableListOf() } += word }
                }
            }
        }
        return buildList {
            if (dates.isNotEmpty()) {
                period(SearchQueryParser.parse(dates.joinToString(" "), today, zone, people, locale), zone)
                    ?.let { add(ChipSuggestion(it, dates.toList())) }
            }
            types.forEach { (filter, words) -> add(ChipSuggestion(SearchChip.Type(filter), words)) }
            persons.entries.take(MAX_PEOPLE).forEach { (id, words) -> add(ChipSuggestion(SearchChip.Person(id, people[id]), words)) }
            placeWords.entries.take(MAX_PLACES).forEach { (place, words) -> add(ChipSuggestion(SearchChip.Place.of(place), words)) }
        }
    }

    /**
     * [text] sans les mots de [suggestion] (une occurrence chacun) ; vide s'il n'y reste rien à
     * chercher (mots vides seulement).
     */
    fun remainingText(text: String, suggestion: ChipSuggestion, people: Map<Long, String> = emptyMap(), locale: Locale = Locale.ROOT): String {
        val rest = words(text).toMutableList()
        suggestion.words.forEach { rest.remove(it) }
        val remaining = rest.joinToString(" ")
        return if (SearchQueryParser.isEmpty(SearchQueryParser.parse(remaining, people = people, locale = locale))) "" else remaining
    }

    private fun words(text: String): List<String> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Mots du nom GeoNames et des noms français et anglais : « lisb » trouve Lisbon et Lisbonne. */
    private fun placeWords(place: PlaceSuggestion): List<String> =
        place.names.forms.flatMap { LabelDictionary.normalize(it).split(' ') }

    /** Période d'une requête de dates : plage de jours, ou mois de toutes les années. */
    private fun period(query: MediaQuery, zone: ZoneId): SearchChip.Period? {
        val from = query.dateFrom
        val to = query.dateTo
        val month = query.month
        return when {
            from != null && to != null && month == null -> SearchChip.Period.days(day(from, zone), day(to, zone).minusDays(1))
            from == null && month != null -> SearchChip.Period.everyYear(month)
            else -> null
        }
    }

    private fun day(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    private const val MIN_PREFIX = 2
    private const val MAX_PEOPLE = 4
    private const val MAX_PLACES = 4
}
