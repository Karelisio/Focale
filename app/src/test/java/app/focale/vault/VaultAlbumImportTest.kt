package app.focale.vault

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEntity
import app.focale.core.mediastore.ConsentRequester
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.permissions.MediaAccessManager
import app.focale.data.MediaOperations
import app.focale.data.MediaRepository
import app.focale.data.OperationResult
import app.focale.data.toModel
import app.focale.widget.WidgetRefresher
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.SeekableByteChannel
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Import dans un album du coffre, de bout en bout : MediaStore simulé (contenu, présence des
 * originaux, demande de suppression), base et coffre réels. Compléter un album ne chiffre que
 * les nouveaux médias : les fichiers déjà dans le coffre ne sont ni relus ni réécrits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultAlbumImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = VaultStorage(context)
    private val consent = ConsentRequester()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = Random(77)
    private lateinit var database: FocaleDatabase
    private lateinit var store: FakeMediaStore
    private lateinit var aead: RecordingAead
    private lateinit var repository: VaultRepository

    /** Réponse de l'utilisateur au dialogue système de suppression. */
    @Volatile
    private var approveDeletion = true

    @Before
    fun setUp() {
        storage.directory.listFiles()?.forEach(File::delete)
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).allowMainThreadQueries().build()
        store = Robolectric.buildContentProvider(FakeMediaStore::class.java).create(MediaStore.AUTHORITY).get()
        StreamingAeadConfig.register()
        aead = RecordingAead(
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java),
        )
        // Sans permission de lecture de la galerie, la synchronisation de l'index ne fait rien :
        // le test la remplace par [syncIndex].
        val sync = MediaSyncManager(
            context = context,
            database = database,
            dao = database.mediaDao(),
            source = MediaStoreSource(context.contentResolver),
            accessManager = MediaAccessManager(context),
            stateStore = PreferenceDataStoreFactory.create { File(folder.root, "state.preferences_pb") },
            scope = scope,
            io = Dispatchers.IO,
        )
        val operations = MediaOperations(
            context, context.contentResolver, consent, sync, database.trashDao(), database.favoriteDao(), database.mediaDao(),
            Dispatchers.IO,
        )
        repository = VaultRepository(
            resolver = context.contentResolver,
            dao = database.vaultDao(),
            crypto = VaultCrypto { aead },
            storage = storage,
            operations = operations,
            syncManager = sync,
            media = MediaRepository(database.mediaDao(), Dispatchers.IO),
            widgets = WidgetRefresher(context, scope),
            io = Dispatchers.IO,
        )
        // Dialogue système de suppression : l'utilisateur répond, MediaStore applique.
        scope.launch {
            consent.requests.collect {
                val approved = approveDeletion
                if (approved) store.deletePending()
                consent.complete(approved)
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        storage.directory.listFiles()?.forEach(File::delete)
    }

    @Test
    fun `les nouvelles photos d'un dossier rejoignent son album sans relire ni réécrire le coffre`() = runTest {
        // Premier passage : tout le dossier entre dans le coffre, dans un album qui en garde l'origine.
        gallery(photo(1), photo(2))
        assertEquals(OperationResult.Success(2), repository.importFolder(BUCKET, "WhatsApp Images"))
        val album = repository.observeAlbums().first().single()
        assertEquals("WhatsApp Images", album.name)
        assertEquals("WhatsApp Images", album.sourceName)
        assertEquals(2, album.count)
        assertEquals(0, store.size)
        syncIndex()
        assertTrue(repository.observeNewInSource(album.id).first().isEmpty())

        // Deux photos arrivent ensuite dans le dossier : seules elles sont proposées.
        val existing = vaultFiles()
        aead.touched.clear()
        gallery(photo(3), photo(4))
        assertEquals(listOf(4L, 3L), repository.observeNewInSource(album.id).first().map { it.id })
        val startedAt = System.currentTimeMillis()
        assertEquals(OperationResult.Success(2), repository.importNewFromSource(album.id))

        // Seuls les nouveaux fichiers ont été chiffrés puis relus pour vérification.
        val added = vaultFiles().keys - existing.keys
        assertEquals(2, added.count { it.endsWith(VaultStorage.MEDIA_SUFFIX) })
        assertTrue(aead.touched.containsAll(added))
        assertTrue("Fichiers existants touchés : ${aead.touched.intersect(existing.keys)}", aead.touched.none { it in existing })
        // Les fichiers déjà dans le coffre sont intacts, à l'octet près.
        existing.forEach { (name, bytes) -> assertArrayEquals(name, bytes, storage.file(name).readBytes()) }
        assertEquals(4, repository.observeItems(album.id).first().size)
        assertTrue(database.vaultDao().album(album.id)!!.lastImportAt!! >= startedAt)
        syncIndex()
        assertTrue(repository.observeNewInSource(album.id).first().isEmpty())

        // Le dossier passé à nouveau dans le coffre rejoint le même album.
        gallery(photo(5))
        assertEquals(OperationResult.Success(1), repository.importFolder(BUCKET, "WhatsApp Images"))
        assertEquals(listOf(5), repository.observeAlbums().first().map { it.count })
    }

    @Test
    fun `une sélection rangée dans un album existant ne touche pas au reste du coffre`() = runTest {
        val albumId = repository.createAlbum(" Vacances ")
        gallery(photo(1))
        assertEquals(OperationResult.Success(1), repository.import(listOf(media(1)), VaultDestination.Album(albumId)))
        syncIndex()
        val existing = vaultFiles()
        aead.touched.clear()

        gallery(photo(2), photo(3))
        assertEquals(OperationResult.Success(2), repository.import(listOf(media(2), media(3)), VaultDestination.Album(albumId)))
        assertTrue(aead.touched.none { it in existing })
        existing.forEach { (name, bytes) -> assertArrayEquals(name, bytes, storage.file(name).readBytes()) }
        val album = repository.observeAlbums().first().single()
        assertEquals("Vacances", album.name)
        assertEquals(3, album.count)
        // Album simple : aucun dossier d'origine, donc rien de nouveau à proposer.
        assertTrue(repository.observeNewInSource(albumId).first().isEmpty())
    }

    @Test
    fun `un refus de suppression annule les copies chiffrées et ne laisse aucun album`() = runTest {
        gallery(photo(1), photo(2))
        approveDeletion = false
        assertEquals(OperationResult.Cancelled, repository.importFolder(BUCKET, "WhatsApp Images"))
        assertEquals(OperationResult.Cancelled, repository.import(listOf(media(1)), VaultDestination.NewAlbum("Perso")))
        assertTrue(repository.observeAlbums().first().isEmpty())
        assertTrue(repository.observeItems().first().isEmpty())
        assertTrue(storage.directory.list().isNullOrEmpty())
        // Les originaux sont toujours dans la galerie.
        assertEquals(2, store.size)
    }

    /** Médias arrivés dans la galerie : dans MediaStore et dans l'index. */
    private suspend fun gallery(vararg photos: MediaEntity) {
        photos.forEach { store.put(it.id, random.nextBytes(4_000 + it.id.toInt())) }
        database.mediaDao().upsert(photos.toList())
    }

    /** Ce que ferait la synchronisation : les originaux supprimés de MediaStore quittent l'index. */
    private suspend fun syncIndex() {
        val gone = database.mediaDao().syncKeys().map { it.id }.filter { !store.contains(it) }
        if (gone.isNotEmpty()) database.mediaDao().deleteByIds(gone)
    }

    private suspend fun media(id: Long) = database.mediaDao().get(id)!!.toModel()

    private fun vaultFiles(): Map<String, ByteArray> =
        storage.directory.listFiles().orEmpty().associate { it.name to it.readBytes() }

    private fun photo(id: Long) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG-$id-WA0001.jpg",
        relativePath = "Pictures/WhatsApp Images/", bucketId = BUCKET, bucketName = "WhatsApp Images",
        dateTaken = id * 1_000, dateAdded = System.currentTimeMillis(), dateModified = id, generation = 0,
        size = 4_000 + id, width = 1_600, height = 1_200, orientation = 0, duration = 0,
        volumeName = "external_primary", isScreenshot = false, isGif = false, isTrashed = false, dateExpires = 0,
    )

    /** Chiffrement du coffre qui note le nom de chaque fichier chiffré ou déchiffré (données associées). */
    private class RecordingAead(private val inner: StreamingAead) : StreamingAead by inner {
        val touched: MutableSet<String> = Collections.synchronizedSet(HashSet())

        override fun newEncryptingStream(ciphertext: OutputStream, associatedData: ByteArray): OutputStream {
            touched += associatedData.decodeToString()
            return inner.newEncryptingStream(ciphertext, associatedData)
        }

        override fun newDecryptingStream(ciphertextSource: InputStream, associatedData: ByteArray): InputStream {
            touched += associatedData.decodeToString()
            return inner.newDecryptingStream(ciphertextSource, associatedData)
        }

        override fun newSeekableDecryptingChannel(ciphertextSource: SeekableByteChannel, associatedData: ByteArray): SeekableByteChannel {
            touched += associatedData.decodeToString()
            return inner.newSeekableDecryptingChannel(ciphertextSource, associatedData)
        }
    }

    /**
     * MediaStore réduit à ce qu'utilise l'import : contenu des médias, présence des originaux et
     * demande de suppression groupée (`MediaStore.createDeleteRequest`), appliquée si l'utilisateur
     * accepte.
     */
    class FakeMediaStore : ContentProvider() {
        private val contents = ConcurrentHashMap<Long, ByteArray>()
        @Volatile
        private var pending: List<Long> = emptyList()

        val size: Int get() = contents.size

        fun put(id: Long, bytes: ByteArray) {
            contents[id] = bytes
        }

        fun contains(id: Long): Boolean = contents.containsKey(id)

        fun deletePending() {
            pending.forEach(contents::remove)
        }

        override fun onCreate(): Boolean = true

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val id = ContentUris.parseId(uri)
            return MatrixCursor(arrayOf(MediaStore.MediaColumns._ID)).apply { if (contents.containsKey(id)) addRow(arrayOf(id)) }
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val bytes = contents[ContentUris.parseId(uri)] ?: throw FileNotFoundException(uri.toString())
            val file = File.createTempFile("media", ".jpg", requireNotNull(context).cacheDir).apply { writeBytes(bytes) }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method != "create_delete_request") return null
            val clip = requireNotNull(extras?.getParcelable("clip_data", ClipData::class.java))
            pending = (0 until clip.itemCount).map { ContentUris.parseId(clip.getItemAt(it).uri) }
            val intent = PendingIntent.getActivity(context, 0, Intent(Intent.ACTION_VIEW), PendingIntent.FLAG_IMMUTABLE)
            return Bundle().apply { putParcelable("result", intent) }
        }

        override fun getType(uri: Uri): String = "image/jpeg"

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    }

    private companion object {
        const val BUCKET = 77L
    }
}
