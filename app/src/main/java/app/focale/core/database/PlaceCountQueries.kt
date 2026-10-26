package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Query

/** Clé de lieu et nombre d'analyses qui la portent (`place_count`). */
data class PlaceTally(@ColumnInfo(name = "place_key") val placeKey: String, val count: Int)

/**
 * Tenue de `place_count` ([PlaceCountEntity]), partagée par les DAO qui écrivent les analyses
 * ([AnalysisDao]) et les purgent ([MediaDao]) : comme pour `label_count` ([LabelCountQueries]), une
 * écriture est encadrée par [withPlaceCounts], qui retire des comptes les clés des analyses de ses
 * médias avant elle et ajoute celles d'après. Les écritures de plusieurs lieux à la fois (clés
 * recalculées, purge des orphelines) recomptent tout ([recountPlaces]) : quelques millisecondes,
 * par l'index de `place_key`.
 */
interface PlaceCountQueries {

    /**
     * Retire de `place_count` les clés des analyses des médias [ids] (au plus
     * [LabelCountQueries.MAX_IDS]), lues par la clé primaire de `media_analysis`.
     */
    @Query(
        """
        INSERT OR REPLACE INTO place_count (place_key, count)
        SELECT t.place_key, COALESCE(c.count, 0) - t.n FROM (
            SELECT place_key, COUNT(*) AS n FROM media_analysis
            WHERE media_id IN (:ids) AND place_key IS NOT NULL
            GROUP BY place_key
        ) t LEFT JOIN place_count c ON c.place_key = t.place_key
        """,
    )
    suspend fun uncountPlaces(ids: List<Long>)

    /** Ajoute à `place_count` les clés des analyses des médias [ids], comme [uncountPlaces]. */
    @Query(
        """
        INSERT OR REPLACE INTO place_count (place_key, count)
        SELECT t.place_key, COALESCE(c.count, 0) + t.n FROM (
            SELECT place_key, COUNT(*) AS n FROM media_analysis
            WHERE media_id IN (:ids) AND place_key IS NOT NULL
            GROUP BY place_key
        ) t LEFT JOIN place_count c ON c.place_key = t.place_key
        """,
    )
    suspend fun countPlaces(ids: List<Long>)

    /**
     * Exécute [change], qui écrit les analyses des médias [ids], en tenant à jour `place_count` :
     * deux écritures de plus. À appeler dans la transaction de l'écriture.
     */
    suspend fun <T> withPlaceCounts(ids: Collection<Long>, change: suspend () -> T): T {
        val chunks = ids.distinct().chunked(LabelCountQueries.MAX_IDS)
        chunks.forEach { uncountPlaces(it) }
        val result = change()
        chunks.forEach { countPlaces(it) }
        return result
    }

    @Query("DELETE FROM place_count")
    suspend fun clearPlaceCounts()

    /** Toutes les clés, dans l'ordre de leur index, sans tri. */
    @Query(
        """
        INSERT INTO place_count (place_key, count)
        SELECT place_key, COUNT(*) FROM media_analysis WHERE place_key IS NOT NULL GROUP BY place_key
        """,
    )
    suspend fun countAllPlaces()

    /** Recalcule `place_count` depuis toutes les analyses ; à appeler dans une transaction. */
    suspend fun recountPlaces() {
        clearPlaceCounts()
        countAllPlaces()
    }
}
