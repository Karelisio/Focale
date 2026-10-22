package app.focale.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FaceEntity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaAnalysisEntity
import app.focale.core.database.MediaEntity
import app.focale.core.database.MemoryQueries
import app.focale.core.database.PersonEntity
import app.focale.core.database.PersonPresence
import app.focale.core.locale.AppLocales
import app.focale.core.model.MediaQuery
import app.focale.core.settings.SettingsRepository
import app.focale.ml.PlaceName
import app.focale.ml.PlaceNames
import app.focale.people.PeopleEditions
import app.focale.people.PeopleRepository
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MemoriesRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 6, 12)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var database: FocaleDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: MemoriesRepository

    private var nextId = 1L
    private val media = mutableListOf<MediaEntity>()
    private val analyses = mutableListOf<MediaAnalysisEntity>()

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsRepository(PreferenceDataStoreFactory.create { File(folder.root, "settings.preferences_pb") }, scope)
        val people = PeopleRepository(database.faceDao(), PeopleEditions(), Dispatchers.Unconfined, AppLocales(ApplicationProvider.getApplicationContext()))
        val names = PlaceNames(ApplicationProvider.getApplicationContext(), Dispatchers.Unconfined)
        repository = MemoriesRepository(database.mediaDao(), database.memoryDao(), people, settings, names, Dispatchers.Unconfined)
    }

    @After
    fun close() {
        database.close()
        scope.cancel()
    }

    private data class Spot(val name: String, val country: String, val latitude: Double, val longitude: Double)

    private fun millis(at: LocalDateTime) = at.toInstant(utc).toEpochMilli()

    private fun photo(at: LocalDateTime, where: Spot?, bucket: Long = 1, trashed: Boolean = false): Long {
        val id = nextId++
        val date = millis(at)
        media += MediaEntity(
            id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg",
            relativePath = "DCIM/Camera/", bucketId = bucket, bucketName = "Camera", dateTaken = date,
            dateAdded = date, dateModified = date, generation = 0, size = 1, width = 100, height = 100,
            orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = false,
            isGif = false, isTrashed = trashed, dateExpires = 0,
        )
        if (where != null) {
            analyses += MediaAnalysisEntity(
                mediaId = id, analyzedModified = date, version = 1, phash = null, blurScore = null,
                latitude = where.latitude, longitude = where.longitude, place = where.name,
                placeKey = where.name.lowercase(), country = where.country,
            )
        }
        return id
    }

    /** Photos de [from] à [to] inclus, aux heures [hours] de chaque jour, dans l'ordre. */
    private fun stay(
        where: Spot,
        from: LocalDate,
        to: LocalDate,
        hours: List<Int> = listOf(10, 13, 16),
        bucket: Long = 1,
        trashed: Boolean = false,
    ): List<Long> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
        .flatMap { day -> hours.map { photo(day.atTime(it, 0), where, bucket, trashed) } }
        .toList()

    /** Une photo à la maison le 25 de chaque mois. */
    private fun homeLife(year: Int) = (1..12).forEach { photo(LocalDate.of(year, it, 25).atTime(12, 0), PARIS) }

    private fun face(mediaId: Long, personId: Long) = FaceEntity(
        mediaId = mediaId, personId = personId, boxLeft = 0.1f, boxTop = 0.1f, boxRight = 0.3f, boxBottom = 0.3f,
        embedding = ByteArray(8), quality = 0.5f,
    )

    private suspend fun save() {
        database.mediaDao().upsert(media)
        database.analysisDao().upsert(analyses)
    }

    @Test
    fun `voyages, souvenir général et souvenir avec une personne, sans doublon`() = runTest {
        homeLife(2024)
        homeLife(2025)
        // Il y a un an : départ pour Lisbonne le 9 juin au matin, toute la fenêtre en voyage.
        photo(LocalDate.of(2025, 6, 9).atTime(7, 0), PARIS)
        val lisbon = stay(LISBON, LocalDate.of(2025, 6, 9), LocalDate.of(2025, 6, 18))
        // Il y a deux ans : cinq jours à la maison, puis Rome du 14 au 17 juin.
        val home = stay(PARIS, LocalDate.of(2024, 6, 9), LocalDate.of(2024, 6, 13), hours = listOf(9, 18))
        val rome = stay(ROME, LocalDate.of(2024, 6, 14), LocalDate.of(2024, 6, 17))
        save()
        val faces = database.faceDao()
        val alice = faces.insertPerson(PersonEntity(name = "Alice", createdAt = 0))
        val bob = faces.insertPerson(PersonEntity(name = "Bob", hidden = true, createdAt = 0))
        val dan = faces.insertPerson(PersonEntity(name = "Dan", createdAt = 0))
        val window = home + rome.take(6)
        // Alice figure aussi sur la meilleure photo de la fenêtre, déjà couverture du souvenir général.
        faces.insertFaces((home.take(5) + rome[5]).map { face(it, alice) })
        faces.insertFaces(home.take(8).map { face(it, bob) })
        faces.insertFaces(window.take(14).map { face(it, dan) })

        val memories = repository.memories(today, utc)

        assertEquals(4, memories.size)
        val (lisbonTrip, romeTrip, general, withAlice) = memories
        // Ville titrée dans les deux langues, d'après la base embarquée.
        val lisbonne = PlaceName("Lisbon", french = "Lisbonne")
        assertEquals(MemoryKind.Trip(1, lisbonne, "PT", LocalDate.of(2025, 6, 9), LocalDate.of(2025, 6, 18)), lisbonTrip.kind)
        // Le départ de la maison, le matin, n'en fait pas partie.
        assertEquals(millis(LocalDate.of(2025, 6, 9).atTime(10, 0)), lisbonTrip.query.dateFrom)
        assertEquals(lisbon.size, lisbonTrip.count)
        assertEquals(MemoryKind.Trip(2, PlaceName("Rome"), "IT", LocalDate.of(2024, 6, 14), LocalDate.of(2024, 6, 17)), romeTrip.kind)
        assertEquals(rome.size, romeTrip.count)
        assertEquals(MemoryKind.YearsAgo(2), general.kind)
        assertEquals(window.size, general.count)
        // Bob est masqué, Dan figure sur presque toute la fenêtre : Alice.
        val together = withAlice.kind as MemoryKind.WithPerson
        assertEquals(listOf<Any?>(2, alice, "Alice"), listOf(together.yearsAgo, together.personId, together.name))
        assertNotNull(together.face)
        assertEquals(6, withAlice.count)
        assertEquals(alice, withAlice.query.personId)
        // Couvertures distinctes : Alice prend sa meilleure photo encore libre.
        assertEquals(rome[5], general.cover.id)
        assertEquals(home[4], withAlice.cover.id)
        assertEquals(4, memories.map { it.cover.id }.toSet().size)
    }

    @Test
    fun `un voyage d'un dossier masqué ou de la corbeille n'est pas un souvenir`() = runTest {
        homeLife(2025)
        stay(LISBON, LocalDate.of(2025, 6, 9), LocalDate.of(2025, 6, 11), bucket = 9)
        stay(ROME, LocalDate.of(2025, 6, 13), LocalDate.of(2025, 6, 15), trashed = true)
        val home = stay(PARIS, LocalDate.of(2025, 6, 12), LocalDate.of(2025, 6, 12))
        save()
        settings.setFolderHidden(9, true)

        val memories = repository.memories(today, utc)

        assertEquals(listOf<MemoryKind>(MemoryKind.YearsAgo(1)), memories.map { it.kind })
        assertEquals(home.size, memories.single().count)
    }

    @Test
    fun `points datés des seuls médias géolocalisés visibles, par date`() = runTest {
        photo(LocalDateTime.of(2025, 6, 2, 12, 0), PARIS)
        photo(LocalDateTime.of(2025, 6, 1, 12, 0), LISBON)
        photo(LocalDateTime.of(2025, 6, 3, 12, 0), null)
        photo(LocalDateTime.of(2025, 6, 4, 12, 0), ROME, trashed = true)
        photo(LocalDateTime.of(2025, 6, 5, 12, 0), ROME, bucket = 9)
        save()

        val points = database.memoryDao().datedPoints(MemoryQueries.datedPoints(MediaQuery(excludedBuckets = listOf(9))))

        assertEquals(listOf(LISBON.latitude, PARIS.latitude), points.map { it.latitude })
    }

    @Test
    fun `présences comptées en médias distincts, et en mois pour une personne sans nom`() = runTest {
        val inWindow = stay(PARIS, LocalDate.of(2025, 6, 10), LocalDate.of(2025, 6, 11))
        val earlier = listOf(3, 5, 8).map { photo(LocalDate.of(2024, it, 1).atTime(12, 0), PARIS) }
        val trashed = photo(LocalDate.of(2024, 9, 1).atTime(12, 0), PARIS, trashed = true)
        save()
        val faces = database.faceDao()
        val named = faces.insertPerson(PersonEntity(name = "Alice", createdAt = 0))
        val unnamed = faces.insertPerson(PersonEntity(createdAt = 0))
        val hidden = faces.insertPerson(PersonEntity(name = "Bob", hidden = true, createdAt = 0))
        // Deux visages d'Alice sur une même photo : un seul média.
        faces.insertFaces(listOf(face(inWindow[0], named), face(inWindow[0], named), face(inWindow[1], named)))
        faces.insertFaces((inWindow.take(2) + earlier + trashed).map { face(it, unnamed) })
        faces.insertFaces(inWindow.map { face(it, hidden) })
        val window = MediaQuery(
            dateFrom = millis(LocalDate.of(2025, 6, 9).atStartOfDay()),
            dateTo = millis(LocalDate.of(2025, 6, 16).atStartOfDay()),
        )

        val presences = database.memoryDao().people(MemoryQueries.people(window)).sortedBy { it.personId }

        // Personne sans nom : juin 2025, mars, mai et août 2024 (la photo de la corbeille ne compte pas).
        assertEquals(listOf(PersonPresence(named, "Alice", 2, 0), PersonPresence(unnamed, null, 2, 4)), presences)
    }

    private companion object {
        val PARIS = Spot("Paris", "FR", 48.8566, 2.3522)
        val LISBON = Spot("Lisbon", "PT", 38.7223, -9.1393)
        val ROME = Spot("Rome", "IT", 41.9028, 12.4964)
    }
}
