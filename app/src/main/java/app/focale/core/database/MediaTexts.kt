package app.focale.core.database

/**
 * Recherche d'un mot dans le texte reconnu par l'index plein texte `media_text_fts` (FTS4), au
 * lieu d'un `LIKE '%mot%'` évalué sur la clé de chaque texte, 17 à 22 ms sur 47 000 photos
 * (docs/PERFORMANCE.md).
 *
 * FTS ne trouve que des débuts de mots : « fact » trouve « facture » et « factures », mais « crème »
 * ne trouve plus « demi-écrémé ». Quand rien d'autre dans la bibliothèque ne répond au terme (ni
 * étiquette, ni lieu, ni pays, ni nom de fichier, ni personne, ni mot d'un texte qui commence ainsi),
 * l'ancien `LIKE` le cherche n'importe où dans les textes : « fi » trouve encore « wifi » quand rien
 * d'autre ne correspond. Les suffixes de chaque mot, comme pour les noms ([MediaNames]), feraient un
 * index trop gros (des dizaines de mots par capture d'écran).
 *
 * La clé du texte ([MediaTextEntity.textKey]) et le mot cherché sont normalisés de la même façon
 * (`LabelDictionary.normalize` : `[a-z0-9]`, espaces simples), si bien que majuscules, accents et
 * ponctuation collée aux mots (« (Facture) », « Total: », « l'été ») ne changent rien ; la clé sépare
 * aussi lettres et chiffres collés (`RecognizedText.searchKey` : « 2024 » dans « #vacances2024 »).
 */
object MediaTexts {

    /**
     * Requête FTS de la clé normalisée [key] (non vide) : ses mots à la suite, le dernier comme
     * début de mot (« wi fi » : « wi » suivi d'un mot qui commence par « fi »).
     */
    fun match(key: String): String = "\"$key*\""

    /** Médias d'alias [m] dont le texte reconnu a un mot qui commence par [key], par l'index. */
    fun prefixClause(m: String, key: String, args: MutableList<Any?>): String {
        args += match(key)
        return "$m.id IN (SELECT docid FROM media_text_fts WHERE media_text_fts MATCH ?)"
    }

    /** Médias d'alias [m] dont la clé du texte reconnu contient [key] (`LIKE` sur chaque texte, comme avant). */
    fun likeClause(m: String, key: String, args: MutableList<Any?>): String {
        args += MediaQueryBuilder.likePattern(key)
        return "$m.id IN (SELECT media_id FROM media_text WHERE text_key LIKE ? ESCAPE '\\')"
    }

    /**
     * Repli de [prefixClause] : médias d'alias [m] dont la clé du texte contient [key], seulement si
     * aucun média hors corbeille ne répond autrement au terme, par l'une des voies [others] (alias du
     * média en paramètre ; leurs arguments vont à [args]). Le parcours de `media_text` dépend d'une
     * sous-requête d'une ligne au plus (`g`), boucle extérieure de la jointure : quand une autre voie
     * trouve un média (une étiquette, un lieu, le début d'un mot d'un texte…), elle est vide et la
     * table n'est pas lue.
     */
    fun fallbackClause(m: String, key: String, args: MutableList<Any?>, others: (String) -> List<String>): String {
        // « + » : SQLite partirait sinon de l'index de la corbeille, toute la timeline (voir MediaQueryBuilder.count).
        val gate = "SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM media o WHERE +o.is_trashed = 0 AND (${others("o").joinToString(" OR ")}))"
        args += MediaQueryBuilder.likePattern(key)
        return "$m.id IN (SELECT t.media_id FROM ($gate) g CROSS JOIN media_text t WHERE t.text_key LIKE ? ESCAPE '\\')"
    }
}
