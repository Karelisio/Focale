package app.focale.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.data.MediaRepository
import app.focale.ml.ImageAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tables observées par les grilles : la timeline et les dossiers ne lisent que `media`, l'analyse
 * (un lot toutes les quelques secondes, des heures durant sur une grande bibliothèque) ne doit
 * donc pas recharger leurs pages ; une recherche, elle, suit l'analyse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaObservationTest {

    private lateinit var database: FocaleDatabase

    @Before
    fun open() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java).build()
        database.mediaDao().upsert((1L..3L).map { media(it) })
    }

    @After
    fun close() = database.close()

    @Test
    fun `un lot d'analyse recharge la recherche mais pas la timeline ni un dossier`() = runBlocking<Unit> {
        val dao = database.mediaDao()
        val repository = MediaRepository(dao, Dispatchers.IO, database.invalidationTracker)
        val timeline = repository.pagingSource(MediaQuery.Timeline).loaded()
        val folder = repository.pagingSource(MediaQuery(bucketId = 42L)).loaded()
        val search = repository.pagingSource(MediaQuery(terms = listOf(SearchTerm(labels = listOf("Dog"))))).loaded()

        database.analysisDao().save(listOf(analysis(1)), listOf(MediaLabelEntity(1, "Dog", 0.9f)))
        awaitInvalid(search)
        // Tous les observateurs sont prévenus dans le même passage : un court délai suffit.
        delay(200)
        assertFalse(timeline.invalid)
        assertFalse(folder.invalid)

        // Un nouveau média, lui, recharge la timeline.
        dao.upsert(listOf(media(4)))
        awaitInvalid(timeline)
        awaitInvalid(folder)
    }

    private suspend fun PagingSource<Int, MediaEntity>.loaded() = apply {
        load(PagingSource.LoadParams.Refresh(key = null, loadSize = 30, placeholdersEnabled = true))
    }

    /** Les PagingSource sont invalidées en différé, après la transaction (InvalidationTracker). */
    private suspend fun awaitInvalid(source: PagingSource<Int, MediaEntity>) = withTimeout(5_000) {
        while (!source.invalid) delay(10)
    }

    private fun analysis(id: Long) = MediaAnalysisEntity(
        mediaId = id, analyzedModified = id * 1_000, version = ImageAnalyzer.VERSION, phash = 1L, blurScore = 500f,
        latitude = null, longitude = null, place = null, placeKey = null, country = null,
    )

    private fun media(id: Long) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/",
        bucketId = 42, bucketName = "Camera", dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = id * 1_000,
        generation = id, size = 1, width = 1, height = 1, orientation = 0, duration = 0, volumeName = "external_primary",
        isScreenshot = false, isGif = false, isTrashed = false, dateExpires = 0,
    )
}
