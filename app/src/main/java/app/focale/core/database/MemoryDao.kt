package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import app.focale.core.model.MediaQuery

/** Média géolocalisé réduit à sa date de prise de vue (ms) et à ses coordonnées. */
data class DatedPoint(val date: Long, val latitude: Double, val longitude: Double)

/** Média géolocalisé d'un voyage, avec son lieu (géocodage hors ligne). */
data class TripPlace(val latitude: Double, val longitude: Double, val place: String?, val country: String?)

/** Personne non masquée présente dans une fenêtre de souvenirs. */
data class PersonPresence(
    @ColumnInfo(name = "person_id") val personId: Long,
    val name: String?,
    /** Médias de la fenêtre où elle figure. */
    val photos: Int,
    /** Mois distincts où elle figure dans la bibliothèque, pour une personne sans nom (0 sinon). */
    val months: Int,
)

/**
 * Lectures des souvenirs : colonnes compactes et agrégats, jamais les médias complets de
 * toute la bibliothèque. Les requêtes viennent de [MemoryQueries].
 */
@Dao
interface MemoryDao {

    @RawQuery
    suspend fun datedPoints(query: SupportSQLiteQuery): List<DatedPoint>

    @RawQuery
    suspend fun tripPlaces(query: SupportSQLiteQuery): List<TripPlace>

    @RawQuery
    suspend fun people(query: SupportSQLiteQuery): List<PersonPresence>
}

/**
 * Requêtes des souvenirs, sur la clause WHERE de [MediaQueryBuilder] : corbeille, dossiers
 * masqués et période s'appliquent exactement comme dans les grilles.
 */
object MemoryQueries {

    /** Médias géolocalisés de [query], par date croissante (détection des voyages). */
    fun datedPoints(query: MediaQuery): SimpleSQLiteQuery = geotagged(
        "m.date_taken AS date, a.latitude AS latitude, a.longitude AS longitude",
        query,
    )

    /** Médias géolocalisés de [query] avec leur lieu, par date croissante (titre d'un voyage). */
    fun tripPlaces(query: MediaQuery): SimpleSQLiteQuery = geotagged(
        "a.latitude AS latitude, a.longitude AS longitude, a.place AS place, a.country AS country",
        query,
    )

    /**
     * Personnes non masquées présentes dans [query], avec leur nombre de médias. Pour une
     * personne sans nom, nombre de mois distincts où elle figure (hors corbeille) : une
     * personne récurrente se distingue ainsi d'un inconnu photographié lors d'un seul événement.
     */
    fun people(query: MediaQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(query, args)
        return SimpleSQLiteQuery(
            """
            SELECT p.id AS person_id, p.name AS name, COUNT(DISTINCT m.id) AS photos,
                   CASE WHEN p.name IS NULL THEN
                       (SELECT COUNT(DISTINCT strftime('%Y-%m', m2.date_taken / 1000, 'unixepoch', 'localtime'))
                        FROM face f2 JOIN media m2 ON m2.id = f2.media_id
                        WHERE f2.person_id = p.id AND m2.is_trashed = 0)
                   ELSE 0 END AS months
            FROM media m JOIN face f ON f.media_id = m.id JOIN person p ON p.id = f.person_id
            WHERE $where AND p.hidden = 0
            GROUP BY p.id
            """.trimIndent(),
            args.toTypedArray(),
        )
    }

    private fun geotagged(columns: String, query: MediaQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(query, args)
        return SimpleSQLiteQuery(
            "SELECT $columns FROM media m JOIN media_analysis a ON a.media_id = m.id " +
                "WHERE $where AND m.date_taken > 0 AND a.latitude IS NOT NULL AND a.longitude IS NOT NULL " +
                "ORDER BY m.date_taken",
            args.toTypedArray(),
        )
    }
}
