package app.focale.people

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FaceDao
import app.focale.core.database.FaceEntity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.PersonEntity
import app.focale.core.model.MediaQuery
import app.focale.ml.SearchQueryParser
import app.focale.ui.people.togetherTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Photos où plusieurs personnes figurent ensemble (« Avec… »), sur une vraie base Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeopleTogetherTest {

    private lateinit var database: FocaleDatabase
    private lateinit var faces: FaceDao
    private lateinit var repository: PeopleRepository
    private var alice = 0L
    private var bob = 0L
    private var chloe = 0L
    private var unnamed = 0L
    private var zoe = 0L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        faces = database.faceDao()
        repository = PeopleRepository(faces, PeopleEditions(), Dispatchers.IO)
        runBlocking {
            // La photo 7 est à la corbeille.
            database.mediaDao().upsert((1L..7L).map { photo(it, trashed = it == 7L) })
            alice = person("Alice", 1, 2, 3, 4, 7)
            bob = person("Bob", 2, 3, 5, 7)
            chloe = person("Chloé", 1, 3, 4)
            unnamed = person(null, 3, 4, 5, 6)
            zoe = person("Zoé", 6)
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `les photos ensemble sont celles où figurent toutes les personnes choisies`() {
        assertEquals(setOf(2L, 3L), ids(photosTogether(listOf(alice, bob))))
        assertEquals(setOf(3L), ids(photosTogether(listOf(alice, bob, chloe))))
        assertEquals(setOf(3L, 4L), ids(photosTogether(listOf(alice, unnamed))))
        assertEquals(emptySet<Long>(), ids(photosTogether(listOf(alice, zoe))))
        // Un terme par personne, combinés par ET.
        assertEquals(2, photosTogether(listOf(alice, bob, alice)).terms.size)
    }

    @Test
    fun `la recherche « alice bob » donne les mêmes photos`() = runBlocking {
        val named = repository.observeNamed().first().associate { it.id to it.name.orEmpty() }
        val search = SearchQueryParser.parse("alice bob", people = named)
        assertEquals(setOf(2L, 3L), ids(search))
        assertEquals(ids(photosTogether(listOf(alice, bob))), ids(search))
        // Sans accent ni majuscule, Chloé est reconnue aussi.
        assertEquals(setOf(3L), ids(SearchQueryParser.parse("Alice bob chloe", people = named)))
    }

    @Test
    fun `les personnes vues avec Alice sont comptées, nommées d'abord puis les plus fréquentes`() = runBlocking {
        val together = repository.observeTogether(alice).first()
        assertEquals(listOf(chloe, bob, unnamed), together.map { it.person.id })
        assertEquals(listOf(3, 2, 2), together.map { it.photoCount })
    }

    @Test
    fun `les autres personnes proposées sont les nommées d'abord`() = runBlocking {
        val others = repository.observeOthers(alice).first().map { it.id }
        assertEquals(listOf(bob, chloe, zoe, unnamed), others)
    }

    @Test
    fun `titre en anglais`() {
        assertEquals("Alice and Bob", title(alice, bob))
        assertEquals("Alice, Bob and Chloé", title(alice, bob, chloe))
        assertEquals("Alice and Unnamed person", title(alice, unnamed))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `titre en français`() {
        assertEquals("Alice et Bob", title(alice, bob))
        assertEquals("Alice, Bob et Chloé", title(alice, bob, chloe))
        assertEquals("Alice, Bob, Chloé et Personne sans nom", title(alice, bob, chloe, unnamed))
    }

    private fun title(vararg ids: Long): String = runBlocking {
        val people = ids.map { id -> checkNotNull(repository.observePerson(id).first()) }
        togetherTitle(ApplicationProvider.getApplicationContext<Context>().resources, people)
    }

    private fun ids(query: MediaQuery): Set<Long> = runBlocking {
        database.mediaDao().list(MediaQueryBuilder.select(query)).map { it.id }.toSet()
    }

    private suspend fun person(name: String?, vararg mediaIds: Long): Long {
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

    private fun photo(id: Long, trashed: Boolean) = MediaEntity(
        id = id, mediaType = MediaQueryBuilder.MEDIA_TYPE_IMAGE, mimeType = "image/jpeg", displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/", bucketId = 1, bucketName = "Camera", dateTaken = id * 1000,
        dateAdded = id * 1000, dateModified = id, generation = 0, size = 1, width = 100, height = 100,
        orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = false,
        isGif = false, isTrashed = trashed, dateExpires = 0,
    )
}
