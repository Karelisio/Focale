package app.focale.people

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FaceDao
import app.focale.core.database.FaceEntity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEntity
import app.focale.core.database.PersonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Retrait et déplacement de visages depuis la revue, et leur annulation, sur une vraie base Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FaceTransferTest {

    private lateinit var database: FocaleDatabase
    private lateinit var faces: FaceDao
    private val editions = PeopleEditions()
    private lateinit var repository: PeopleRepository

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        faces = database.faceDao()
        repository = PeopleRepository(faces, editions, Dispatchers.IO)
    }

    @After
    fun close() = database.close()

    private fun photo(id: Long) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/", bucketId = 1, bucketName = "Camera", dateTaken = id * 1000,
        dateAdded = id * 1000, dateModified = id, generation = 0, size = 1, width = 100, height = 100,
        orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = false,
        isGif = false, isTrashed = false, dateExpires = 0,
    )

    private fun face(
        mediaId: Long,
        personId: Long?,
        quality: Float = 0.5f,
        manual: Boolean = false,
        embedding: FloatArray = FloatArray(FaceEmbeddings.DIMENSION),
    ) = FaceEntity(
        mediaId = mediaId, personId = personId, boxLeft = 0.1f, boxTop = 0.1f, boxRight = 0.3f, boxBottom = 0.3f,
        embedding = FaceEmbeddings.encode(embedding), quality = quality, manual = manual,
    )

    private suspend fun person(name: String? = null) = faces.insertPerson(PersonEntity(name = name, createdAt = 0))

    private suspend fun link(faceId: Long) = faces.facesOfMedia((1L..10L).toList()).single { it.id == faceId }

    @Test
    fun `retirer des visages les détache à la main, et le regroupement s'y tient`() = runTest {
        database.mediaDao().upsert((1L..4L).map { photo(it) })
        val lea = person("Léa")
        val (a1, a2, a3, a4) = faces.insertFaces((1L..4L).map { face(it, lea) })
        val before = editions.current

        val transfer = repository.removeFaces(lea, listOf(a2, a3))
        assertEquals(2, transfer?.count)
        assertTrue(editions.current > before)
        listOf(a2, a3).forEach { id ->
            assertNull(link(id).personId)
            assertTrue(link(id).manual)
        }
        assertEquals(listOf(a1, a4), repository.observeReview(lea).first().map { it.face.faceId }.sorted())
        // Le regroupement ne peut pas reprendre un visage retiré à la main.
        val (_, refused) = faces.saveClustering(emptyList(), mapOf(a2 to lea), now = 0)
        assertEquals(1, refused)
    }

    @Test
    fun `l'annulation rend les visages avec leur rattachement d'origine`() = runTest {
        database.mediaDao().upsert((1L..3L).map { photo(it) })
        val lea = person("Léa")
        val (auto, chosen, kept) = faces.insertFaces(listOf(face(1, lea), face(2, lea, manual = true), face(3, lea)))

        val transfer = checkNotNull(repository.removeFaces(lea, listOf(auto, chosen)))
        repository.restore(transfer)
        assertEquals(lea, link(auto).personId)
        assertFalse(link(auto).manual)
        assertEquals(lea, link(chosen).personId)
        assertTrue(link(chosen).manual)
        assertEquals(3, repository.observeReview(lea).first().size)
        assertEquals(lea, link(kept).personId)
    }

    @Test
    fun `déplacer vers une autre personne la rattache à la main, l'annulation la rend`() = runTest {
        database.mediaDao().upsert((1L..5L).map { photo(it) })
        val lea = person("Léa")
        val tom = person("Tom")
        val (_, _, wrong) = faces.insertFaces((1L..3L).map { face(it, lea) })
        faces.insertFaces(listOf(face(4, tom), face(5, tom)))

        val transfer = checkNotNull(repository.moveFaces(lea, listOf(wrong), tom))
        assertEquals(tom, link(wrong).personId)
        assertTrue(link(wrong).manual)
        assertEquals(2, faces.observePerson(lea).first()?.photoCount)
        assertEquals(3, faces.observePerson(tom).first()?.photoCount)

        repository.restore(transfer)
        assertEquals(lea, link(wrong).personId)
        assertFalse(link(wrong).manual)
        assertEquals(2, faces.observePerson(tom).first()?.photoCount)
    }

    @Test
    fun `déplacer vers une nouvelle personne la crée, l'annulation la supprime`() = runTest {
        database.mediaDao().upsert((1L..3L).map { photo(it) })
        val lea = person("Léa")
        val (_, b, c) = faces.insertFaces((1L..3L).map { face(it, lea) })

        val named = checkNotNull(repository.moveFacesToNewPerson(lea, listOf(b, c), "  Zoé "))
        val zoe = checkNotNull(link(b).personId)
        assertEquals("Zoé", faces.person(zoe)?.name)
        assertEquals(2, faces.observePerson(zoe).first()?.photoCount)
        repository.restore(named)
        assertNull(faces.person(zoe))
        assertEquals(lea, link(b).personId)

        // Sans nom : une personne sans nom, que l'annulation supprime aussi.
        val unnamed = checkNotNull(repository.moveFacesToNewPerson(lea, listOf(c), " "))
        val stranger = checkNotNull(link(c).personId)
        assertNull(faces.person(stranger)?.name)
        repository.restore(unnamed)
        assertNull(faces.person(stranger))
    }

    @Test
    fun `une personne vidée disparaît, l'annulation la recrée`() = runTest {
        database.mediaDao().upsert((1L..3L).map { photo(it) })
        val lea = person("Léa")
        faces.setHidden(lea, true)
        val ids = faces.insertFaces((1L..3L).map { face(it, lea) })

        val transfer = checkNotNull(repository.removeFaces(lea, ids))
        assertTrue(faces.observePeople(includeHidden = true, minPhotos = 1).first().none { it.id == lea })
        assertTrue(repository.observeReview(lea).first().isEmpty())
        // Passage du regroupement : la personne vide est supprimée.
        faces.deleteEmptyPersons()
        assertNull(faces.person(lea))

        repository.restore(transfer)
        val restored = checkNotNull(faces.person(lea))
        assertEquals("Léa", restored.name)
        assertTrue(restored.hidden)
        assertEquals(3, faces.observePerson(lea).first()?.photoCount)
    }

    @Test
    fun `la vignette retirée ou déplacée retombe sur le meilleur visage`() = runTest {
        database.mediaDao().upsert((1L..4L).map { photo(it) })
        val lea = person("Léa")
        val tom = person("Tom")
        val (_, best, chosen) = faces.insertFaces(listOf(face(1, lea, 0.2f), face(2, lea, 0.9f), face(3, lea, 0.4f)))
        val tomFace = faces.insertFaces(listOf(face(4, tom, 0.3f))).single()
        assertEquals(tomFace, faces.observePerson(tom).first()?.coverFaceId)
        faces.setCover(lea, chosen)
        assertEquals(chosen, faces.observePerson(lea).first()?.coverFaceId)

        val removal = checkNotNull(repository.removeFaces(lea, listOf(chosen)))
        assertEquals(best, faces.observePerson(lea).first()?.coverFaceId)
        repository.restore(removal)
        assertEquals(chosen, faces.observePerson(lea).first()?.coverFaceId)

        // Déplacée : Léa retombe sur son meilleur visage, Tom prend le sien (meilleur que l'ancien).
        repository.moveFaces(lea, listOf(chosen), tom)
        assertEquals(best, faces.observePerson(lea).first()?.coverFaceId)
        assertEquals(chosen, faces.observePerson(tom).first()?.coverFaceId)
    }

    @Test
    fun `seuls les visages de la personne sont concernés, vers une personne existante`() = runTest {
        database.mediaDao().upsert((1L..2L).map { photo(it) })
        val lea = person("Léa")
        val tom = person("Tom")
        val leaFace = faces.insertFaces(listOf(face(1, lea))).single()
        val tomFace = faces.insertFaces(listOf(face(2, tom))).single()

        assertNull(repository.removeFaces(lea, listOf(tomFace)))
        assertEquals(tom, link(tomFace).personId)
        assertNull(repository.moveFaces(lea, listOf(leaFace), lea))
        // Personne d'arrivée disparue entre-temps : rien ne bouge, aucun visage orphelin.
        assertNull(repository.moveFaces(lea, listOf(leaFace), 999))
        assertEquals(lea, link(leaFace).personId)
        assertFalse(link(leaFace).manual)
        assertNull(repository.removeFaces(888, listOf(leaFace)))
    }

    @Test
    fun `la revue lit les empreintes et place l'intrus en tête`() = runTest {
        database.mediaDao().upsert((1L..5L).map { photo(it) })
        val lea = person("Léa")
        fun unit(vararg pairs: Pair<Int, Float>) =
            FaceEmbeddings.normalize(FloatArray(FaceEmbeddings.DIMENSION).also { v -> pairs.forEach { (i, x) -> v[i] = x } })
        val ids = faces.insertFaces(
            listOf(
                face(1, lea, embedding = unit(0 to 1f, 1 to 0.1f)),
                face(2, lea, embedding = unit(0 to 1f, 2 to 0.2f)),
                face(3, lea, embedding = unit(5 to 1f)),
                face(4, lea, embedding = unit(0 to 1f)),
                face(5, lea, embedding = unit(0 to 1f, 3 to 0.3f)),
            ),
        )
        val review = repository.observeReview(lea).first()
        assertEquals(ids[2], review.first().face.faceId)
        assertEquals(3L, review.first().face.mediaId)
        assertEquals(review.map { it.similarity }.sorted(), review.map { it.similarity })
        assertEquals(ids[3], review.last().face.faceId)
    }
}
