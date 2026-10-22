package app.focale.core.database

/**
 * Recherche d'un mot dans les noms de fichiers par l'index plein texte `media_name` (FTS4), au
 * lieu d'un `LIKE '%mot%'` évalué sur chaque ligne de `media` (docs/PERFORMANCE.md).
 *
 * FTS ne trouve que des débuts de mots : chaque suite de lettres du nom y est donc écrite avec
 * ses suffixes d'au moins [MIN_LENGTH] lettres (« Screenshot » : « Screenshot creenshot … shot
 * hot »), et un mot d'au moins autant de lettres ASCII, contenu dans un nom, commence l'un d'eux :
 * la sous-chaîne qu'il désigne n'est faite que de lettres, donc tient dans une même suite. Le
 * tokenizer « simple » de FTS4 coupe aux espaces et ne replie que la casse ASCII, comme `LIKE`.
 * Les candidats sont revérifiés par le même `LIKE` qu'avant : aucun résultat perdu ni ajouté. Un
 * texte plus court, avec un chiffre, un séparateur ou une lettre non ASCII garde le `LIKE` sur
 * toute la table ([searchable]).
 */
object MediaNames {

    /**
     * Longueur maximale d'un suffixe indexé : un nom fait d'une très longue suite de lettres reste
     * petit dans l'index, et un mot plus long est cherché par ses [MAX_TOKEN] premières lettres
     * (candidats revérifiés).
     */
    const val MAX_TOKEN = 24

    /** Longueur minimale d'un mot cherché par l'index, et de ses suffixes : la moitié moins de mots. */
    const val MIN_LENGTH = 3

    /** Ligne de l'index plein texte de [media]. */
    fun entity(media: MediaEntity) = MediaNameEntity(media.id, document(media.displayName))

    /**
     * Texte indexé de [displayName] : les suffixes d'au moins [MIN_LENGTH] lettres de chacune de ses
     * suites de lettres (du plan multilingue de base : une lettre hors de ce plan sépare, comme
     * chiffres et ponctuation), tronqués à [MAX_TOKEN], sans doublon, séparés par des espaces.
     */
    fun document(displayName: String): String {
        val tokens = LinkedHashSet<String>()
        var start = -1
        for (index in 0..displayName.length) {
            val letter = index < displayName.length && displayName[index].isLetter()
            if (letter && start < 0) start = index
            if (!letter && start >= 0) {
                for (from in start..index - MIN_LENGTH) tokens += displayName.substring(from, minOf(index, from + MAX_TOKEN))
                start = -1
            }
        }
        return tokens.joinToString(" ")
    }

    /** Vrai si [text] peut être cherché par l'index : au moins [MIN_LENGTH] lettres ASCII, rien d'autre. */
    fun searchable(text: String): Boolean = text.length >= MIN_LENGTH && text.all { it in 'a'..'z' || it in 'A'..'Z' }

    /** Requête FTS de [text] ([searchable]) : un suffixe indexé qui commence par lui. */
    fun match(text: String): String = "\"${text.take(MAX_TOKEN)}*\""
}
