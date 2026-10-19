package app.focale.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaSort
import app.focale.core.model.SearchTerm
import app.focale.core.model.SortField

/**
 * Traduit une [MediaQuery] en SQL pour Room.
 *
 * Toutes les requêtes partagent la même clause WHERE, ce qui garantit que la grille,
 * le compteur, l'histogramme de dates et la position dans la visionneuse décrivent
 * exactement le même ensemble de médias.
 */
object MediaQueryBuilder {

    /** Sélection complète, triée, utilisable comme PagingSource. */
    fun select(query: MediaQuery, limit: Int? = null): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        val limitClause = limit?.let { " LIMIT $it" }.orEmpty()
        return SimpleSQLiteQuery(
            "SELECT m.* FROM media m WHERE $where ORDER BY ${orderBy(query.sort)}$limitClause",
            args.toTypedArray(),
        )
    }

    /**
     * Couverture représentative : de préférence une photo (ni capture ni GIF), la plus
     * nette selon l'analyse, puis la plus récente.
     */
    fun bestCover(query: MediaQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        return SimpleSQLiteQuery(
            "SELECT m.* FROM media m LEFT JOIN media_analysis a ON a.media_id = m.id WHERE $where " +
                "ORDER BY (m.media_type = $MEDIA_TYPE_IMAGE AND m.is_screenshot = 0 AND m.is_gif = 0) DESC, " +
                "COALESCE(a.blur_score, 0) DESC, m.date_taken DESC LIMIT 1",
            args.toTypedArray(),
        )
    }

    /** Identifiants seuls, dans l'ordre de tri (« tout sélectionner »). */
    fun ids(query: MediaQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        return SimpleSQLiteQuery("SELECT m.id FROM media m WHERE $where ORDER BY ${orderBy(query.sort)}", args.toTypedArray())
    }

    fun count(query: MediaQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM media m WHERE $where", args.toTypedArray())
    }

    /**
     * Nombre de médias placés avant [anchor] dans l'ordre de tri : c'est l'index de
     * [anchor] dans la liste paginée.
     */
    fun position(query: MediaQuery, anchor: MediaEntity): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        val (column, value) = sortValue(query.sort.field, anchor)
        val cmp = if (query.sort.ascending) "<" else ">"
        val before = "($column $cmp ? OR ($column = ? AND m.id $cmp ?))"
        args += value
        args += value
        args += anchor.id
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM media m WHERE $where AND $before", args.toTypedArray())
    }

    /**
     * Nombre de périodes distinctes (au niveau [level]) strictement antérieures à [anchor]
     * dans l'ordre chronologique décroissant : sert à convertir un index de média en
     * index de grille (les en-têtes occupent une case chacun).
     */
    fun periodsBefore(query: MediaQuery, anchor: MediaEntity, level: GroupLevel): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        val period = periodExpression(level)
        val cmp = if (query.sort.ascending) "<" else ">"
        args += anchor.dateTaken
        return SimpleSQLiteQuery(
            "SELECT COUNT(DISTINCT $period) FROM media m WHERE $where AND " +
                "$period $cmp (SELECT ${period.replace("m.date_taken", "?")})",
            args.toTypedArray(),
        )
    }

    /** Histogramme des périodes (pour la bulle de la barre de défilement rapide). */
    fun histogram(query: MediaQuery, level: GroupLevel): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        val period = periodExpression(level)
        val direction = if (query.sort.ascending) "ASC" else "DESC"
        return SimpleSQLiteQuery(
            "SELECT MIN(m.date_taken) AS period_start, COUNT(*) AS count FROM media m " +
                "WHERE $where GROUP BY $period ORDER BY $period $direction",
            args.toTypedArray(),
        )
    }

    /** Albums dossiers avec leur couverture (média le plus récent). */
    fun buckets(query: MediaQuery = MediaQuery()): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query.copy(bucketId = null), args)
        return SimpleSQLiteQuery(
            """
            SELECT m.bucket_id AS bucket_id, m.bucket_name AS bucket_name,
                   m.relative_path AS relative_path, COUNT(*) AS count,
                   MAX(m.date_taken) AS latest,
                   (SELECT c.id FROM media c WHERE c.bucket_id = m.bucket_id AND c.is_trashed = 0
                    ORDER BY c.date_taken DESC, c.id DESC LIMIT 1) AS cover_id
            FROM media m WHERE $where
            GROUP BY m.bucket_id ORDER BY latest DESC
            """.trimIndent(),
            args.toTypedArray(),
        )
    }

    /** Clause WHERE commune ; les arguments positionnels sont ajoutés à [args]. */
    fun where(query: MediaQuery, args: MutableList<Any?>): String {
        val clauses = mutableListOf("m.is_trashed = 0")
        when (query.filter) {
            MediaFilter.ALL -> Unit
            MediaFilter.PHOTOS -> clauses += "m.media_type = $MEDIA_TYPE_IMAGE"
            MediaFilter.VIDEOS -> clauses += "m.media_type = $MEDIA_TYPE_VIDEO"
            MediaFilter.SCREENSHOTS -> clauses += "m.is_screenshot = 1"
            MediaFilter.GIFS -> clauses += "m.is_gif = 1"
        }
        query.bucketId?.let {
            clauses += "m.bucket_id = ?"
            args += it
        }
        if (query.excludedBuckets.isNotEmpty()) {
            clauses += "m.bucket_id NOT IN (${query.excludedBuckets.joinToString(",")})"
        }
        query.albumId?.let {
            clauses += "m.id IN (SELECT media_id FROM album_media WHERE album_id = ?)"
            args += it
        }
        if (query.favoritesOnly) clauses += "m.id IN (SELECT media_id FROM favorite)"
        if (query.geotaggedOnly) {
            clauses += "m.id IN (SELECT media_id FROM media_analysis WHERE latitude IS NOT NULL)"
        }
        query.area?.let { area ->
            clauses += "m.id IN (SELECT media_id FROM media_analysis " +
                "WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?)"
            args += area.south
            args += area.north
            args += area.west
            args += area.east
        }
        query.dateFrom?.let {
            clauses += "m.date_taken >= ?"
            args += it
        }
        query.dateTo?.let {
            clauses += "m.date_taken < ?"
            args += it
        }
        query.month?.let {
            clauses += "strftime('%m', m.date_taken / 1000, 'unixepoch', 'localtime') = ?"
            args += it.toString().padStart(2, '0')
        }
        query.mediaIds?.let { ids ->
            // Identifiants numériques : insérés littéralement pour éviter la limite de
            // 999 paramètres de SQLite sur les anciennes versions d'Android.
            clauses += if (ids.isEmpty()) "0" else "m.id IN (${ids.joinToString(",")})"
        }
        query.terms.forEach { term -> clauses += termClause(term, args) }
        return clauses.joinToString(" AND ")
    }

    private fun termClause(term: SearchTerm, args: MutableList<Any?>): String {
        val alternatives = mutableListOf<String>()
        if (term.labels.isNotEmpty()) {
            alternatives += "m.id IN (SELECT media_id FROM media_label WHERE label IN " +
                "(${term.labels.joinToString(",") { "?" }}))"
            args.addAll(term.labels)
        }
        term.place?.takeIf { it.isNotBlank() }?.let { place ->
            if (term.exactPlace) {
                alternatives += "m.id IN (SELECT media_id FROM media_analysis WHERE place_key = ?)"
                args += place
            } else {
                // Préfixe d'un mot du lieu : « malo » trouve « saint malo », « nice » ne trouve pas « venice ».
                alternatives += "m.id IN (SELECT media_id FROM media_analysis WHERE (' ' || place_key) LIKE ? ESCAPE '\\')"
                args += "% " + likePattern(place).removePrefix("%")
            }
        }
        if (term.countries.isNotEmpty()) {
            alternatives += "m.id IN (SELECT media_id FROM media_analysis WHERE country IN " +
                "(${term.countries.joinToString(",") { "?" }}))"
            args.addAll(term.countries)
        }
        term.text?.takeIf { it.isNotBlank() }?.let { text ->
            alternatives += "m.display_name LIKE ? ESCAPE '\\'"
            args += likePattern(text)
        }
        return if (alternatives.isEmpty()) "0" else alternatives.joinToString(" OR ", "(", ")")
    }

    fun orderBy(sort: MediaSort): String {
        val direction = if (sort.ascending) "ASC" else "DESC"
        return when (sort.field) {
            SortField.DATE_TAKEN -> "m.date_taken $direction, m.id $direction"
            SortField.DATE_MODIFIED -> "m.date_modified $direction, m.id $direction"
            SortField.NAME -> "m.display_name COLLATE NOCASE $direction, m.id $direction"
            SortField.SIZE -> "m.size $direction, m.id $direction"
        }
    }

    private fun sortValue(field: SortField, anchor: MediaEntity): Pair<String, Any> = when (field) {
        SortField.DATE_TAKEN -> "m.date_taken" to anchor.dateTaken
        SortField.DATE_MODIFIED -> "m.date_modified" to anchor.dateModified
        SortField.NAME -> "m.display_name COLLATE NOCASE" to anchor.displayName
        SortField.SIZE -> "m.size" to anchor.size
    }

    /** Expression SQL identifiant la période (jour, mois ou année locale) d'un média. */
    fun periodExpression(level: GroupLevel): String {
        val format = when (level) {
            GroupLevel.DAY -> "%Y-%m-%d"
            GroupLevel.MONTH -> "%Y-%m"
            GroupLevel.YEAR -> "%Y"
        }
        return "strftime('$format', m.date_taken / 1000, 'unixepoch', 'localtime')"
    }

    /** Motif LIKE « contient », avec échappement des caractères spéciaux. */
    fun likePattern(text: String): String {
        val escaped = text.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    const val MEDIA_TYPE_IMAGE = 1
    const val MEDIA_TYPE_VIDEO = 3
}
