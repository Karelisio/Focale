package app.focale.core.database

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.ml.LabelDictionary
import app.focale.ml.PlaceName
import app.focale.ml.PlaceNames
import app.focale.ml.PlaceTranslations
import app.focale.ml.SearchQueryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Un lieu se cherche par son nom GeoNames, français ou anglais (« Lisbon », « Lisbonne »). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlaceSearchTest {

    private lateinit var database: FocaleDatabase
    private lateinit var translations: PlaceTranslations

    @Before
    fun open() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).allowMainThreadQueries().build()
        translations = PlaceNames(context, Dispatchers.Unconfined).translations()
    }

    @After
    fun close() = database.close()

    private suspend fun save(id: Long, place: String, country: String, key: String) {
        database.mediaDao().upsert(
            listOf(
                MediaEntity(
                    id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/",
                    bucketId = 1, bucketName = "Camera", dateTaken = id, dateAdded = id, dateModified = id, generation = 0, size = 1,
                    width = 100, height = 100, orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = false,
                    isGif = false, isTrashed = false, dateExpires = 0,
                ),
            ),
        )
        database.analysisDao().upsert(
            listOf(
                MediaAnalysisEntity(
                    mediaId = id, analyzedModified = id, version = 2, phash = null, blurScore = null, latitude = 0.0, longitude = 0.0,
                    place = place, placeKey = key, country = country,
                ),
            ),
        )
    }

    private suspend fun count(query: MediaQuery) = database.mediaDao().count(MediaQueryBuilder.count(query))

    private suspend fun search(text: String) = count(SearchQueryParser.parse(text))

    /** Requête d'une carte de lieu de la recherche : ce lieu, dans ce pays. */
    private suspend fun exact(name: String, country: String) = count(
        MediaQuery(terms = listOf(SearchTerm(place = LabelDictionary.normalize(name), exactPlace = true), SearchTerm(countries = listOf(country)))),
    )

    @Test
    fun `un lieu se trouve par ses noms français et anglais`() = runTest {
        save(1, "Lisbon", "PT", translations.searchKey("Lisbon", "PT"))
        save(2, "Köln", "DE", translations.searchKey("Köln", "DE"))
        save(3, "Paris", "FR", translations.searchKey("Paris", "FR"))
        save(4, "Makkah", "SA", translations.searchKey("Makkah", "SA"))

        assertEquals(1, search("Lisbonne"))
        assertEquals(1, search("lisbon"))
        assertEquals(1, search("lisb"))
        assertEquals(1, search("Cologne"))
        assertEquals(1, search("köln"))
        assertEquals(1, search("paris"))
        // Nom français affiché, nom anglais seulement cherché.
        assertEquals(1, search("la mecque"))
        assertEquals(1, search("mecca"))
        // Préfixe d'un mot seulement : « bonne » ne trouve pas Lisbonne.
        assertEquals(0, search("bonne"))
        // Carte d'un lieu : son nom GeoNames, avec ou sans traductions.
        assertEquals(1, exact("Lisbon", "PT"))
        assertEquals(1, exact("Paris", "FR"))
        // Mot désignant un lieu de la bibliothèque (contrainte de la recherche par le sens).
        val embeddings = database.embeddingDao()
        assertTrue(embeddings.isPlaceWord("lisbonne"))
        assertTrue(embeddings.isPlaceWord("cologne"))
        assertFalse(embeddings.isPlaceWord("lisbonn"))
    }

    @Test
    fun `les clés des lieux déjà analysés sont recalculées sans nouvelle analyse`() = runTest {
        // Clés d'une version sans noms traduits : le nom GeoNames normalisé.
        save(1, "Lisbon", "PT", "lisbon")
        save(2, "Lisbon", "PT", "lisbon")
        save(3, "Paris", "FR", "paris")
        assertEquals(0, search("lisbonne"))

        val analysis = database.analysisDao()
        assertEquals(1, analysis.refreshPlaceKeys(translations::searchKey))

        assertEquals(2, search("lisbonne"))
        assertEquals(2, exact("Lisbon", "PT"))
        assertEquals(1, search("paris"))
        // Rien à refaire au passage suivant.
        assertEquals(0, analysis.refreshPlaceKeys(translations::searchKey))
        assertEquals(PlaceName("Lisbon", french = "Lisbonne"), translations.of("Lisbon", "PT"))
    }
}
