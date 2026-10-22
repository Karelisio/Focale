package app.focale.ml

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Résout des noms de pays saisis (français ou anglais, sans accents) en codes
 * ISO 3166-1, à partir des noms localisés fournis par la JVM (hors ligne).
 */
object CountryResolver {

    private val codes: List<String> by lazy { Locale.getISOCountries().toList() }

    /** Noms normalisés de chaque pays dans une langue, avec son code ; calculés une fois par langue. */
    private val namesByLanguage = ConcurrentHashMap<String, List<Pair<String, String>>>()

    private fun names(language: Locale): List<Pair<String, String>> = namesByLanguage.getOrPut(language.language) {
        codes.flatMap { code ->
            val locale = Locale.Builder().setRegion(code).build()
            listOf(LabelDictionary.normalize(locale.getDisplayCountry(language)))
                .filter { it.isNotBlank() }
                .map { it to code }
        }
    }

    /**
     * Codes des pays dont un mot du nom commence par [word] (3 lettres minimum) : noms français et
     * anglais, et ceux de [locale], langue de l'application (celle du système quand Focale la
     * suit : « Deutschland » pour un téléphone en allemand).
     */
    fun resolve(word: String, locale: Locale = Locale.ENGLISH): List<String> {
        val term = LabelDictionary.normalize(word)
        if (term.length < 3) return emptyList()
        val matching = listOf(Locale.FRENCH, Locale.ENGLISH, locale)
            .distinctBy { it.language }
            .flatMap(::names)
            .filter { (name, _) -> name == term || name.split(' ').any { it.startsWith(term) } }
            .mapTo(HashSet()) { it.second }
        return codes.filter { it in matching }
    }

    /** Nom du pays dans la langue de [locale]. */
    fun displayName(code: String, locale: Locale): String =
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }
}
