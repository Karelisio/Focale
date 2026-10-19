package app.focale.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceDaoTest {

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

    private fun photo(id: Long, trashed: Boolean = false, screenshot: Boolean = false) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/", bucketId = 1, bucketName = "Camera", dateTaken = id * 1000,
        dateAdded = id * 1000, dateModified = id, generation = 0, size = 1, width = 100, height = 100,
        orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = screenshot,
        isGif = false, isTrashed = trashed, dateExpires = 0,
    )

    private fun face(mediaId: Long, personId: Long? = null, quality: Float = 0.5f, manual: Boolean = false) = FaceEntity(
        mediaId = mediaId, personId = personId, boxLeft = 0.1f, boxTop = 0.1f, boxRight = 0.3f, boxBottom = 0.3f,
        embedding = ByteArray(8), quality = quality, manual = manual,
    )

    private suspend fun person(name: String? = null) = faces.insertPerson(PersonEntity(name = name, createdAt = 0))

    @Test
    fun `une personne sans nom n'apparaît qu'à partir de trois photos hors corbeille`() = runTest {
        media.upsert((1L..6L).map { photo(it, trashed = it == 6L) })
        val often = person()
        val rarely = person()
        val named = person("Zoé")
        faces.insertFaces(listOf(face(1, often), face(2, often), face(3, often), face(6, often)))
        faces.insertFaces(listOf(face(4, rarely), face(5, rarely), face(6, rarely)))
        faces.insertFaces(listOf(face(5, named)))

        val visible = faces.observePeople(includeHidden = false, minPhotos = 3).first()
        assertEquals(listOf(often, named), visible.map { it.id })
        assertEquals(3, visible.first().photoCount)
    }

    @Test
    fun `la vignette choisie l'emporte tant qu'elle appartient à la personne`() = runTest {
        media.upsert((1L..3L).map { photo(it) })
        val id = person("Léa")
        val (low, best, chosen) = faces.insertFaces(listOf(face(1, id, 0.2f), face(2, id, 0.9f), face(3, id, 0.4f)))
        assertEquals(best, faces.observePerson(id).first()?.coverFaceId)
        faces.setCover(id, chosen)
        assertEquals(chosen, faces.observePerson(id).first()?.coverFaceId)
        // Visage retiré de la personne : retour au meilleur visage.
        faces.detach(id, listOf(3L))
        assertEquals(best, faces.observePerson(id).first()?.coverFaceId)
        assertTrue(low > 0)
    }

    @Test
    fun `fusionner déplace les visages, garde un nom et supprime la source`() = runTest {
        media.upsert((1L..4L).map { photo(it) })
        val source = person("Tom")
        val target = person()
        faces.insertFaces(listOf(face(1, source), face(2, source), face(3, target), face(4, target)))
        faces.merge(source, target)
        assertNull(faces.person(source))
        assertEquals("Tom", faces.person(target)?.name)
        assertEquals(4, faces.observePerson(target).first()?.photoCount)
    }

    @Test
    fun `retirer une photo est une décision manuelle que le regroupement respecte`() = runTest {
        media.upsert((1L..3L).map { photo(it) })
        val id = person()
        val (kept, removed) = faces.insertFaces(listOf(face(1, id), face(2, id)))
        val free = faces.insertFaces(listOf(face(3))).single()
        val links = faces.linksIn(id, listOf(2L))
        faces.detach(id, listOf(2L))

        // Le regroupement ne peut ni reprendre le visage retiré ni toucher aux autres.
        val (_, refused) = faces.saveClustering(emptyList(), mapOf(removed to id, kept to id, free to id), now = 0)
        assertEquals(2, refused)
        assertEquals(setOf(kept, free), faces.facesOfMedia(listOf(1L, 2L, 3L)).filter { it.personId == id }.map { it.id }.toSet())

        // Annulation du retrait.
        faces.relink(links.map { it.id }, id, manual = false)
        assertEquals(id, faces.facesOfMedia(listOf(2L)).single().personId)
    }

    @Test
    fun `les personnes créées par le regroupement reçoivent un identifiant et les vides disparaissent`() = runTest {
        media.upsert((1L..2L).map { photo(it) })
        val empty = person()
        val (a, b) = faces.insertFaces(listOf(face(1), face(2)))
        val (ids, refused) = faces.saveClustering(listOf(-1L), mapOf(a to -1L, b to -1L), now = 0)
        assertEquals(0, refused)
        val created = ids.getValue(-1L)
        assertEquals(2, faces.observePerson(created).first()?.photoCount)
        assertNull(faces.person(empty))
    }

    @Test
    fun `l'analyse des visages ignore captures d'écran et corbeille`() = runTest {
        media.upsert(listOf(photo(1), photo(2, screenshot = true), photo(3, trashed = true)))
        assertEquals(listOf(1L), faces.pending(version = 1, limit = 10).map { it.id })
        faces.upsertScans(listOf(FaceScanEntity(1, analyzedModified = 1, version = 1, faceCount = 0)))
        assertTrue(faces.pending(version = 1, limit = 10).isEmpty())
        // Nouvelle version de l'étape : la photo est relue.
        assertEquals(listOf(1L), faces.pending(version = 2, limit = 10).map { it.id })
    }
}
