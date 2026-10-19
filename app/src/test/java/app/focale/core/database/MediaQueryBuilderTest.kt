package app.focale.core.database

import app.focale.core.model.GroupLevel
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
        assertTrue(where.contains("(' ' || place_key) LIKE ?"))
        assertTrue(where.contains("country IN (?)"))
        assertTrue(where.contains(" OR "))
        assertTrue(where.contains("SELECT media_id FROM media_text WHERE text_key LIKE ?"))
        assertEquals(listOf<Any?>("Dog", "Cat", "% paris%", "FR", "%IMG\\_1%", "%img 1%"), args)
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
        assertTrue(where.contains("label IN (?)) OR m.display_name LIKE ?"))
        assertTrue(where.contains(" OR m.id IN (SELECT media_id FROM face WHERE person_id IN (4,12)))"))
        assertEquals(listOf<Any?>("Beach", "%marie%", "%marie%"), args)
    }

    @Test
    fun `un terme réduit à une personne ne garde que ses photos`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(MediaQuery(terms = listOf(SearchTerm(personIds = listOf(9L)))), args)
        assertTrue(where.endsWith(" AND (m.id IN (SELECT media_id FROM face WHERE person_id IN (9)))"))
        assertTrue(args.isEmpty())
    }

    @Test
    fun `le filtre de mois compare le mois local sur deux chiffres`() {
        val args = mutableListOf<Any?>()
        val where = MediaQueryBuilder.where(MediaQuery(month = 7), args)
        assertTrue(where.contains("strftime('%m'"))
        assertEquals(listOf<Any?>("07"), args)
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
    fun `l'histogramme groupe par période locale`() {
        val sql = MediaQueryBuilder.histogram(MediaQuery(), GroupLevel.MONTH).sql
        assertTrue(sql.contains("strftime('%Y-%m', m.date_taken / 1000, 'unixepoch', 'localtime')"))
        assertTrue(sql.contains("GROUP BY"))
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
}
