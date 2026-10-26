package app.focale.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.ml.ImageAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Garde-fou des comptes tenus à jour (`label_count`, `place_count`) : des écritures qui contournent
 * volontairement les méthodes prévues les faussent, le contrôle du début de passage d'analyse
 * ([AnalysisDao.checkCounts]) le voit et les recalcule ; sans écart, il ne touche à rien.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CountCheckTest {

    private lateinit var database: FocaleDatabase
    private val media get() = database.mediaDao()
    private val analysis get() = database.analysisDao()

    @Before
    fun open() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java).build()
        media.upsert((1L..4L).map { media(it) })
        analysis.save((1L..3L).map { analysis(it, "nantes") }, (1L..3L).map { MediaLabelEntity(it, "Dog", 0.9f) })
    }

    @After
    fun close() = database.close()

    @Test
    fun `des comptes justes ne sont pas recalculés`() = runBlocking<Unit> {
        assertEquals(emptyList<String>(), analysis.checkCounts())
        assertEquals(listOf(LabelTally("Dog", 3)), analysis.labelCounts())
        assertEquals(listOf(PlaceTally("nantes", 3)), analysis.placeCounts())
    }

    @Test
    fun `des écritures qui contournent les méthodes sont rattrapées au passage suivant`() = runBlocking<Unit> {
        // Étiquettes et analyse écrites sans leurs comptes, média mis à la corbeille de même.
        analysis.insertLabels(listOf(MediaLabelEntity(4, "Cat", 0.8f), MediaLabelEntity(1, "Beach", 0.7f)))
        analysis.upsertRows(listOf(analysis(4, "saint malo")))
        media.upsertRows(listOf(media(2, trashed = true)))
        assertNotEquals(expectedLabels, analysis.labelCounts())
        assertEquals(listOf("label_count", "place_count"), analysis.checkCounts())
        assertEquals(expectedLabels, analysis.labelCounts())
        assertEquals(listOf(PlaceTally("nantes", 3), PlaceTally("saint malo", 1)), analysis.placeCounts())
        // Rattrapé : plus rien au passage suivant.
        assertEquals(emptyList<String>(), analysis.checkCounts())

        // Suppressions directes : lignes retirées sans leurs comptes.
        database.openHelper.writableDatabase.execSQL("DELETE FROM media_analysis WHERE media_id = 1")
        database.openHelper.writableDatabase.execSQL("DELETE FROM media_label WHERE label = 'Dog'")
        assertEquals(listOf("label_count", "place_count"), analysis.checkCounts())
        assertEquals(listOf(LabelTally("Beach", 1), LabelTally("Cat", 1)), analysis.labelCounts())
        assertEquals(listOf(PlaceTally("nantes", 2), PlaceTally("saint malo", 1)), analysis.placeCounts())
    }

    /** Médias visibles 1, 3 et 4 (2 dans la corbeille) : ce que donne le recalcul complet. */
    private val expectedLabels = listOf(LabelTally("Beach", 1), LabelTally("Cat", 1), LabelTally("Dog", 2))

    private fun analysis(id: Long, key: String) = MediaAnalysisEntity(
        mediaId = id, analyzedModified = 0, version = ImageAnalyzer.VERSION, phash = null, blurScore = null,
        latitude = 47.0, longitude = -1.5, place = key, placeKey = key, country = "FR",
    )

    private fun media(id: Long, trashed: Boolean = false) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/",
        bucketId = 42, bucketName = "Camera", dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = 0,
        generation = id, size = 1, width = 1, height = 1, orientation = 0, duration = 0, volumeName = "external_primary",
        isScreenshot = false, isGif = false, isTrashed = trashed, dateExpires = 0,
    )
}
