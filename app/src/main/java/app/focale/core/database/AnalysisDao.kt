package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Étiquette et nombre de médias qui la portent, avec un média représentatif. */
data class LabelStat(
    val label: String,
    val count: Int,
    @ColumnInfo(name = "cover_id") val coverId: Long?,
)

/** Lieu et nombre de médias pris sur place, avec le plus récent. */
data class PlaceStat(
    val place: String,
    val country: String,
    val count: Int,
    @ColumnInfo(name = "cover_id") val coverId: Long,
)

data class HashRow(val id: Long, val phash: Long, val size: Long, val width: Int, val height: Int)

/** Média géolocalisé, tel qu'affiché sur la carte. */
data class GeoPhoto(
    @ColumnInfo(name = "media_id") val mediaId: Long,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "media_type") val mediaType: Int,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    val place: String?,
    /** Pays du lieu (ISO 3166-1 alpha-2), pour en traduire le nom. */
    val country: String? = null,
)

/** Lieu de la bibliothèque et sa clé de recherche enregistrée. */
data class PlaceKeyRow(
    val place: String,
    val country: String?,
    @ColumnInfo(name = "place_key") val placeKey: String?,
)

@Dao
interface AnalysisDao : LabelCountQueries, PlaceCountQueries {

    /**
     * Médias à analyser (jamais analysés, modifiés depuis, ou analysés par une ancienne version),
     * les plus récents d'abord ; ordre total (identifiant en second) pour [pendingAfter].
     */
    @Query(
        """
        SELECT m.* FROM media m LEFT JOIN media_analysis a ON a.media_id = m.id
        WHERE m.is_trashed = 0
          AND (a.media_id IS NULL OR a.analyzed_modified != m.date_modified OR a.version < :version)
        ORDER BY m.date_taken DESC, m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun pending(version: Int, limit: Int): List<MediaEntity>

    /**
     * Suite de [pending] après le média ([dateTaken], [id]) d'un lot précédent, dans le même
     * ordre : la borne sur `date_taken` fait partir le parcours de l'index de la timeline de ce
     * média, sans repasser sur les médias déjà analysés (voir `AnalysisQueue`).
     */
    @Query(
        """
        SELECT m.* FROM media m LEFT JOIN media_analysis a ON a.media_id = m.id
        WHERE m.is_trashed = 0
          AND m.date_taken <= :dateTaken AND (m.date_taken < :dateTaken OR m.id < :id)
          AND (a.media_id IS NULL OR a.analyzed_modified != m.date_modified OR a.version < :version)
        ORDER BY m.date_taken DESC, m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun pendingAfter(version: Int, dateTaken: Long, id: Long, limit: Int): List<MediaEntity>

    /**
     * Nombre de médias à analyser. Toute la table est lue : « + » écarte l'index de la timeline
     * (trié par date) au profit d'un parcours dans l'ordre des identifiants, qui lit `media` et
     * `media_analysis` dans le même ordre, bien plus vite (docs/PERFORMANCE.md). Relu au plus
     * toutes les quelques secondes pendant l'analyse (`AnalysisScheduler`, `sampledQuery`).
     */
    @Query(
        """
        SELECT COUNT(*) FROM media m LEFT JOIN media_analysis a ON a.media_id = m.id
        WHERE +m.is_trashed = 0
          AND (a.media_id IS NULL OR a.analyzed_modified != m.date_modified OR a.version < :version)
        """,
    )
    suspend fun pendingCount(version: Int): Int

    /** Enregistre [analysis], reportées dans les comptes des lieux (`place_count`). */
    @Transaction
    suspend fun upsert(analysis: List<MediaAnalysisEntity>) = withPlaceCounts(analysis.map { it.mediaId }) {
        upsertRows(analysis)
    }

    /** Lignes seules : passer par [upsert]. */
    @Upsert
    suspend fun upsertRows(analysis: List<MediaAnalysisEntity>)

    @Query("DELETE FROM media_label WHERE media_id IN (:mediaIds)")
    suspend fun deleteLabels(mediaIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabels(labels: List<MediaLabelEntity>)

    /**
     * Enregistre un lot d'analyses et remplace les étiquettes correspondantes, reportées dans
     * `label_count` : une écriture de plus par lot, pour ne plus relire toutes les étiquettes ; les
     * lieux, dans `place_count` ([upsert]).
     */
    @Transaction
    suspend fun save(analysis: List<MediaAnalysisEntity>, labels: List<MediaLabelEntity>) =
        withLabelCounts(analysis.map { it.mediaId } + labels.map { it.mediaId }) {
            deleteLabels(analysis.map { it.mediaId })
            upsert(analysis)
            insertLabels(labels)
        }

    /** Recalcule `label_count` depuis toutes les étiquettes (contrôle des tests, [checkCounts]). */
    @Transaction
    suspend fun rebuildLabelCounts() {
        clearLabelCounts()
        countAllLabels()
    }

    /** Recalcule `place_count` depuis toutes les analyses (contrôle des tests, [checkCounts]). */
    @Transaction
    suspend fun rebuildPlaceCounts() = recountPlaces()

    @Query("SELECT COALESCE(SUM(count), 0) FROM label_count")
    suspend fun labelCountTotal(): Long

    /**
     * Étiquettes des médias de l'index hors corbeille, ce que totalisent les comptes de
     * `label_count` : l'index de la clé primaire de `media_label` seul, le média de chacune par la
     * sienne (« + » écarte l'index de la corbeille), sans regroupement : 20 ms pour 157 000
     * étiquettes, contre 40 à 60 ms en partant des médias.
     */
    @Query("SELECT COUNT(*) FROM media m JOIN media_label l ON l.media_id = m.id WHERE +m.is_trashed = 0")
    suspend fun visibleLabelTotal(): Long

    @Query("SELECT COALESCE(SUM(count), 0) FROM place_count")
    suspend fun placeCountTotal(): Long

    /** Analyses avec un lieu, ce que totalisent les comptes de `place_count` : l'index de `place_key` seul. */
    @Query("SELECT COUNT(*) FROM media_analysis WHERE place_key IS NOT NULL")
    suspend fun placedTotal(): Long

    /**
     * Garde-fou des comptes tenus à jour (`label_count`, `place_count`), justes tant que toutes les
     * écritures passent par les méthodes prévues ([withLabelCounts], [withPlaceCounts]) : le total
     * de chacun est comparé au nombre de lignes qu'il compte, et la table est recalculée s'ils
     * diffèrent. Appelé au début de chaque passage d'analyse ; renvoie les tables recalculées.
     * Un écart qui se compenserait (une étiquette de trop, une autre de moins) passerait inaperçu.
     */
    @Transaction
    suspend fun checkCounts(): List<String> = buildList {
        if (labelCountTotal() != visibleLabelTotal()) {
            rebuildLabelCounts()
            add("label_count")
        }
        if (placeCountTotal() != placedTotal()) {
            recountPlaces()
            add("place_count")
        }
    }

    @Query("SELECT label, count FROM label_count WHERE count != 0 ORDER BY label")
    suspend fun labelCounts(): List<LabelTally>

    @Query("SELECT place_key, count FROM place_count WHERE count != 0 ORDER BY place_key")
    suspend fun placeCounts(): List<PlaceTally>

    @Query("SELECT * FROM media_analysis WHERE media_id = :mediaId")
    fun observe(mediaId: Long): Flow<MediaAnalysisEntity?>

    @Query("SELECT label FROM media_label WHERE media_id = :mediaId ORDER BY confidence DESC")
    fun observeLabels(mediaId: Long): Flow<List<String>>

    /**
     * Les [limit] étiquettes les plus présentes hors corbeille (onglet Recherche), avec leur photo
     * la plus sûre. Nombres lus dans `label_count`, tenu à jour à chaque écriture : compter les
     * 150 000 lignes de `media_label` en vérifiant le média de chacune coûtait 90 ms. Couverture
     * cherchée pour les étiquettes retenues seulement : la première de l'index (`label`,
     * `confidence`, `media_id`) par confiance décroissante (docs/PERFORMANCE.md). À égalité, par nom.
     */
    @Query(
        """
        SELECT s.label AS label, s.count AS count,
               (SELECT l2.media_id FROM media_label l2 JOIN media m2 ON m2.id = l2.media_id
                WHERE l2.label = s.label AND m2.is_trashed = 0
                ORDER BY l2.confidence DESC, m2.date_taken DESC LIMIT 1) AS cover_id
        FROM (SELECT label, count FROM label_count WHERE count > 0 ORDER BY count DESC, label LIMIT :limit) s
        ORDER BY s.count DESC, s.label
        """,
    )
    suspend fun labelStats(limit: Int): List<LabelStat>

    @Query(
        """
        SELECT a.place AS place, a.country AS country, COUNT(*) AS count,
               (SELECT a2.media_id FROM media_analysis a2 JOIN media m2 ON m2.id = a2.media_id
                WHERE a2.place = a.place AND a2.country = a.country AND m2.is_trashed = 0
                ORDER BY m2.date_taken DESC LIMIT 1) AS cover_id
        FROM media_analysis a JOIN media m ON m.id = a.media_id
        WHERE m.is_trashed = 0 AND a.place IS NOT NULL AND a.country IS NOT NULL
        GROUP BY a.place, a.country
        ORDER BY count DESC
        LIMIT :limit
        """,
    )
    suspend fun placeStats(limit: Int): List<PlaceStat>

    /** Empreintes des photos (détection de doublons). */
    @Query(
        """
        SELECT a.media_id AS id, a.phash AS phash, m.size AS size, m.width AS width, m.height AS height
        FROM media_analysis a JOIN media m ON m.id = a.media_id
        WHERE m.is_trashed = 0 AND m.media_type = 1 AND a.phash IS NOT NULL
        """,
    )
    suspend fun hashes(): List<HashRow>

    @Query(
        """
        SELECT m.* FROM media m JOIN media_analysis a ON a.media_id = m.id
        WHERE m.is_trashed = 0 AND m.media_type = 1 AND m.is_screenshot = 0
          AND a.blur_score IS NOT NULL AND a.blur_score < :threshold
        ORDER BY a.blur_score ASC
        """,
    )
    fun observeBlurry(threshold: Float): Flow<List<MediaEntity>>

    @Query(
        """
        SELECT a.media_id AS media_id, a.latitude AS latitude, a.longitude AS longitude,
               m.media_type AS media_type, m.date_taken AS date_taken,
               m.date_modified AS date_modified, a.place AS place, a.country AS country
        FROM media_analysis a JOIN media m ON m.id = a.media_id
        WHERE m.is_trashed = 0 AND a.latitude IS NOT NULL AND a.longitude IS NOT NULL
        ORDER BY m.date_taken DESC
        """,
    )
    fun observeGeoPhotos(): Flow<List<GeoPhoto>>

    @Query("SELECT place, country, place_key FROM media_analysis WHERE place IS NOT NULL GROUP BY place, country, place_key")
    suspend fun placeKeys(): List<PlaceKeyRow>

    /** Lignes seules : passer par [refreshPlaceKeys], qui recompte les lieux. */
    @Query("UPDATE media_analysis SET place_key = :key WHERE place = :place AND country IS :country")
    suspend fun updatePlaceKey(place: String, country: String?, key: String)

    /**
     * Recalcule les clés de recherche des lieux déjà analysés ([keyOf] : nom et pays → clé) qui
     * diffèrent, par exemple quand la base embarquée gagne des noms traduits : sans nouvelle
     * analyse des médias. Renvoie le nombre de lieux mis à jour.
     */
    @Transaction
    suspend fun refreshPlaceKeys(keyOf: (String, String?) -> String): Int {
        var updated = 0
        for (row in placeKeys()) {
            val key = keyOf(row.place, row.country)
            if (key != row.placeKey) {
                updatePlaceKey(row.place, row.country, key)
                updated++
            }
        }
        // Clés de tous les médias d'un lieu changées d'un coup : comptes des lieux recalculés.
        if (updated > 0) recountPlaces()
        return updated
    }
}
