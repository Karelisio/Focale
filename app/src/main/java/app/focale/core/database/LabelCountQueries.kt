package app.focale.core.database

import androidx.room.Query

/** Étiquette et nombre de médias qui la portent (`label_count`). */
data class LabelTally(val label: String, val count: Int)

/**
 * Tenue de `label_count` ([LabelCountEntity]), partagée par les DAO qui écrivent les médias
 * ([MediaDao]) et leurs étiquettes ([AnalysisDao]) : une écriture est encadrée par
 * [withLabelCounts], qui retire des comptes les étiquettes visibles de ses médias avant elle et
 * ajoute celles d'après. Ajout, mise à la corbeille, restauration, retrait de l'index (accès
 * partiel) ou nouvelle analyse déplacent ainsi les étiquettes d'un média ; un média réécrit tel
 * quel les retire puis les rend. Les étiquettes d'un média absent de l'index ne comptent pas :
 * leur purge ne change rien.
 */
interface LabelCountQueries {

    /** Vrai si au moins une étiquette est enregistrée (avant la première analyse : rien à compter). */
    @Query("SELECT EXISTS (SELECT 1 FROM media_label)")
    suspend fun hasLabels(): Boolean

    /**
     * Retire de `label_count` les étiquettes des médias [ids] (au plus [MAX_IDS]) présents dans
     * l'index et hors corbeille, lues depuis leurs étiquettes (clé primaire) ; « + » : SQLite, sans
     * statistiques, partirait sinon des médias hors corbeille, toute la timeline à chaque lot.
     */
    @Query(
        """
        INSERT OR REPLACE INTO label_count (label, count)
        SELECT t.label, COALESCE(c.count, 0) - t.n FROM (
            SELECT l.label AS label, COUNT(*) AS n FROM media_label l JOIN media m ON m.id = l.media_id
            WHERE l.media_id IN (:ids) AND +m.is_trashed = 0
            GROUP BY l.label
        ) t LEFT JOIN label_count c ON c.label = t.label
        """,
    )
    suspend fun uncountLabels(ids: List<Long>)

    /** Ajoute à `label_count` les étiquettes des médias [ids] visibles, comme [uncountLabels]. */
    @Query(
        """
        INSERT OR REPLACE INTO label_count (label, count)
        SELECT t.label, COALESCE(c.count, 0) + t.n FROM (
            SELECT l.label AS label, COUNT(*) AS n FROM media_label l JOIN media m ON m.id = l.media_id
            WHERE l.media_id IN (:ids) AND +m.is_trashed = 0
            GROUP BY l.label
        ) t LEFT JOIN label_count c ON c.label = t.label
        """,
    )
    suspend fun countLabels(ids: List<Long>)

    /**
     * Exécute [change], qui écrit les médias [ids] ou leurs étiquettes, en tenant à jour
     * `label_count` : deux écritures de plus. À appeler dans la transaction de l'écriture. Sans
     * aucune étiquette (première synchronisation), rien n'est compté.
     */
    suspend fun <T> withLabelCounts(ids: Collection<Long>, change: suspend () -> T): T {
        val chunks = ids.distinct().chunked(MAX_IDS)
        if (hasLabels()) chunks.forEach { uncountLabels(it) }
        val result = change()
        if (hasLabels()) chunks.forEach { countLabels(it) }
        return result
    }

    // Recalcul complet (contrôle des tests ; la migration fait de même) : la requête qu'évite
    // `label_count`.

    @Query("DELETE FROM label_count")
    suspend fun clearLabelCounts()

    @Query(
        """
        INSERT INTO label_count (label, count)
        SELECT l.label, COUNT(*) FROM media_label l
        WHERE +l.media_id IN (SELECT id FROM media WHERE is_trashed = 0)
        GROUP BY l.label
        """,
    )
    suspend fun countAllLabels()

    companion object {
        /** Identifiants par requête : la limite de 999 paramètres de SQLite (Android 10). */
        const val MAX_IDS = 500
    }
}
