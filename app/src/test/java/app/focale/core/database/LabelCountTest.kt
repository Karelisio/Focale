package app.focale.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
 * Comptes des étiquettes (`label_count`) tenus à jour par chaque écriture des médias et des
 * étiquettes : après chaque étape, égaux au recalcul complet depuis `media_label` (médias de
 * l'index, hors corbeille), et à ce qu'affiche l'onglet Recherche.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabelCountTest {

    private lateinit var database: FocaleDatabase
    private val media get() = database.mediaDao()
    private val analysis get() = database.analysisDao()

    @Before
    fun open() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java).build()
        media.upsert((1L..6L).map { media(it) })
    }

    @After
    fun close() = database.close()

    @Test
    fun `les comptes suivent l'analyse, la corbeille, l'accès partiel et la purge des orphelines`() = runBlocking<Unit> {
        analysis.save((1L..4L).map(::analysis), labels(1L to "Dog", 1L to "Beach", 2L to "Dog", 3L to "Dog", 4L to "Cat"))
        assertCounts("Beach" to 1, "Cat" to 1, "Dog" to 3)

        // Nouvelle analyse d'un média : ses anciennes étiquettes sont remplacées ; doublon dans le lot.
        analysis.save(listOf(analysis(1)), labels(1L to "Cat", 1L to "Cat"))
        assertCounts("Cat" to 2, "Dog" to 2)

        // Corbeille, puis restauration (synchronisation : le média réécrit avec son nouvel état).
        media.upsert(listOf(media(2, trashed = true)))
        assertCounts("Cat" to 2, "Dog" to 1)
        media.upsert(listOf(media(2)))
        assertCounts("Cat" to 2, "Dog" to 2)

        // Réécrit tel quel (date modifiée) : rien ne change.
        media.upsert((1L..6L).map { media(it, modified = 99) })
        assertCounts("Cat" to 2, "Dog" to 2)

        // Accès partiel : médias retirés de l'index, étiquettes gardées mais plus comptées…
        media.deleteByIds(listOf(3L, 4L))
        assertCounts("Cat" to 1, "Dog" to 1)
        // … jusqu'à leur retour dans l'index.
        media.upsert(listOf(media(3)))
        assertCounts("Cat" to 1, "Dog" to 2)
        // Étiquettes d'un média absent analysé entre-temps, puis purge des orphelines : sans effet.
        analysis.save(listOf(analysis(4)), labels(4L to "Beach"))
        assertCounts("Cat" to 1, "Dog" to 2)
        media.deleteOrphanLabels()
        assertCounts("Cat" to 1, "Dog" to 2)

        // Les plus présentes de l'onglet Recherche : nombres et couverture visible.
        val stats = analysis.labelStats(10)
        assertEquals(listOf("Dog" to 2, "Cat" to 1), stats.map { it.label to it.count })
        assertEquals(listOf(3L, 1L), stats.map { it.coverId })
    }

    /** Comptes tenus à jour égaux à [expected], et au recalcul complet. */
    private suspend fun assertCounts(vararg expected: Pair<String, Int>) {
        val counts = analysis.labelCounts()
        assertEquals(expected.map { (label, count) -> LabelTally(label, count) }, counts)
        analysis.rebuildLabelCounts()
        assertEquals(counts, analysis.labelCounts())
    }

    private fun labels(vararg labels: Pair<Long, String>) = labels.map { (id, label) -> MediaLabelEntity(id, label, 0.5f + id / 100f) }

    private fun analysis(id: Long) = MediaAnalysisEntity(
        mediaId = id, analyzedModified = 0, version = ImageAnalyzer.VERSION, phash = null, blurScore = null,
        latitude = null, longitude = null, place = null, placeKey = null, country = null,
    )

    private fun media(id: Long, trashed: Boolean = false, modified: Long = 0) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/",
        bucketId = 42, bucketName = "Camera", dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = modified,
        generation = id, size = 1, width = 1, height = 1, orientation = 0, duration = 0, volumeName = "external_primary",
        isScreenshot = false, isGif = false, isTrashed = trashed, dateExpires = 0,
    )
}
