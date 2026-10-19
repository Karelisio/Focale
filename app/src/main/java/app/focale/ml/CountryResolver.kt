package app.focale.ml

import java.util.Locale

/**
 * Résout des noms de pays saisis (français ou anglais, sans accents) en codes
 * ISO 3166-1, à partir des noms localisés fournis par la JVM (hors ligne).
 */
object CountryResolver {

    private val names: List<Pair<String, String>> by lazy {
        Locale.getISOCountries().flatMap { code ->
            val locale = Locale.Builder().setRegion(code).build()
            listOf(Locale.FRENCH, Locale.ENGLISH, Locale.getDefault())
                .map { LabelDictionary.normalize(locale.getDisplayCountry(it)) }
                .filter { it.isNotBlank() }
                .distinct()
                .map { it to code }
        }
    }

    /** Codes des pays dont un mot du nom commence par [word] (3 lettres minimum). */
    fun resolve(word: String): List<String> {
        val term = LabelDictionary.normalize(word)
        if (term.length < 3) return emptyList()
        return names.filter { (name, _) ->
            name == term || name.split(' ').any { it.startsWith(term) }
        }.map { it.second }.distinct()
    }

    /** Nom du pays dans la langue de [locale]. */
    fun displayName(code: String, locale: Locale): String =
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }
}
