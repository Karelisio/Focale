package app.focale.data

import android.app.Application
import androidx.paging.PagingData
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEntity
import app.focale.core.model.GridItem
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaSort
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Histogramme et position dans la grille, par périodes bornées en Kotlin : mêmes jours, mois et
 * années que les en-têtes de la grille (toGridItems), autour de minuit et des changements d'heure,
 * quel que soit le fuseau de SQLite. Fuseau de l'appareil : Paris ; JVM des tests : le sien.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaPeriodsTest {

    private val zone = ZoneId.of("Europe/Paris")
    private lateinit var database: FocaleDatabase
    private lateinit var repository: MediaRepository

    /** Médias autour de minuit et des jours de 23 et 25 heures de 2023, plus un dans la corbeille. */
    private val times = listOf(
        at(2023, 3, 25, 23, 30), at(2023, 3, 26, 0, 15), at(2023, 3, 26, 3, 0), at(2023, 3, 26, 23, 45),
        at(2023, 3, 27, 0, 5), at(2023, 3, 31, 23, 59), at(2023, 4, 1, 0, 0), at(2023, 10, 29, 0, 30),
        at(2023, 10, 29, 2, 30), at(2023, 10, 29, 23, 30), at(2023, 10, 30, 0, 10), at(2023, 12, 31, 23, 50),
        at(2024, 1, 1, 0, 10),
    )

    @Before
    fun open() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java).build()
        repository = MediaRepository(database.mediaDao(), Dispatchers.IO, database.invalidationTracker)
        database.mediaDao().upsert(times.mapIndexed { index, time -> media(index + 1L, time) } + media(99, at(2023, 6, 1, 12, 0), trashed = true))
    }

    @After
    fun close() = database.close()

    @Test
    fun `histogramme et en-têtes avant chaque média, comme la grille`() = runBlocking<Unit> {
        for (sort in listOf(MediaSort(), MediaSort(ascending = true))) {
            val query = MediaQuery(sort = sort)
            val items = repository.list(query)
            for (level in GroupLevel.entries) {
                val grid = flowOf(PagingData.from(items)).gridOf(level)
                val headers = grid.filterIsInstance<GridItem.Header>()
                val buckets = repository.histogram(query, level, zone)
                assertEquals("$level $sort", headers.map { it.periodStart }, buckets.map { it.periodStart })
                assertEquals(items.size, buckets.sumOf { it.count })
                for ((index, item) in grid.withIndex()) {
                    if (item !is GridItem.Media) continue
                    assertEquals("$level $sort ${item.item.dateTaken}", index, repository.gridPositionOf(query, item.item.id, level, zone))
                }
            }
            assertEquals(items.size, repository.observeHistogram(query, GroupLevel.MONTH, zone).first().sumOf { it.count })
        }
        // Aucun média pour la requête : aucune période.
        assertEquals(emptyList<Any>(), repository.histogram(MediaQuery(bucketId = 404L), GroupLevel.DAY, zone))
    }

    private suspend fun kotlinx.coroutines.flow.Flow<PagingData<MediaItem>>.gridOf(level: GroupLevel): List<GridItem> =
        flow { collect { emit(it.toGridItems(level, zone)) } }.asSnapshot()

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun media(id: Long, dateTaken: Long, trashed: Boolean = false) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/",
        bucketId = 42, bucketName = "Camera", dateTaken = dateTaken, dateAdded = dateTaken, dateModified = dateTaken,
        generation = id, size = 1, width = 1, height = 1, orientation = 0, duration = 0, volumeName = "external_primary",
        isScreenshot = false, isGif = false, isTrashed = trashed, dateExpires = 0,
    )
}
