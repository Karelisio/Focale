package app.focale.backup

import app.focale.data.SearchChip
import app.focale.data.SearchFilters

/**
 * Historique de recherche d'une sauvegarde, réuni à celui de ce téléphone. Une recherche à
 * pastilles désigne ses personnes par leur identifiant, propre au téléphone sauvegardé : il est
 * remplacé par celui de la personne recréée, ou par 0 (aucune) pour une personne absente de la
 * sauvegarde, que la recherche retrouvera par son nom (`SearchFilters.relinkPeople`).
 */
internal object SearchHistoryMerge {

    /** Personne absente : aucun identifiant ne vaut 0 (clé auto-incrémentée). */
    private const val NOBODY = 0L

    /**
     * Clé de l'historique [key] (sauvegardée), ses personnes désignées sur ce téléphone par
     * [persons] (null : absente). Une clé illisible (version ultérieure) est gardée telle quelle.
     */
    fun remap(key: String, persons: (Long) -> Long?): String {
        val filters = SearchFilters.fromHistory(key) ?: return key
        if (filters.chips.none { it is SearchChip.Person }) return key
        val chips = filters.chips.map { chip -> if (chip is SearchChip.Person) chip.copy(id = persons(chip.id) ?: NOBODY) else chip }
        return filters.copy(chips = chips).historyKey
    }

    /**
     * Forme de [key] sans identifiants de personnes : la même recherche, mémorisée avant une
     * restauration ou par une restauration précédente, a la même. Deux recherches qui ne diffèrent
     * que par une personne sans nom se confondent : la plus récente est gardée.
     */
    fun canonical(key: String): String = remap(key) { NOBODY }
}
