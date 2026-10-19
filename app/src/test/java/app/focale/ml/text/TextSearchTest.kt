package app.focale.ml.text

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaAnalysisEntity
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaLabelEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.ml.SearchQueryParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** De la saisie au résultat : le texte reconnu est trouvé par la recherche, sur une vraie base Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextSearchTest {

    private lateinit var database: FocaleDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            index(1, "FACTURE N° 2024-118\nTotal TTC : 42,00 €")
            index(2, "Réseau Wi-Fi : Livebox-A1B2\nClé : 7F3K9", screenshot = true)
            index(3, "MENU DU JOUR\nPlat · Dessert · Café")
            index(4, "Crème brûlée maison")
            index(5, "")
            index(6, null, labels = listOf("Receipt"))
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `chaque mot saisi cherche aussi le texte reconnu, en OU avec les étiquettes`() {
        val wifi = SearchQueryParser.parse("wifi").terms.single()
        assertEquals("wifi", wifi.text)
        assertEquals(emptyList<String>(), wifi.labels)
        val menu = SearchQueryParser.parse("Menu").terms.single()
        assertEquals("menu", menu.text)
        assertEquals(listOf("Menu"), menu.labels)
        assertEquals(listOf("Receipt"), SearchQueryParser.parse("facture").terms.single().labels)
    }

    @Test
    fun `facture, wifi et menu trouvent les photos qui contiennent ces mots`() {
        // « facture » est aussi un synonyme de l'étiquette « Receipt » : texte OU étiquette.
        assertEquals(setOf(1L, 6L), search("facture"))
        assertEquals(setOf(2L), search("wifi"))
        assertEquals(setOf(3L), search("menu"))
    }

    @Test
    fun `la recherche ignore la casse et les accents`() {
        assertEquals(setOf(1L, 6L), search("FACTURÉ"))
        assertEquals(setOf(2L), search("Wi-Fi"))
        assertEquals(setOf(2L), search("cle"))
        assertEquals(setOf(3L), search("café"))
        assertEquals(setOf(4L), search("creme brulee"))
        assertEquals(setOf(4L), search("BRÛLÉE"))
    }

    @Test
    fun `chaque mot doit figurer dans la photo`() {
        assertEquals(setOf(1L), search("facture 118"))
        assertEquals(emptySet<Long>(), search("dessert brulee"))
        assertEquals(setOf(1L), search("2024118"))
    }

    @Test
    fun `le texte se combine aux filtres de type`() {
        assertEquals(setOf(2L), search("captures livebox"))
        assertEquals(emptySet<Long>(), search("captures menu"))
    }

    private fun search(text: String): Set<Long> = runBlocking {
        database.mediaDao().list(MediaQueryBuilder.select(SearchQueryParser.parse(text))).map { it.id }.toSet()
    }

    /** Photo indexée, avec son texte reconnu ([text] null : pas encore lu) et ses étiquettes. */
    private suspend fun index(id: Long, text: String?, screenshot: Boolean = false, labels: List<String> = emptyList()) {
        database.mediaDao().upsert(
            listOf(
                MediaEntity(
                    id = id, mediaType = MediaQueryBuilder.MEDIA_TYPE_IMAGE, mimeType = "image/jpeg",
                    displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/", bucketId = 1, bucketName = "Camera",
                    dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = 1_000L, generation = 0, size = 1,
                    width = 4000, height = 3000, orientation = 0, duration = 0, volumeName = "external_primary",
                    isScreenshot = screenshot, isGif = false, isTrashed = false, dateExpires = 0,
                ),
            ),
        )
        if (text != null) database.textDao().upsert(listOf(TextRecognitionStep.entity(photo(id), text)))
        if (labels.isNotEmpty()) {
            database.analysisDao().save(
                listOf(MediaAnalysisEntity(id, 1_000L, 1, null, null, null, null, null, null, null)),
                labels.map { MediaLabelEntity(id, it, 0.9f) },
            )
        }
    }
}
