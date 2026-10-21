package app.focale.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaAnalysisEntity
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaTextEntity
import app.focale.data.toModel
import app.focale.ml.ImageAnalyzer
import app.focale.ml.text.FakeTextReader
import app.focale.ml.text.TextRecognitionStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Files de l'analyse reprises après le dernier lot (AnalysisQueue, AnalysisStep.pendingAfter) :
 * mêmes médias, dans le même ordre, que des lots relus depuis le début. Le coût sur une grande
 * bibliothèque est mesuré par les tests de charge (app.focale.perf).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisQueueTest {

    private lateinit var database: FocaleDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `la file parcourt une fois les médias à analyser, des plus récents aux plus anciens`() = runBlocking<Unit> {
        // Dates égales départagées par l'identifiant ; un média déjà analysé, un dans la corbeille.
        database.mediaDao().upsert((1L..9L).map { media(it, taken = 1_000L * ((it + 1) / 2)) } + media(10, taken = 9_000, trashed = true))
        val dao = database.analysisDao()
        dao.save(listOf(analysis(4)), emptyList())
        val queue = AnalysisQueue(dao)
        val batches = mutableListOf<List<Long>>()
        while (true) {
            val batch = queue.next(2)
            if (batch.isEmpty()) break
            dao.save(batch.map { analysis(it.id) }, emptyList())
            batches += batch.map { it.id }
        }
        assertEquals(listOf(listOf(9L, 8L), listOf(7L, 6L), listOf(5L, 3L), listOf(2L, 1L)), batches)

        // Arrivé pendant le passage, plus récent que le curseur : pour le passage suivant.
        database.mediaDao().upsert(listOf(media(11, taken = 20_000)))
        assertTrue(queue.next(2).isEmpty())
        assertEquals(listOf(11L), AnalysisQueue(dao).next(2).map { it.id })
    }

    @Test
    fun `le texte reprend après le lot précédent, captures d'abord puis les autres photos`() = runBlocking<Unit> {
        val screenshots = (1L..3L).map { media(it, taken = it * 1_000, screenshot = true) }
        val photos = (4L..7L).map { media(it, taken = it * 1_000) }
        database.mediaDao().upsert(screenshots + photos)
        // Photo déjà lue : sautée.
        database.textDao().upsert(listOf(MediaTextEntity(6, analyzedModified = 1_000, version = TextRecognitionStep.VERSION, text = "", textKey = "")))
        val step = TextRecognitionStep(database.textDao(), FakeTextReader(emptyMap()))
        val first = step.pending(2)
        assertEquals(listOf(3L, 2L), first.map { it.id })
        // Fin des captures : le lot se complète avec les photos les plus récentes.
        val second = step.pendingAfter(first.last(), 2)
        assertEquals(listOf(1L, 7L), second.map { it.id })
        assertEquals(listOf(5L, 4L), step.pendingAfter(second.last(), 2).map { it.id })
        assertTrue(step.pendingAfter(photos.first().toModel(), 2).isEmpty())
    }

    private fun analysis(id: Long) = MediaAnalysisEntity(
        mediaId = id, analyzedModified = 1_000, version = ImageAnalyzer.VERSION, phash = null, blurScore = null,
        latitude = null, longitude = null, place = null, placeKey = null, country = null,
    )

    private fun media(id: Long, taken: Long, screenshot: Boolean = false, trashed: Boolean = false) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = if (screenshot) "Screenshot_$id.png" else "IMG_$id.jpg",
        relativePath = if (screenshot) "Pictures/Screenshots/" else "DCIM/Camera/", bucketId = 1, bucketName = "Camera",
        dateTaken = taken, dateAdded = taken, dateModified = 1_000, generation = id, size = 1, width = 1, height = 1,
        orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = screenshot, isGif = false,
        isTrashed = trashed, dateExpires = 0,
    )
}
