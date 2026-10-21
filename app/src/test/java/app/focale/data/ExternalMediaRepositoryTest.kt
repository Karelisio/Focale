package app.focale.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaDao
import app.focale.core.database.MediaEntity
import app.focale.core.mediastore.MediaStoreLookup
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExternalMediaRepositoryTest {

    private lateinit var database: FocaleDatabase
    private lateinit var media: MediaDao
    private val lookup = FakeLookup()

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        media = database.mediaDao()
    }

    @After
    fun close() = database.close()

    private fun repository() = ExternalMediaRepository(
        dao = media,
        lookup = lookup,
        resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver,
        // Attente de synchronisation en temps réel : la base Room notifie depuis ses propres threads.
        io = Dispatchers.IO,
    )

    private fun photo(id: Long, bucketId: Long = 1, trashed: Boolean = false) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/", bucketId = bucketId, bucketName = "Camera", dateTaken = id * 1000,
        dateAdded = id * 1000, dateModified = id, generation = 0, size = 1, width = 100, height = 100,
        orientation = 0, duration = 0, volumeName = "external_primary", isScreenshot = false,
        isGif = false, isTrashed = trashed, dateExpires = 0,
    )

    @Test
    fun `une URI MediaStore désigne le média de l'index, dossier masqué compris`() = runTest {
        media.upsert(listOf(photo(42, bucketId = 7)))

        val item = repository().find(Uri.parse("content://media/external/images/media/42"))

        assertEquals(42L, item?.id)
        assertEquals(7L, item?.bucketId)
        assertEquals(0, lookup.syncRequests)
    }

    @Test
    fun `un document du stockage partagé est converti par MediaStore`() = runTest {
        media.upsert(listOf(photo(12)))
        val document = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2FIMG_12.jpg")
        lookup.mediaUris[document] = Uri.parse("content://media/external_primary/images/media/12")

        assertEquals(12L, repository().find(document)?.id)
    }

    @Test
    fun `un chemin file est retrouvé par MediaStore`() = runTest {
        media.upsert(listOf(photo(3)))
        lookup.paths["/storage/emulated/0/DCIM/Camera/IMG_3.jpg"] = 3

        assertEquals(3L, repository().find(Uri.parse("file:///storage/emulated/0/DCIM/Camera/IMG_3.jpg"))?.id)
    }

    @Test
    fun `le fichier d'une autre application reste hors index`() = runTest {
        media.upsert(listOf(photo(1)))
        val document = Uri.parse("content://com.android.providers.media.documents/document/image%3A99")

        assertNull(repository().find(Uri.parse("content://com.whatsapp.provider.media/item/1")))
        // Document sans équivalent MediaStore (refus, fournisseur non pris en charge).
        assertNull(repository().find(document))
        assertNull(repository().find(Uri.parse("file:///storage/emulated/0/Download/inconnu.jpg")))
        assertEquals(0, lookup.syncRequests)
    }

    @Test
    fun `un média qui vient d'arriver est attendu le temps d'une synchronisation`() = runTest {
        lookup.visible += 77
        lookup.onSync = { thread { runBlocking { media.upsert(listOf(photo(77))) } } }

        val item = repository().find(Uri.parse("content://media/external/images/media/77"))

        assertEquals(77L, item?.id)
        assertEquals(1, lookup.syncRequests)
    }

    @Test
    fun `un média hors de l'accès accordé n'est pas attendu`() = runTest {
        val start = System.nanoTime()

        assertNull(repository().find(Uri.parse("content://media/external/images/media/5")))
        assertEquals(0, lookup.syncRequests)
        assertTrue((System.nanoTime() - start) / 1_000_000 < ExternalMediaRepository.ARRIVAL_TIMEOUT_MS)
    }

    @Test
    fun `sans accès à la galerie, l'index périmé n'est pas consulté`() = runTest {
        media.upsert(listOf(photo(6)))
        lookup.access = false

        assertNull(repository().find(Uri.parse("content://media/external/images/media/6")))
    }

    @Test
    fun `un média à la corbeille s'ouvre hors index`() = runTest {
        media.upsert(listOf(photo(8, trashed = true)))

        assertNull(repository().find(Uri.parse("content://media/external/images/media/8")))
    }

    @Test
    fun `un fichier hors index est décrit d'après l'intention et son nom`() = runTest {
        val described = repository().describe(Uri.parse("file:///storage/emulated/0/Download/clip.mp4"), declaredType = "video/mp4")

        assertEquals("clip.mp4", described.displayName)
        assertEquals("video/mp4", described.mimeType)
        assertTrue(described.isVideo)
        assertFalse(described.canHandOver)
    }

    /** MediaStore scripté : accès, conversions de documents et de chemins, médias visibles, synchronisations. */
    private class FakeLookup : MediaStoreLookup {
        var access = true
        val mediaUris = mutableMapOf<Uri, Uri>()
        val paths = mutableMapOf<String, Long>()
        val visible = mutableSetOf<Long>()
        var onSync: () -> Unit = {}
        var syncRequests = 0

        override fun hasMediaAccess(): Boolean = access
        override fun mediaUriOf(document: Uri): Uri? = mediaUris[document]
        override fun idOfPath(path: String): Long? = paths[path]
        override fun isVisible(id: Long): Boolean = id in visible
        override fun requestSync() {
            syncRequests++
            onSync()
        }
    }
}
