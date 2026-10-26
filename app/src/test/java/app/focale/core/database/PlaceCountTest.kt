package app.focale.core.database

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.ml.ImageAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comptes des clés de lieux (`place_count`) tenus à jour par chaque écriture des analyses : après
 * chaque étape, égaux au recalcul complet depuis `media_analysis`, et la recherche d'un mot parmi
 * les lieux, qui passe par eux, trouve les mêmes médias qu'un `LIKE` sur chaque analyse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaceCountTest {

    private lateinit var database: FocaleDatabase
    private val media get() = database.mediaDao()
    private val analysis get() = database.analysisDao()

    @Before
    fun open() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java).build()
        media.upsert((1L..6L).map(::media))
    }

    @After
    fun close() = database.close()

    @Test
    fun `les comptes suivent l'analyse, la nouvelle analyse, les clés recalculées et la purge des orphelines`() = runBlocking<Unit> {
        analysis.save((1L..4L).map { analysis(it, if (it <= 3) "saint malo" else "nantes") } + analysis(5, null), emptyList())
        assertCounts("nantes" to 1, "saint malo" to 3)
        assertEquals(listOf(1L, 2L, 3L), found("malo"))
        assertEquals(listOf(1L, 2L, 3L), found("saint"))
        assertEquals(emptyList<Long>(), found("alo"))

        // Nouvelle analyse : le média change de lieu, puis le perd ; doublon dans le lot.
        analysis.save(listOf(analysis(1, "nantes"), analysis(1, "nantes"), analysis(2, null)), emptyList())
        assertCounts("nantes" to 2, "saint malo" to 1)
        assertEquals(listOf(1L, 4L), found("nan"))
        // Écriture directe des analyses (sans étiquettes) : comptée aussi.
        analysis.upsert(listOf(analysis(6, "lisbon | lisbonne")))
        assertCounts("lisbon | lisbonne" to 1, "nantes" to 2, "saint malo" to 1)
        assertEquals(listOf(6L), found("lisbonne"))

        // Clés recalculées d'un lieu (nouvelle base des lieux) : tous ses médias d'un coup.
        val keys = mapOf("nantes" to "nantes | nantes ville", "saint malo" to "saint malo", "lisbon" to "lisbon | lisbonne")
        assertEquals(1, analysis.refreshPlaceKeys { place, _ -> keys.getValue(place) })
        assertCounts("lisbon | lisbonne" to 1, "nantes | nantes ville" to 2, "saint malo" to 1)
        assertEquals(listOf(1L, 4L), found("ville"))

        // Médias disparus (accès complet) : leurs analyses purgées, retirées des comptes.
        media.deleteByIds(listOf(3L, 4L))
        assertCounts("lisbon | lisbonne" to 1, "nantes | nantes ville" to 2, "saint malo" to 1)
        media.deleteOrphanAnalysis()
        assertCounts("lisbon | lisbonne" to 1, "nantes | nantes ville" to 1)
        assertEquals(emptyList<Long>(), found("malo"))
    }

    /** Comptes non nuls égaux à [expected], et au recalcul complet. */
    private suspend fun assertCounts(vararg expected: Pair<String, Int>) {
        val counts = analysis.placeCounts()
        assertEquals(expected.map { (key, count) -> PlaceTally(key, count) }, counts)
        analysis.rebuildPlaceCounts()
        assertEquals(counts, analysis.placeCounts())
    }

    /** Médias trouvés par un mot parmi les lieux, par identifiant croissant ; comparés au LIKE sur chaque analyse. */
    private suspend fun found(word: String): List<Long> {
        val ids = media.ids(MediaQueryBuilder.ids(MediaQuery(terms = listOf(SearchTerm(place = word))))).sorted()
        val like = media.ids(
            SimpleSQLiteQuery(
                "SELECT media_id FROM media_analysis WHERE (' ' || place_key) LIKE ? AND media_id IN (SELECT id FROM media) ORDER BY media_id",
                arrayOf("% $word%"),
            ),
        )
        assertEquals(word, like, ids)
        return ids
    }

    private fun analysis(id: Long, key: String?) = MediaAnalysisEntity(
        mediaId = id, analyzedModified = 0, version = ImageAnalyzer.VERSION, phash = null, blurScore = null,
        latitude = key?.let { 47.0 }, longitude = key?.let { -1.5 }, place = key?.substringBefore(" |"), placeKey = key, country = key?.let { "FR" },
    )

    private fun media(id: Long) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/",
        bucketId = 42, bucketName = "Camera", dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = 0,
        generation = id, size = 1, width = 1, height = 1, orientation = 0, duration = 0, volumeName = "external_primary",
        isScreenshot = false, isGif = false, isTrashed = false, dateExpires = 0,
    )
}
