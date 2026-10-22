package app.focale.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaSort
import app.focale.core.model.SearchTerm
import app.focale.core.model.SortField
import app.focale.core.model.TextCriteria
import app.focale.ml.LabelDictionary
import app.focale.ml.PlaceTranslations

/**
 * Traduit une [MediaQuery] en SQL pour Room.
 *
 * Toutes les requêtes partagent la même clause WHERE, ce qui garantit que la grille,
 * le compteur, l'histogramme de dates et la position dans la visionneuse décrivent
 * exactement le même ensemble de médias.
 */
object MediaQueryBuilder {

    /** Sélection complète, triée ; [limit] et [offset] : une page ([MediaPagingSource]). */
    fun select(query: MediaQuery, limit: Int? = null, offset: Int = 0): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        val limitClause = limit?.let { " LIMIT $it" + if (offset > 0) " OFFSET $offset" else "" }.orEmpty()
        return SimpleSQLiteQuery(
            "SELECT m.* FROM media m WHERE $where ORDER BY ${orderBy(query.sort)}$limitClause",
            args.toTypedArray(),
        )
    }

    /**
     * Couverture représentative : de préférence une photo (ni capture ni GIF), la plus
     * nette selon l'analyse, puis la plus récente ; [limit] candidates, dans cet ordre.
     */
    fun bestCover(query: MediaQuery, limit: Int = 1): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        return SimpleSQLiteQuery("SELECT m.* ${coverSource(query, args, limit)}", args.toTypedArray())
    }

    /** Fin de [bestCover] (à partir du FROM), partagée avec [summaries]. */
    private fun coverSource(query: MediaQuery, args: MutableList<Any?>, limit: Int = 1): String =
        "FROM media m LEFT JOIN media_analysis a ON a.media_id = m.id WHERE ${where(query, args)} " +
            "ORDER BY (m.media_type = $MEDIA_TYPE_IMAGE AND m.is_screenshot = 0 AND m.is_gif = 0) DESC, " +
            "COALESCE(a.blur_score, 0) DESC, m.date_taken DESC LIMIT $limit"

    /**
     * Nombre de médias ([count]) et couverture ([bestCover]) de chacune des [queries], en une
     * seule requête : une ligne par requête, `position` étant son rang dans [queries].
     */
    fun summaries(queries: List<MediaQuery>): SimpleSQLiteQuery {
        require(queries.isNotEmpty())
        val args = mutableListOf<Any?>()
        // Les arguments sont ajoutés dans l'ordre où leurs « ? » apparaissent.
        val sql = queries.withIndex().joinToString(" UNION ALL ") { (position, query) ->
            val count = "SELECT COUNT(*) FROM media m WHERE ${where(query, args)}"
            val cover = "SELECT m.id ${coverSource(query, args)}"
            "SELECT $position AS position, ($count) AS count, ($cover) AS cover_id"
        }
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }

    /** Identifiants seuls, dans l'ordre de tri (« tout sélectionner »). */
    fun ids(query: MediaQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        return SimpleSQLiteQuery("SELECT m.id FROM media m WHERE $where ORDER BY ${orderBy(query.sort)}", args.toTypedArray())
    }

    /**
     * Nombre de médias de [query] (compteurs, nombre total des grilles). Timeline, dossiers et
     * filtres : par l'index de leur ordre seul. Avec un critère pris dans une autre table
     * (recherche, personne, album, favoris), « + » écarte l'index de la corbeille, que SQLite croit
     * sélectif faute de statistiques et qui lui faisait parcourir toute la timeline dans l'ordre
     * des dates (lignes lues au hasard) : il part alors des médias du critère (`m.id IN (…)`, par la
     * clé primaire), ou, quand une alternative l'exige (nom de fichier), parcourt `media` dans
     * l'ordre des identifiants (docs/PERFORMANCE.md).
     */
    fun count(query: MediaQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args, trashByIndex = readsOnlyMedia(query))
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
     * Dates extrêmes des médias de [query] (`first`, `last`), chacune par une recherche dans
     * l'index de son ordre : de quoi borner les périodes de [histogram] et [periodsWithMedia].
     */
    fun dateRange(query: MediaQuery): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val first = "SELECT MIN(m.date_taken) FROM media m WHERE ${where(query, args)}"
        val last = "SELECT MAX(m.date_taken) FROM media m WHERE ${where(query, args)}"
        return SimpleSQLiteQuery("SELECT ($first) AS first, ($last) AS last", args.toTypedArray())
    }

    /**
     * Nombre de médias de [query] dans chacune des périodes [bounds] : leurs débuts successifs, puis
     * la fin de la dernière, calculés en Kotlin dans le fuseau courant (`DateGrouping.bounds`, heure
     * d'été comprise). Chaque période est une plage de `date_taken`, comptée par l'index de la
     * requête ; `strftime(…, 'localtime')`, calculé pour chaque média, coûtait 100 ms sur 50 000
     * (docs/PERFORMANCE.md). Une ligne par période, vide comprise (`period_start`, `count`).
     */
    fun histogram(query: MediaQuery, bounds: List<Long>): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        return SimpleSQLiteQuery(
            "SELECT p.column1 AS period_start, (SELECT COUNT(*) FROM media m WHERE $where " +
                "AND m.date_taken >= p.column1 AND m.date_taken < p.column2) AS count FROM (${periods(bounds)}) p",
            args.toTypedArray(),
        )
    }

    /**
     * Nombre de périodes [bounds] (voir [histogram]) qui contiennent au moins un média de [query] :
     * les en-têtes de la grille avant un média, sans lire les médias de chaque période.
     */
    fun periodsWithMedia(query: MediaQuery, bounds: List<Long>): SimpleSQLiteQuery {
        val args = mutableListOf<Any?>()
        val where = where(query, args)
        return SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM (${periods(bounds)}) p WHERE EXISTS (SELECT 1 FROM media m WHERE $where " +
                "AND m.date_taken >= p.column1 AND m.date_taken < p.column2)",
            args.toTypedArray(),
        )
    }

    /**
     * Périodes [bounds] en table de valeurs (`column1` : début, `column2` : fin exclue) ; bornes
     * numériques insérées littéralement (limite de paramètres de SQLite), au plus [MAX_PERIODS].
     */
    private fun periods(bounds: List<Long>): String {
        require(bounds.size in 2..MAX_PERIODS + 1)
        return "VALUES " + bounds.zipWithNext().joinToString(",") { (start, end) -> "($start,$end)" }
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

    /**
     * Vrai si la clause WHERE de [query] ne lit que la table `media` (timeline, dossiers, filtres
     * de type ou de date) : tout autre critère passe par une sous-requête sur sa table. Ses grilles
     * n'ont pas à suivre les tables des favoris, des albums ni de l'analyse.
     */
    fun readsOnlyMedia(query: MediaQuery): Boolean = !where(query, mutableListOf()).contains("SELECT")

    /**
     * Clause WHERE commune ; les arguments positionnels sont ajoutés à [args]. [trashByIndex] faux :
     * la corbeille est écartée sans proposer ses index à SQLite (« + », voir [count]).
     */
    fun where(query: MediaQuery, args: MutableList<Any?>, trashByIndex: Boolean = true): String {
        val clauses = mutableListOf(if (trashByIndex) "m.is_trashed = 0" else "+m.is_trashed = 0")
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
        query.personId?.let {
            clauses += "m.id IN (SELECT media_id FROM face WHERE person_id = ?)"
            args += it
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
                // Clé du nom GeoNames, seule ou suivie de ses traductions (« lisbon | lisbonne »).
                alternatives += "m.id IN (SELECT media_id FROM media_analysis WHERE place_key = ? OR place_key GLOB ?)"
                args += place
                args += place + PlaceTranslations.SEPARATOR + "*"
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
            // Texte reconnu dans la photo, comparé sous forme normalisée (sans accents).
            alternatives += "m.id IN (SELECT media_id FROM media_text WHERE text_key LIKE ? ESCAPE '\\')"
            args += likePattern(LabelDictionary.normalize(text))
        }
        term.recognizedText?.let { alternatives += recognizedTextClause(it, args) }
        if (term.similarIds.isNotEmpty()) {
            // Identifiants numériques insérés littéralement (limite de paramètres SQLite).
            alternatives += "m.id IN (${term.similarIds.joinToString(",")})"
        }
        if (term.personIds.isNotEmpty()) {
            alternatives += "m.id IN (SELECT media_id FROM face WHERE person_id IN (${term.personIds.joinToString(",")}))"
        }
        return if (alternatives.isEmpty()) "0" else alternatives.joinToString(" OR ", "(", ")")
    }

    /**
     * Photo (hors capture d'écran) dont le texte reconnu répond à [criteria]. Chaque mot-clé
     * est cherché entier dans la clé normalisée (espaces simples entre les mots), un symbole
     * dans le texte brut ; les correspondances (0 ou 1 en SQLite) s'additionnent.
     */
    private fun recognizedTextClause(criteria: TextCriteria, args: MutableList<Any?>): String {
        var condition = "length(text_key) >= ?"
        args += criteria.minLength
        // Un mot n'est compté qu'une fois, quelle que soit sa graphie (« Reçu », « recu »).
        val keywords = criteria.keywords.map(String::trim).filter(String::isNotEmpty)
            .distinctBy { LabelDictionary.normalize(it).ifEmpty { it } }
        if (keywords.isNotEmpty()) {
            val hits = keywords.joinToString(" + ") { keyword ->
                val key = LabelDictionary.normalize(keyword)
                if (key.isEmpty()) {
                    args += likePattern(keyword)
                    "(text LIKE ? ESCAPE '\\')"
                } else {
                    // Clé faite de [a-z0-9 ] : aucun caractère spécial de LIKE à échapper.
                    args += "% $key %"
                    "((' ' || text_key || ' ') LIKE ?)"
                }
            }
            condition += " AND ($hits) >= ?"
            args += criteria.minKeywords
        }
        return "(m.is_screenshot = 0 AND m.id IN (SELECT media_id FROM media_text WHERE $condition))"
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

    /** Périodes par requête de [histogram] et [periodsWithMedia] (500 jours, 41 ans de mois). */
    const val MAX_PERIODS = 500
}
