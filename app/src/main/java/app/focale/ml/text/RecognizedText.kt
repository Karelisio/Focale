package app.focale.ml.text

import app.focale.ml.LabelDictionary

/**
 * Mise en forme pure (testable sur la JVM) du texte reconnu dans une photo : texte brut
 * affiché et copié, et clé normalisée interrogée par la recherche.
 */
object RecognizedText {

    private val whitespace = Regex("[\\s\\u00A0\\u2007\\u202F]+")

    /** Mots liés par un trait d'union, un point ou une apostrophe : « Wi-Fi », « S.N.C.F. ». */
    private val compound = Regex("[\\p{L}\\p{M}\\p{N}]+(?:[-\\u2010\\u2011.\\u00B7'\\u2019_][\\p{L}\\p{M}\\p{N}]+)+")

    /** Groupes de chiffres séparés par des espaces : « 06 12 34 56 78 ». */
    private val digitGroups = Regex("\\d{2,}(?:[ \\u00A0\\u202F]\\d{2,})+")

    /** Suites de lettres ou de chiffres d'un mot de la clé où ils sont collés : « cafe2024 ». */
    private val runs = Regex("[a-z]+|[0-9]+")

    /** Texte brut : une ligne par ligne reconnue, espaces superflus retirés, lignes vides ignorées. */
    fun assemble(lines: List<String>): String = lines
        .map { it.replace(whitespace, " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")

    /**
     * Clé de recherche : [text] normalisé comme les saisies ([LabelDictionary.normalize] :
     * minuscules, sans accents, espaces simples), suivi de la forme compacte des mots
     * composés et des numéros, pour que « wifi » trouve « Wi-Fi » et « 0612345678 » un
     * numéro écrit par paires, puis des lettres et des chiffres collés d'un même mot, séparés :
     * la recherche, par début de mot (`MediaTexts`), trouve « 2024 » dans « #vacances2024 » et
     * « 1234 » dans « AF1234 ».
     */
    fun searchKey(text: String): String {
        val key = LabelDictionary.normalize(text)
        if (key.isEmpty()) return ""
        val words = key.split(' ').toSet()
        val joined = (compound.findAll(text) + digitGroups.findAll(text))
            .map { LabelDictionary.normalize(it.value).replace(" ", "") }
            .filter { it.length > 1 && it !in words }
            .distinct()
            .toList()
        val parts = (words + joined)
            .filter { word -> word.any { it in '0'..'9' } && word.any { it in 'a'..'z' } }
            .flatMap { word -> runs.findAll(word).map { it.value } }
            .filter { it.length > 1 && it !in words && it !in joined }
            .distinct()
        val extra = joined + parts
        return if (extra.isEmpty()) key else key + " " + extra.joinToString(" ")
    }
}
