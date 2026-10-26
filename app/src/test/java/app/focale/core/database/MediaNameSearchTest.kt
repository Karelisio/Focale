package app.focale.core.database

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mots des noms de fichiers cherchés par l'index plein texte (`media_name`) : exactement les
 * médias que trouvait `LIKE '%mot%'` sur chaque nom, pour tous les mots de lettres contenus dans
 * des noms variés (majuscules, accents, chiffres, longues suites de lettres, lettre hors du plan
 * de base, accent décomposé), et l'index suit les noms renommés et les médias retirés ; de même
 * pendant que la première indexation écrit les mots après coup (`media_name_pending`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaNameSearchTest {

    private lateinit var database: FocaleDatabase

    private val names = listOf(
        "Screenshot_20240101-123456_WhatsApp.png", "IMG_20240101_123456.jpg", "IMG-20230704-WA0012.jpg",
        "facture_EDF-2024.pdf.jpg", "Été à Nice.JPG", "naïve café.jpg", "ÉCRAN.png", "PXL_20231224_183012345.MP.jpg",
        "a".repeat(40) + "Zut.jpg", "x𝔸yz.jpg", "éte.jpg", "DSC_0042.JPG", "20240101_120000.mp4",
    )

    @Before
    fun open() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java).build()
        database.mediaDao().upsert(names.mapIndexed { index, name -> media(index + 1L, name) })
    }

    @After
    fun close() = database.close()

    @Test
    fun `chaque mot de lettres trouve les mêmes noms que LIKE, ni plus ni moins`() = runBlocking<Unit> {
        // Toutes les suites de lettres ASCII des noms, en minuscules et en majuscules, et quelques absentes.
        val words = names.flatMap { name ->
            Regex("[A-Za-z]+").findAll(name).flatMap { run ->
                val letters = run.value
                (letters.indices).flatMap { from -> (from + 1..minOf(letters.length, from + 30)).map { letters.substring(from, it) } }
            }
        }.flatMap { listOf(it, it.lowercase(), it.uppercase()) }.toSet() + listOf("chien", "jpgx", "ete", "cafe", "zz", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaz")
        assertTrue(words.size > 500)
        for (word in words) {
            val query = MediaQuery(terms = listOf(SearchTerm(text = word)))
            // Moins de trois lettres : LIKE sur chaque nom, comme avant.
            assertEquals(word, word.length >= MediaNames.MIN_LENGTH, MediaQueryBuilder.select(query).sql.contains("media_name MATCH ?"))
            assertEquals(word, like(word), found(query))
        }
    }

    @Test
    fun `un texte avec chiffre ou séparateur garde LIKE`() = runBlocking<Unit> {
        for (text in listOf("2024", "IMG_2024", "wa0012", "pdf.jpg", "é")) {
            val query = MediaQuery(terms = listOf(SearchTerm(text = text)))
            assertFalse(MediaQueryBuilder.select(query).sql.contains("media_name"))
            assertEquals(text, like(text), found(query))
        }
    }

    @Test
    fun `l'index suit les noms renommés et les médias retirés`() = runBlocking<Unit> {
        val dao = database.mediaDao()
        val shot = MediaQuery(terms = listOf(SearchTerm(text = "shot")))
        assertEquals(listOf(1L), found(shot))
        dao.upsert(listOf(media(1, "Capture.png"), media(2, "Screenshot_2.png")))
        assertEquals(listOf(2L), found(shot))
        assertEquals(listOf(1L), found(MediaQuery(terms = listOf(SearchTerm(text = "capt")))))
        dao.deleteByIds(listOf(2L))
        assertEquals(emptyList<Long>(), found(shot))
        assertEquals(0L, count("SELECT COUNT(*) FROM media_name WHERE rowid = 2"))
    }

    @Test
    fun `pendant la première indexation, les noms sans leurs mots restent trouvés`() = runBlocking<Unit> {
        // Première indexation : médias écrits, mots des noms en attente, puis écrits par tranches.
        val dao = database.mediaDao()
        dao.deleteByIds(names.indices.map { it + 1L })
        dao.upsertNamesLater(names.mapIndexed { index, name -> media(index + 1L, name) })
        assertEquals(0L, count("SELECT COUNT(*) FROM media_name"))
        val words = listOf("shot", "Screenshot", "whatsapp", "facture", "EDF", "naïve", "café", "ete", "ÉCRAN", "zut", "jpg", "img", "mp", "dsc")
        suspend fun sameAsLike() {
            for (word in words) assertEquals(word, like(word), found(MediaQuery(terms = listOf(SearchTerm(text = word)))))
        }
        sameAsLike()
        // Un média renommé ou retiré pendant ce temps : son nom actuel, ou rien.
        dao.upsert(listOf(media(2, "Capture_plage.jpg")))
        dao.deleteByIds(listOf(4L))
        assertEquals(5, dao.indexPendingNames(5))
        sameAsLike()
        assertEquals(listOf(2L), found(MediaQuery(terms = listOf(SearchTerm(text = "plage")))))
        while (dao.indexPendingNames(5) > 0) Unit
        assertFalse(dao.hasPendingNames())
        sameAsLike()
        // Tous les mots écrits, médias retirés exceptés, chacun d'après son nom actuel.
        assertEquals(names.size - 1L, count("SELECT COUNT(*) FROM media_name"))
        val words2 = database.openHelper.readableDatabase.query("SELECT words FROM media_name WHERE rowid = 2").use { it.moveToFirst(); it.getString(0) }
        assertEquals(MediaNames.document("Capture_plage.jpg"), words2)
    }

    /** Médias de [query], par identifiant croissant. */
    private suspend fun found(query: MediaQuery): List<Long> = database.mediaDao().ids(MediaQueryBuilder.ids(query)).sorted()

    /** Médias dont le nom contient [text] selon `LIKE`, comme avant l'index. */
    private suspend fun like(text: String): List<Long> = database.mediaDao().ids(
        SimpleSQLiteQuery("SELECT id FROM media WHERE display_name LIKE ? ESCAPE '\\' ORDER BY id", arrayOf(MediaQueryBuilder.likePattern(text))),
    )

    private suspend fun count(sql: String): Long = database.mediaDao().ids(SimpleSQLiteQuery(sql)).single()

    private fun media(id: Long, name: String) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = name, relativePath = "DCIM/Camera/",
        bucketId = 42, bucketName = "Camera", dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = id * 1_000,
        generation = id, size = 1, width = 1, height = 1, orientation = 0, duration = 0, volumeName = "external_primary",
        isScreenshot = false, isGif = false, isTrashed = false, dateExpires = 0,
    )
}
