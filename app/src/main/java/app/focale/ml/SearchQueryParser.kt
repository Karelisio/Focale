package app.focale.ml

import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import java.time.LocalDate
import java.time.ZoneId

/**
 * Traduit une recherche en langage naturel (français ou anglais) en [MediaQuery].
 *
 * - dates : « 2023 », « juillet », « juillet 2022 », « 14/07/2021 », « 2021-07-14 »,
 *   « aujourd'hui », « hier » ;
 * - types : « vidéos », « captures », « gif », « photos », « favoris » ;
 * - autres mots : étiquettes ML Kit (dictionnaire FR/EN), lieux, pays, noms de
 *   fichiers, noms des personnes ; chaque mot doit correspondre (ET), par l'une de ces
 *   voies (OU).
 */
object SearchQueryParser {

    private val months: Map<String, Int> = buildMap {
        val fr = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre")
        val en = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
        fr.forEachIndexed { i, name -> put(name, i + 1) }
        en.forEachIndexed { i, name -> put(name, i + 1) }
        // Abréviations courantes.
        listOf("janv", "fevr", "avr", "juil", "sept", "oct", "nov", "dec", "jan", "feb", "apr", "jun", "jul", "aug", "sep")
            .forEach { abbreviation ->
                (fr + en).indexOfFirst { it.startsWith(abbreviation) }.takeIf { it >= 0 }?.let { put(abbreviation, it % 12 + 1) }
            }
    }

    private val stopWords = setOf(
        "de", "du", "des", "la", "le", "les", "l", "a", "au", "aux", "en", "et", "un", "une", "d", "avec", "sur", "pour",
        "mes", "mon", "ma", "dans", "the", "of", "in", "at", "and", "with", "my", "on", "for", "an", "from", "to",
    )

    private val typeWords: Map<String, MediaFilter> = mapOf(
        "video" to MediaFilter.VIDEOS, "videos" to MediaFilter.VIDEOS, "film" to MediaFilter.VIDEOS, "films" to MediaFilter.VIDEOS,
        "photo" to MediaFilter.PHOTOS, "photos" to MediaFilter.PHOTOS, "image" to MediaFilter.PHOTOS, "images" to MediaFilter.PHOTOS,
        "capture" to MediaFilter.SCREENSHOTS, "captures" to MediaFilter.SCREENSHOTS,
        "screenshot" to MediaFilter.SCREENSHOTS, "screenshots" to MediaFilter.SCREENSHOTS,
        "gif" to MediaFilter.GIFS, "gifs" to MediaFilter.GIFS,
    )

    private val favoriteWords = setOf("favori", "favoris", "favorite", "favorites", "favourite", "favourites", "prefere", "preferes")
    private val todayWords = setOf("aujourd hui", "aujourdhui", "today")
    private val yesterdayWords = setOf("hier", "yesterday")

    private val numericDate = Regex("""^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$""")
    private val isoDate = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val year = Regex("""^(19|20)\d{2}$""")

    /** @param people personnes nommées (identifiant → nom), reconnues par leur nom. */
    fun parse(
        text: String,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        people: Map<Long, String> = emptyMap(),
    ): MediaQuery {
        var query = MediaQuery()
        var rawDates = text.trim().lowercase()
        // Les dates numériques sont reconnues avant la normalisation (qui supprime « / »).
        val tokens = mutableListOf<String>()
        for (raw in rawDates.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            val numeric = numericDate.matchEntire(raw)
            val iso = isoDate.matchEntire(raw)
            val day = when {
                numeric != null -> runCatching {
                    LocalDate.of(numeric.groupValues[3].toInt(), numeric.groupValues[2].toInt(), numeric.groupValues[1].toInt())
                }.getOrNull()
                iso != null -> runCatching {
                    LocalDate.of(iso.groupValues[1].toInt(), iso.groupValues[2].toInt(), iso.groupValues[3].toInt())
                }.getOrNull()
                else -> null
            }
            if (day != null) {
                query = query.withRange(day, day.plusDays(1), zone)
            } else {
                tokens += raw
            }
        }
        rawDates = tokens.joinToString(" ")

        val normalized = LabelDictionary.normalize(rawDates)
        if (normalized in todayWords || normalized.contains("aujourd hui")) {
            query = query.withRange(today, today.plusDays(1), zone)
        }
        val words = normalized.replace("aujourd hui", " ").split(' ').filter { it.isNotBlank() }

        var pendingMonth: Int? = null
        var pendingYear: Int? = null
        val remaining = mutableListOf<String>()
        // Un prénom l'emporte sur un mois homonyme (« Avril », « June »).
        val nameWords = people.values.flatMapTo(HashSet()) { LabelDictionary.normalize(it).split(' ') }
        for (word in words) {
            when {
                word in todayWords -> query = query.withRange(today, today.plusDays(1), zone)
                word in yesterdayWords -> query = query.withRange(today.minusDays(1), today, zone)
                word in nameWords && word !in stopWords -> remaining += word
                year.matches(word) -> pendingYear = word.toInt()
                months.containsKey(word) -> pendingMonth = months.getValue(word)
                word in favoriteWords -> query = query.copy(favoritesOnly = true)
                typeWords.containsKey(word) -> query = query.copy(filter = typeWords.getValue(word))
                word in stopWords -> Unit
                else -> remaining += word
            }
        }
        query = when {
            pendingYear != null && pendingMonth != null -> {
                val start = LocalDate.of(pendingYear, pendingMonth, 1)
                query.withRange(start, start.plusMonths(1), zone)
            }
            pendingYear != null -> {
                val start = LocalDate.of(pendingYear, 1, 1)
                query.withRange(start, start.plusYears(1), zone)
            }
            pendingMonth != null -> query.copy(month = pendingMonth)
            else -> query
        }
        val terms = remaining.map { word ->
            SearchTerm(
                labels = LabelDictionary.match(word).sorted(),
                place = word.takeIf { it.length >= 2 },
                countries = CountryResolver.resolve(word),
                text = word,
                personIds = matchPeople(word, people),
            )
        }
        return query.copy(terms = terms)
    }

    /**
     * Personnes dont un mot du nom commence par [word] (mot déjà normalisé) : « mar »
     * trouve « Marie Dupont » et « Marc », sans tenir compte des accents ni de la casse.
     */
    fun matchPeople(word: String, people: Map<Long, String>): List<Long> {
        if (word.length < MIN_NAME_PREFIX || people.isEmpty()) return emptyList()
        return people
            .filterValues { name -> LabelDictionary.normalize(name).split(' ').any { it.startsWith(word) } }
            .keys
            .sorted()
    }

    private const val MIN_NAME_PREFIX = 2

    /** Vrai si la requête ne filtre rien (champ vide ou mots vides seulement). */
    fun isEmpty(query: MediaQuery): Boolean =
        query.terms.isEmpty() && query.dateFrom == null && query.month == null &&
            query.filter == MediaFilter.ALL && !query.favoritesOnly

    private fun MediaQuery.withRange(from: LocalDate, to: LocalDate, zone: ZoneId): MediaQuery = copy(
        dateFrom = from.atStartOfDay(zone).toInstant().toEpochMilli(),
        dateTo = to.atStartOfDay(zone).toInstant().toEpochMilli(),
    )
}
