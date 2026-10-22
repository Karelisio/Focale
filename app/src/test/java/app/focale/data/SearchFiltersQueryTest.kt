package app.focale.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FaceEntity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaAnalysisEntity
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaLabelEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.PersonEntity
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.ml.SearchQueryParser
import app.focale.people.photosTogether
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
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
 * Requête des pastilles sur une vraie base Room : personnes, lieu exact, période et type combinés,
 * avec ou sans texte, et parcours de la visionneuse identique à celui de la grille.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchFiltersQueryTest {

    private lateinit var database: FocaleDatabase
    private lateinit var media: MediaRepository
    private val zone = ZoneId.systemDefault()
    private var alice = 0L
    private var bob = 0L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        media = MediaRepository(database.mediaDao(), Dispatchers.IO, database.invalidationTracker)
        runBlocking {
            database.mediaDao().upsert(
                listOf(
                    item(1, LocalDate.of(2023, 7, 14)),
                    item(2, LocalDate.of(2023, 7, 20), video = true),
                    item(3, LocalDate.of(2022, 7, 10)),
                    item(4, LocalDate.of(2023, 3, 1)),
                    item(5, LocalDate.of(2023, 7, 15), screenshot = true),
                    item(6, LocalDate.of(2023, 7, 14), trashed = true),
                    item(7, LocalDate.of(2023, 7, 16), gif = true),
                    item(8, LocalDate.of(2023, 7, 18)),
                    item(9, LocalDate.of(2024, 5, 2)),
                ),
            )
            database.analysisDao().save(
                listOf(
                    place(1, "Paris"), place(2, "Paris"), place(3, "Lyon"), place(4, "Paris"),
                    place(6, "Paris"), place(7, "Paris"), place(8, "Parisot"),
                    // Clé multilingue : nom GeoNames puis nom français.
                    place(9, "Lisbon", country = "PT", key = "lisbon | lisbonne"),
                ),
                listOf(MediaLabelEntity(1, "Beach", 0.9f), MediaLabelEntity(3, "Beach", 0.8f)),
            )
            alice = person("Alice", 1, 2, 3, 5, 6, 7, 8)
            bob = person("Bob", 1, 4, 6)
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `personne, lieu exact, période et type se combinent par ET`() {
        val filters = SearchFilters() + SearchChip.Person(alice, "Alice") + SearchChip.Place("Paris", "FR") +
            SearchChip.Period.month(YearMonth.of(2023, 7)) + SearchChip.Type(MediaFilter.PHOTOS)
        val query = filters.query(zone = zone)
        // Le GIF est une photo ; la vidéo, la capture sans lieu, Parisot et la corbeille sont écartés.
        assertEquals(listOf(7L, 1L), ids(query))
        val sql = MediaQueryBuilder.select(query).sql
        assertTrue(sql.contains("person_id IN ($alice)"))
        assertTrue(sql.contains("place_key = ?"))
        assertTrue(sql.contains("country IN (?)"))
        assertTrue(sql.contains("m.media_type = 1"))
        assertTrue(sql.contains("m.date_taken >= ?") && sql.contains("m.date_taken < ?"))
    }

    @Test
    fun `deux personnes donnent leurs photos ensemble, comme Avec`() {
        val query = (SearchFilters() + SearchChip.Person(alice) + SearchChip.Person(bob)).query(zone = zone)
        assertEquals(listOf(1L), ids(query))
        assertEquals(ids(photosTogether(listOf(alice, bob))), ids(query))
    }

    @Test
    fun `le lieu choisi est exact, le mot saisi en est un début`() {
        val chip = (SearchFilters() + SearchChip.Place("Paris", "FR")).query(zone = zone)
        assertEquals(listOf(2L, 7L, 1L, 4L), ids(chip))
        assertEquals(listOf(2L, 8L, 7L, 1L, 4L), ids(text("paris")))
    }

    @Test
    fun `la pastille d'un lieu traduit cherche son nom GeoNames, le texte l'un de ses noms`() {
        val lisbon = SearchChip.Place("Lisbon", "PT", french = "Lisbonne")
        assertEquals(listOf(9L), ids((SearchFilters() + lisbon).query(zone = zone)))
        assertEquals(listOf(9L), ids(text("lisbonne")))
        assertEquals(listOf(9L), ids(text("lisbon")))
        // Le nom français ne change pas la requête.
        assertEquals((SearchFilters() + SearchChip.Place("Lisbon", "PT")).query(zone = zone), (SearchFilters() + lisbon).query(zone = zone))
    }

    @Test
    fun `le texte et les pastilles se combinent`() {
        val beachWithAlice = SearchFilters("plage") + SearchChip.Person(alice)
        assertEquals(listOf(1L, 3L), ids(text("plage")))
        assertEquals(listOf(1L, 3L), ids(beachWithAlice.query(text("plage"), zone)))
        val beachIn2023 = beachWithAlice + SearchChip.Period.year(2023)
        assertEquals(listOf(1L), ids(beachIn2023.query(text("plage"), zone)))
        // « juillet » de toutes les années, 2023 en pastille : juillet 2023 seulement.
        val julyIn2023 = (SearchFilters("juillet") + SearchChip.Period.year(2023)).query(text("juillet"), zone)
        assertEquals(listOf(2L, 8L, 7L, 5L, 1L), ids(julyIn2023))
    }

    @Test
    fun `des pastilles incompatibles ne donnent aucun résultat`() {
        // « vidéos » saisi, pastille Photos.
        assertEquals(emptyList<Long>(), ids((SearchFilters("vidéos") + SearchChip.Type(MediaFilter.PHOTOS)).query(text("vidéos"), zone)))
        // « 2022 » saisi, pastille 2023.
        assertEquals(emptyList<Long>(), ids((SearchFilters("2022") + SearchChip.Period.year(2023)).query(text("2022"), zone)))
        // Bob n'a jamais été photographié à Lyon.
        val bobInLyon = SearchFilters() + SearchChip.Person(bob) + SearchChip.Place("Lyon", "FR")
        assertEquals(emptyList<Long>(), ids(bobInLyon.query(zone = zone)))
        assertEquals(0, runBlocking { database.mediaDao().count(MediaQueryBuilder.count(bobInLyon.query(zone = zone))) })
    }

    @Test
    fun `la visionneuse parcourt les résultats dans l'ordre de la grille`() = runBlocking {
        val query = (SearchFilters() + SearchChip.Person(alice) + SearchChip.Period.year(2023)).query(zone = zone)
        val grid = ids(query)
        assertEquals(listOf(2L, 8L, 7L, 5L, 1L), grid)
        // Position de départ de la visionneuse (même requête) : l'index de chaque média dans la grille.
        grid.forEachIndexed { index, id -> assertEquals(index, media.positionOf(query, id)) }
    }

    private fun text(value: String): MediaQuery = SearchQueryParser.parse(value, zone = zone)

    private fun ids(query: MediaQuery): List<Long> = runBlocking {
        database.mediaDao().list(MediaQueryBuilder.select(query)).map { it.id }
    }

    private suspend fun person(name: String, vararg mediaIds: Long): Long {
        val faces = database.faceDao()
        val id = faces.insertPerson(PersonEntity(name = name, createdAt = 0))
        faces.insertFaces(
            mediaIds.map {
                FaceEntity(
                    mediaId = it, personId = id, boxLeft = 0.1f, boxTop = 0.1f, boxRight = 0.3f, boxBottom = 0.3f,
                    embedding = ByteArray(8), quality = 0.5f,
                )
            },
        )
        return id
    }

    private fun place(mediaId: Long, name: String, country: String = "FR", key: String = name.lowercase()) = MediaAnalysisEntity(
        mediaId = mediaId, analyzedModified = 0, version = 1, phash = null, blurScore = null,
        latitude = 48.85, longitude = 2.35, place = name, placeKey = key, country = country,
    )

    /** Média pris à midi (heure locale) du jour [day]. */
    private fun item(
        id: Long,
        day: LocalDate,
        video: Boolean = false,
        screenshot: Boolean = false,
        gif: Boolean = false,
        trashed: Boolean = false,
    ): MediaEntity {
        val taken = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        return MediaEntity(
            id = id,
            mediaType = if (video) MediaQueryBuilder.MEDIA_TYPE_VIDEO else MediaQueryBuilder.MEDIA_TYPE_IMAGE,
            mimeType = when {
                video -> "video/mp4"
                gif -> "image/gif"
                else -> "image/jpeg"
            },
            displayName = if (screenshot) "Screenshot_$id.png" else "IMG_$id.jpg",
            relativePath = if (screenshot) "Pictures/Screenshots/" else "DCIM/Camera/",
            bucketId = 1, bucketName = "Camera", dateTaken = taken, dateAdded = taken, dateModified = taken, generation = 0,
            size = 1, width = 100, height = 100, orientation = 0, duration = if (video) 1_000 else 0,
            volumeName = "external_primary", isScreenshot = screenshot, isGif = gif, isTrashed = trashed, dateExpires = 0,
        )
    }
}
