package app.focale.core.database

import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaSort
import app.focale.core.model.SearchTerm
import app.focale.core.model.SortField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaQueryBuilderTest {

    @Test
    fun `timeline exclut la corbeille et trie par date décroissante`() {
        val query = MediaQueryBuilder.select(MediaQuery())
        assertTrue(query.sql.contains("m.is_trashed = 0"))
        assertTrue(query.sql.endsWith("ORDER BY m.date_taken DESC, m.id DESC"))
        assertEquals(0, query.argCount)
    }

    @Test
    fun `timeline, dossiers et filtres de type ou de date ne lisent que la table media`() {
        assertTrue(MediaQueryBuilder.readsOnlyMedia(MediaQuery.Timeline))
        assertTrue(
            MediaQueryBuilder.readsOnlyMedia(
                MediaQuery(filter = MediaFilter.VIDEOS, bucketId = 4L, excludedBuckets = listOf(1L, 2L), dateFrom = 0L, month = 7, mediaIds = listOf(9L)),
            ),
        )
        // Favoris, albums, lieux et recherche lisent d'autres tables : leurs grilles les suivent.
        assertFalse(MediaQueryBuilder.readsOnlyMedia(MediaQuery(favoritesOnly = true)))
        assertFalse(MediaQueryBuilder.readsOnlyMedia(MediaQuery(albumId = 3L)))
        assertFalse(MediaQueryBuilder.readsOnlyMedia(MediaQuery(geotaggedOnly = true)))
        assertFalse(MediaQueryBuilder.readsOnlyMedia(MediaQuery(personId = 5L)))
        assertFalse(MediaQueryBuilder.readsOnlyMedia(MediaQuery(terms = listOf(SearchTerm(labels = listOf("Dog"))))))
    }

    @Test
    fun `filtres de type et d'album produisent les clauses attendues`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(
            MediaQuery(filter = MediaFilter.VIDEOS, bucketId = 42L, favoritesOnly = true),
            args,
        )
        assertTrue(where.contains("m.media_type = 3"))
        assertTrue(where.contains("m.bucket_id = ?"))
        assertTrue(where.contains("m.id IN (SELECT media_id FROM favorite)"))
        assertEquals(listOf<Any?>(42L), args)
    }

    @Test
    fun `un terme de recherche combine étiquettes, lieu et nom de fichier par OU`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(
            MediaQuery(
                terms = listOf(
                    SearchTerm(labels = listOf("Dog", "Cat"), place = "paris", countries = listOf("FR"), text = "IMG_1"),
                ),
            ),
            args,
        )
        assertTrue(where.contains("label IN (?,?)"))
        // Lieu : parmi les clés distinctes, puis les médias de ces clés par leur index.
        assertTrue(where, where.contains("place_key IN (SELECT place_key FROM place_count WHERE count > 0 AND (' ' || place_key) LIKE ?"))
        assertTrue(where.contains("country IN (?)"))
        assertTrue(where.contains(" OR "))
        // Texte reconnu : débuts de mots par l'index plein texte ; repli sur LIKE si rien d'autre ne
        // répond au terme (mêmes voies, sur les médias hors corbeille).
        assertTrue(where, where.contains("m.id IN (SELECT docid FROM media_text_fts WHERE media_text_fts MATCH ?)"))
        assertTrue(where, where.contains("(SELECT 1 WHERE NOT EXISTS (SELECT 1 FROM media o WHERE +o.is_trashed = 0 AND (o.id IN (SELECT media_id FROM media_label"))
        assertTrue(where, where.contains("o.id IN (SELECT docid FROM media_text_fts WHERE media_text_fts MATCH ?)))) g CROSS JOIN media_text t WHERE t.text_key LIKE ?"))
        val others = listOf<Any?>("Dog", "Cat", "% paris%", "FR", "%IMG\\_1%", "\"img 1*\"")
        assertEquals(others + others + "%img 1%", args)
    }

    @Test
    fun `personne et résultats sémantiques restreignent la sélection`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(
            MediaQuery(personId = 5L, terms = listOf(SearchTerm(similarIds = listOf(3L, 9L)))),
            args,
        )
        assertTrue(where.contains("m.id IN (SELECT media_id FROM face WHERE person_id = ?)"))
        assertTrue(where.contains("(m.id IN (3,9))"))
        assertEquals(listOf<Any?>(5L), args)
    }

    @Test
    fun `les personnes d'un terme sont une alternative de plus, sans paramètre positionnel`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(
            MediaQuery(terms = listOf(SearchTerm(labels = listOf("Beach"), text = "marie", personIds = listOf(4L, 12L)))),
            args,
        )
        assertTrue(where.contains("m.id IN (SELECT media_id FROM face WHERE person_id IN (4,12))"))
        // Étiquettes, nom de fichier, texte reconnu ou personne : une seule voie suffit.
        assertTrue(
            where,
            where.contains(
                "label IN (?)) OR (m.id IN (SELECT rowid FROM media_name WHERE media_name MATCH ? UNION ALL SELECT media_id FROM media_name_pending) " +
                    "AND m.display_name LIKE ?",
            ),
        )
        assertTrue(where.contains(" OR m.id IN (SELECT media_id FROM face WHERE person_id IN (4,12)))"))
        val others = listOf<Any?>("Beach", "\"marie*\"", "%marie%", "\"marie*\"")
        assertEquals(others + others + "%marie%", args)
    }

    @Test
    fun `un mot de lettres est cherché dans les noms par l'index plein texte, revérifié par LIKE`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(MediaQuery(terms = listOf(SearchTerm(text = " Facture "))), args)
        // Médias dont les mots du nom attendent la fin de la première indexation : candidats aussi.
        assertTrue(
            where,
            where.contains(
                "(m.id IN (SELECT rowid FROM media_name WHERE media_name MATCH ? UNION ALL SELECT media_id FROM media_name_pending) " +
                    "AND m.display_name LIKE ? ESCAPE '\\')",
            ),
        )
        val others = listOf<Any?>("\"Facture*\"", "%Facture%", "\"facture*\"")
        assertEquals(others + others + "%facture%", args)
        // Chiffre, séparateur ou lettre non ASCII : LIKE sur chaque nom, comme avant.
        for (text in listOf("img2024", "img_1", "été")) {
            val other = MediaQueryBuilder.where(MediaQuery(terms = listOf(SearchTerm(text = text))), mutableListOf())
            assertFalse(other, other.contains("media_name"))
            assertTrue(other, other.contains("m.display_name LIKE ? ESCAPE"))
        }
    }

    @Test
    fun `un terme réduit à une personne ne garde que ses photos`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(MediaQuery(terms = listOf(SearchTerm(personIds = listOf(9L)))), args)
        assertTrue(where.endsWith(" AND (m.id IN (SELECT media_id FROM face WHERE person_id IN (9)))"))
        assertTrue(args.isEmpty())
    }

    @Test
    fun `le filtre de mois borne le mois de chaque année en Kotlin, le mois de SQLite hors de ces années`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(MediaQuery(month = 7), args)
        // Années de FIRST_MONTH_YEAR à LAST_MONTH_YEAR : dichotomie sur les bornes, sans strftime.
        val (outside, inside) = where.substringAfter("CASE WHEN ").split(" THEN ", limit = 2)
        assertTrue(outside, outside.startsWith("m.date_taken < ") && " OR m.date_taken >= " in outside)
        assertTrue(inside, inside.startsWith("strftime('%m'") && "strftime" !in inside.substringAfter(" ELSE "))
        assertEquals(listOf<Any?>("07"), args)
        assertTrue(MediaQueryBuilder.readsOnlyMedia(MediaQuery(month = 7)))
    }

    @Test
    fun `un terme vide ne correspond à rien`() {
        val where = MediaQueryBuilder.where(MediaQuery(terms = listOf(SearchTerm())), mutableListOf())
        assertTrue(where.endsWith(" AND 0"))
    }

    @Test
    fun `les identifiants explicites sont insérés littéralement`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(MediaQuery(mediaIds = listOf(3L, 5L, 8L)), args)
        assertTrue(where.contains("m.id IN (3,5,8)"))
        assertTrue(args.isEmpty())
    }

    @Test
    fun `position compte les médias placés avant l'ancre`() {
        val anchor = entity(id = 10, dateTaken = 1_000L)
        val descending = MediaQueryBuilder.position(MediaQuery(), anchor)
        assertTrue(descending.sql.contains("(m.date_taken > ? OR (m.date_taken = ? AND m.id > ?))"))
        assertEquals(3, descending.argCount)

        val ascending = MediaQueryBuilder.position(
            MediaQuery(sort = MediaSort(SortField.NAME, ascending = true)),
            anchor,
        )
        assertTrue(ascending.sql.contains("m.display_name COLLATE NOCASE < ?"))
    }

    @Test
    fun `l'histogramme compte chaque période par sa plage de dates, bornes calculées en Kotlin`() {
        val sql = MediaQueryBuilder.histogram(MediaQuery(), listOf(0L, 100L, 250L)).sql
        assertTrue(sql, sql.contains("VALUES (0,100),(100,250)"))
        assertTrue(sql, sql.contains("m.date_taken >= p.column1 AND m.date_taken < p.column2"))
        assertFalse(sql, sql.contains("strftime"))
    }

    @Test
    fun `les périodes sondées sont des suites déroulées par une table récursive`() {
        val day = 86_400_000L
        // Jours de 24 heures, un jour de 23 heures (changement d'heure), puis un trou (mois écarté).
        val periods = listOf(0L to day, day to 2 * day, 2 * day to 3 * day, 3 * day to 4 * day - 3_600_000, 10 * day to 11 * day)
        val runs = MediaQueryBuilder.periodRuns(periods)
        assertEquals(
            listOf(
                MediaQueryBuilder.PeriodRun(0, day, 3),
                MediaQueryBuilder.PeriodRun(3 * day, day - 3_600_000, 1),
                MediaQueryBuilder.PeriodRun(10 * day, day, 1),
            ),
            runs,
        )
        val sql = MediaQueryBuilder.periodsWithMedia(MediaQuery(), runs).sql
        assertTrue(sql, sql.startsWith("WITH RECURSIVE r(s, step, n) AS (VALUES (0,$day,3),"))
        assertTrue(sql, sql.contains("WHERE EXISTS (SELECT 1 FROM media m WHERE m.is_trashed = 0 AND m.date_taken >= d.s + d.k * d.step"))
        assertTrue(MediaQueryBuilder.nonEmptyPeriods(MediaQuery(), runs).sql.contains("SELECT d.s + d.k * d.step AS period_start FROM d"))
    }

    @Test
    fun `le nombre de résultats d'une recherche part de ses critères`() {
        // Timeline : par l'index de la corbeille et des dates ; recherche : sans lui (« + »).
        assertTrue(MediaQueryBuilder.count(MediaQuery()).sql.contains("WHERE m.is_trashed = 0"))
        val search = MediaQueryBuilder.count(MediaQuery(terms = listOf(SearchTerm(labels = listOf("Dog"))))).sql
        assertTrue(search, search.contains("WHERE +m.is_trashed = 0 AND"))
        // Les pages de la recherche gardent l'ordre de l'index des dates.
        assertTrue(MediaQueryBuilder.select(MediaQuery(terms = listOf(SearchTerm(labels = listOf("Dog"))))).sql.contains("WHERE m.is_trashed = 0"))
        assertTrue(MediaQueryBuilder.select(MediaQuery(), limit = 120, offset = 360).sql.endsWith("LIMIT 120 OFFSET 360"))
    }

    @Test
    fun `le motif LIKE échappe les caractères spéciaux`() {
        assertEquals("%50\\%\\_off%", MediaQueryBuilder.likePattern(" 50%_off "))
        assertFalse(MediaQueryBuilder.likePattern("a").contains("\\"))
    }

    private fun entity(id: Long, dateTaken: Long) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/", bucketId = 1, bucketName = "Camera", dateTaken = dateTaken,
        dateAdded = dateTaken, dateModified = dateTaken, generation = 0, size = 1, width = 1, height = 1,
        orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = false,
        isGif = false, isTrashed = false, dateExpires = 0,
    )

    @Test
    fun `les dossiers masqués sont exclus sans paramètre positionnel`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(MediaQuery(excludedBuckets = listOf(7L, -12L)), args)
        assertTrue(where.contains("m.bucket_id NOT IN (7,-12)"))
        assertTrue(args.isEmpty())
        assertFalse(MediaQueryBuilder.where(MediaQuery(), mutableListOf()).contains("NOT IN"))
    }

    @Test
    fun `une carte de lieu cherche son nom GeoNames, suivi ou non de ses traductions`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(MediaQuery(terms = listOf(SearchTerm(place = "lisbon", exactPlace = true))), args)
        assertTrue(where.contains("WHERE place_key = ? OR place_key GLOB ?"))
        assertEquals(listOf<Any?>("lisbon", "lisbon | *"), args)
    }
}
