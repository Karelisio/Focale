package app.focale.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.settings.PeopleSort
import app.focale.people.FaceEmbeddings
import app.focale.people.MergeSuggestions
import app.focale.people.PeopleEditions
import app.focale.people.PeopleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Liste des personnes : épinglage, tri, paires refusées et suggestions de fusion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeopleListDaoTest {

    private lateinit var database: FocaleDatabase
    private lateinit var media: MediaDao
    private lateinit var faces: FaceDao

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        media = database.mediaDao()
        faces = database.faceDao()
    }

    @After
    fun close() = database.close()

    private fun photo(id: Long, trashed: Boolean = false) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/", bucketId = 1, bucketName = "Camera", dateTaken = id * 1000,
        dateAdded = id * 1000, dateModified = id, generation = 0, size = 1, width = 100, height = 100,
        orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = false,
        isGif = false, isTrashed = trashed, dateExpires = 0,
    )

    private fun face(mediaId: Long, personId: Long?, embedding: FloatArray = FloatArray(FaceEmbeddings.DIMENSION)) = FaceEntity(
        mediaId = mediaId, personId = personId, boxLeft = 0.1f, boxTop = 0.1f, boxRight = 0.3f, boxBottom = 0.3f,
        embedding = FaceEmbeddings.encode(embedding), quality = 0.5f,
    )

    /** Personne présente sur les photos [photos] (créées au besoin). */
    private suspend fun person(name: String?, photos: LongRange, hidden: Boolean = false, pinned: Boolean = false): Long {
        media.upsert(photos.map { photo(it) })
        val id = faces.insertPerson(PersonEntity(name = name, hidden = hidden, pinned = pinned, createdAt = 0))
        faces.insertFaces(photos.map { face(it, id) })
        return id
    }

    @Test
    fun `les personnes épinglées passent en tête, avec la date de leur photo la plus récente`() = runTest {
        val many = person(null, 1L..6L)
        val few = person(null, 7L..9L)
        val named = person("Léa", 10L..11L)
        // Photo à la corbeille : ni comptée ni prise pour la plus récente.
        media.upsert(listOf(photo(20, trashed = true)))
        faces.insertFaces(listOf(face(20, many)))

        assertEquals(listOf(many, few, named), faces.observePeopleList(includeHidden = false, minPhotos = 3).first().map { it.id })
        faces.setPinned(named, true)
        val rows = faces.observePeopleList(includeHidden = false, minPhotos = 3).first()
        assertEquals(listOf(named, many, few), rows.map { it.id })
        assertEquals(listOf(true, false, false), rows.map { it.pinned })
        assertEquals(6_000L, rows.first { it.id == many }.latestTaken)
        assertEquals(6, rows.first { it.id == many }.photoCount)

        faces.setPinned(named, false)
        assertEquals(listOf(many, few, named), faces.observePeopleList(includeHidden = false, minPhotos = 3).first().map { it.id })
    }

    @Test
    fun `tri par nombre de photos, par nom sans casse ni accents, ou par photo récente`() = runTest {
        val zoe = person("Zoé", 1L..3L, pinned = true)
        val emile = person("Émile", 4L..7L)
        val eve = person("eve", 8L..9L)
        val anna = person("anna", 10L..14L)
        val bob = person("Bob", 15L..15L, pinned = true)
        val crowd = person(null, 16L..21L)
        val recent = person(null, 22L..24L)
        person(null, 25L..26L) // Sans nom et sur deux photos seulement : absente.
        person("Masquée", 27L..30L, hidden = true)
        val repository = PeopleRepository(faces, PeopleEditions(), Dispatchers.IO)

        suspend fun order(sort: PeopleSort) = repository.observePeople(sort).first().map { it.id }

        assertEquals(listOf(zoe, bob, crowd, anna, emile, recent, eve), order(PeopleSort.PHOTOS))
        assertEquals(listOf(bob, zoe, anna, emile, eve, crowd, recent), order(PeopleSort.NAME))
        assertEquals(listOf(bob, zoe, recent, crowd, anna, eve, emile), order(PeopleSort.RECENT))
        assertTrue(repository.observePeople(PeopleSort.NAME).first().first().pinned)
        assertEquals(8, repository.observePeople(PeopleSort.NAME, includeHidden = true).first().size)
    }

    @Test
    fun `une paire refusée est rangée dans l'ordre et disparaît avec l'une des personnes`() = runTest {
        val a = person(null, 1L..3L)
        val b = person(null, 4L..6L)
        val c = person("Léa", 7L..9L)
        PeopleRepository(faces, PeopleEditions(), Dispatchers.IO).refuseMerge(b, a)
        faces.refuse(minOf(a, c), maxOf(a, c))
        faces.refuse(minOf(a, c), maxOf(a, c))
        assertEquals(setOf(a to b, a to c), faces.observeRefusals().first().map { it.personLow to it.personHigh }.toSet())

        // Personne disparue entre-temps : rien n'est enregistré, sans erreur.
        faces.refuse(b, 999)
        assertEquals(2, faces.observeRefusals().first().size)

        // Fusion : les refus de la source passent à la cible (sauf celui qui la concerne), et
        // son épingle aussi.
        faces.setPinned(a, true)
        faces.merge(sourceId = a, targetId = c)
        assertEquals(setOf(minOf(b, c) to maxOf(b, c)), faces.observeRefusals().first().map { it.personLow to it.personHigh }.toSet())
        assertTrue(checkNotNull(faces.person(c)).pinned)
        faces.deletePerson(c)
        assertTrue(faces.observeRefusals().first().isEmpty())

        // Suppression d'une personne restée sans visage (regroupement).
        val d = person(null, 10L..12L)
        faces.refuse(minOf(b, d), maxOf(b, d))
        faces.detach(d, listOf(10L, 11L, 12L))
        faces.saveClustering(emptyList(), emptyMap(), now = 0)
        assertTrue(faces.observeRefusals().first().isEmpty())
    }

    @Test
    fun `les candidats aux suggestions sont les personnes visibles, avec la signature de leurs visages`() = runTest {
        val visible = person(null, 1L..3L)
        val named = person("Léa", 4L..4L)
        person(null, 5L..6L)
        person("Masquée", 7L..9L, hidden = true)
        val rows = faces.observeMergeCandidates(minPhotos = 3).first().associateBy { it.id }
        assertEquals(setOf(visible, named), rows.keys)
        val ids = faces.facesOfMedia(listOf(1L, 2L, 3L)).map { it.id }.sorted()
        assertEquals(3, rows.getValue(visible).faceCount)
        assertEquals(ids.sum(), rows.getValue(visible).faceSum)

        // Empreintes lues par tranches d'identifiant croissant.
        val people = listOf(visible, named)
        val first = faces.personEmbeddings(people, after = 0, limit = 2)
        val second = faces.personEmbeddings(people, after = first.last().id, limit = 2)
        assertEquals(ids + faces.facesOfMedia(listOf(4L)).single().id, (first + second).map { it.id })
        assertEquals(listOf(visible, visible, visible, named), (first + second).map { it.personId })
        assertTrue(faces.personEmbeddings(people, after = second.last().id, limit = 2).isEmpty())
    }

    @Test
    fun `une suggestion apparaît pour deux groupes proches et disparaît après un refus ou une fusion`() = runTest {
        val axis = FloatArray(FaceEmbeddings.DIMENSION).also { it[0] = 1f }
        fun near(similarity: Float, other: Int) = FloatArray(FaceEmbeddings.DIMENSION).also {
            it[0] = similarity
            it[other] = sqrt(1 - similarity * similarity)
        }
        media.upsert((1L..9L).map { photo(it) })
        val lea = faces.insertPerson(PersonEntity(name = "Léa", createdAt = 0))
        val unnamed = faces.insertPerson(PersonEntity(createdAt = 0))
        val stranger = faces.insertPerson(PersonEntity(createdAt = 0))
        faces.insertFaces((1L..3L).map { face(it, lea, axis) })
        faces.insertFaces((4L..6L).map { face(it, unnamed, near(0.8f, 1)) })
        faces.insertFaces((7L..9L).map { face(it, stranger, near(0.1f, 2)) })
        val suggestions = MergeSuggestions(faces, Dispatchers.Default)

        val first = suggestions.observe().first().single()
        assertEquals(lea to unnamed, first.targetId to first.sourceId)
        assertEquals(0.8f, first.similarity, 1e-4f)

        faces.refuse(minOf(lea, unnamed), maxOf(lea, unnamed))
        assertTrue(suggestions.observe().first().isEmpty())

        // Nouveaux visages chez l'inconnu, proches de Léa : son centroïde est recalculé.
        media.upsert((10L..12L).map { photo(it) })
        faces.insertFaces((10L..12L).map { face(it, stranger, axis) })
        // Léa d'abord (≈ 0,74), puis l'inconnu et la personne sans nom (≈ 0,59) : le plus de photos est la cible.
        val again = suggestions.observe().first()
        assertEquals(listOf(lea to stranger, stranger to unnamed), again.map { it.targetId to it.sourceId })

        faces.merge(sourceId = stranger, targetId = lea)
        assertTrue(suggestions.observe().first().isEmpty())
    }
}
