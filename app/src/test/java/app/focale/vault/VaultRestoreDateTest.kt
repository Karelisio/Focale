package app.focale.vault

import android.Manifest
import android.app.Application
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
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.VaultItemEntity
import app.focale.core.mediastore.ConsentRequester
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.permissions.MediaAccessManager
import app.focale.data.MediaOperations
import app.focale.data.MediaRepository
import app.focale.data.OperationResult
import app.focale.widget.WidgetRefresher
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Un média qui sort du coffre retrouve sa date d'origine, dans MediaStore et dans l'index de
 * la galerie, au lieu de revenir en tête de la timeline. MediaStore simulé comme le vrai : à la
 * publication, il efface `DATE_TAKEN`, ne le retrouve que dans les métadonnées du fichier et
 * prend la date du fichier comme `DATE_MODIFIED`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultRestoreDateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = VaultStorage(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val random = Random(14)
    private lateinit var database: FocaleDatabase
    private lateinit var store: FakeMediaStore
    private lateinit var crypto: VaultCrypto
    private lateinit var sync: MediaSyncManager
    private lateinit var repository: VaultRepository

    @Before
    fun setUp() {
        storage.directory.listFiles()?.forEach(File::delete)
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).allowMainThreadQueries().build()
        store = Robolectric.buildContentProvider(FakeMediaStore::class.java).create(MediaStore.AUTHORITY).get()
        store.directory = folder.newFolder("storage")
        StreamingAeadConfig.register()
        val aead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        crypto = VaultCrypto { aead }
        sync = MediaSyncManager(
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
            context, context.contentResolver, ConsentRequester(), sync, database.trashDao(), database.favoriteDao(),
            database.mediaDao(), Dispatchers.IO,
        )
        repository = VaultRepository(
            resolver = context.contentResolver,
            dao = database.vaultDao(),
            crypto = crypto,
            storage = storage,
            operations = operations,
            syncManager = sync,
            media = MediaRepository(database.mediaDao(), Dispatchers.IO, database.invalidationTracker),
            widgets = WidgetRefresher(context, scope),
            io = Dispatchers.IO,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        storage.directory.listFiles()?.forEach(File::delete)
    }

    @Test
    fun `une photo et une vidéo sorties du coffre reprennent leur date dans MediaStore et dans la timeline`() = runTest {
        val photo = vault("IMG_20190714_101500.jpg", JULY_14)
        val video = vault("VID_20200101_000100.mp4", NEW_YEAR, video = true)

        assertEquals(OperationResult.Success(2), repository.restore(listOf(photo, video)))

        // Le fichier publié porte la date d'origine : MediaStore en fait sa date de modification.
        for ((name, date) in listOf(photo.displayName to JULY_14, video.displayName to NEW_YEAR)) {
            val row = store.row(name)
            assertEquals(date / 1000, row.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED))
            // L'index reprend cette date au lieu de celle du jour.
            val indexed = database.mediaDao().get(row.getAsLong(MediaStore.MediaColumns._ID))!!
            assertEquals(date / 1000 * 1000, indexed.dateTaken)
        }
        assertEquals(0, store.count { it.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 1 })
        assertTrue(database.mediaDao().originalDates().isEmpty())
        assertTrue(repository.observeItems().first().isEmpty())
    }

    @Test
    fun `sans accès au fichier, comme sous Android 10, l'index retient la date du coffre`() = runTest {
        store.fileAccess = false
        val photo = vault("Screenshot_2018-03-02.png", MARCH_2, mimeType = "image/png")

        assertEquals(OperationResult.Success(1), repository.restore(listOf(photo)))

        // MediaStore n'a trouvé aucune date : sa date de modification est celle du jour.
        val row = store.row(photo.displayName)
        assertNull(row.getAsLong(MediaStore.MediaColumns.DATE_TAKEN))
        assertTrue(row.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED) > MARCH_2 / 1000 + YEAR_SECONDS)
        val id = row.getAsLong(MediaStore.MediaColumns._ID)
        assertEquals(MARCH_2, database.mediaDao().get(id)!!.dateTaken)

        // Une synchronisation complète ultérieure garde cette date.
        database.mediaDao().deleteByIds(listOf(id))
        store.touch(id)
        sync.sync()
        assertEquals(MARCH_2, database.mediaDao().get(id)!!.dateTaken)
    }

    @Test
    fun `une date de prise de vue trouvée dans le fichier par MediaStore est conservée`() = runTest {
        store.fileAccess = false
        val photo = vault("DSC_0042.jpg", JULY_14)
        // EXIF avec fuseau horaire : MediaStore le lit, quelle que soit la date du fichier.
        store.exifDates[photo.displayName] = JULY_14

        assertEquals(OperationResult.Success(1), repository.restore(listOf(photo)))

        val row = store.row(photo.displayName)
        assertEquals(JULY_14, row.getAsLong(MediaStore.MediaColumns.DATE_TAKEN))
        assertEquals(JULY_14, database.mediaDao().get(row.getAsLong(MediaStore.MediaColumns._ID))!!.dateTaken)
        assertTrue(database.mediaDao().originalDates().isEmpty())
    }

    @Test
    fun `un album sorti du coffre garde les dates de ses éléments`() = runTest {
        val albumId = repository.createAlbum("Vacances")
        val first = vault("IMG_0001.jpg", JULY_14, albumId = albumId)
        val second = vault("IMG_0002.jpg", MARCH_2, albumId = albumId)

        assertEquals(OperationResult.Success(2), repository.restoreAlbum(albumId))

        assertTrue(repository.observeAlbums().first().isEmpty())
        // DATE_MODIFIED est à la seconde près.
        assertEquals(
            listOf(first.displayName to JULY_14 / 1000 * 1000, second.displayName to MARCH_2),
            database.mediaDao().getByIds(store.ids()).sortedByDescending { it.dateTaken }.map { it.displayName to it.dateTaken },
        )
    }

    @Test
    fun `une photo restaurée d'une sauvegarde puis sortie du coffre garde sa date`() = runTest {
        val content = random.nextBytes(50_000)
        vault("IMG_20190714_101500.jpg", JULY_14, content = content)
        val archive = Uri.fromFile(folder.newFile("coffre.fvault"))
        val backups = VaultBackupRepository(
            context.contentResolver, database.vaultDao(), crypto, storage,
            VaultBackupPrefs(PreferenceDataStoreFactory.create { File(folder.root, "vault.preferences_pb") }),
            Dispatchers.IO, iterations = 1_000,
        )
        backups.export(archive, PASSWORD.toCharArray())
        // Nouveau téléphone : coffre vide, puis sauvegarde restaurée.
        database.vaultDao().delete(repository.observeItems().first().map { it.id })
        storage.directory.listFiles()?.forEach(File::delete)
        backups.restore(archive, PASSWORD.toCharArray())
        val restored = repository.observeItems().first().single()
        assertEquals(JULY_14, restored.dateTaken)

        assertEquals(OperationResult.Success(1), repository.restore(listOf(restored)))

        val row = store.row(restored.displayName)
        assertEquals(JULY_14 / 1000, row.getAsLong(MediaStore.MediaColumns.DATE_MODIFIED))
        assertArrayEquals(content, store.content(restored.displayName))
        assertEquals(JULY_14 / 1000 * 1000, database.mediaDao().get(row.getAsLong(MediaStore.MediaColumns._ID))!!.dateTaken)
    }

    /** Élément chiffré dans le coffre, daté [dateTaken]. */
    private suspend fun vault(
        name: String,
        dateTaken: Long,
        video: Boolean = false,
        mimeType: String = if (video) "video/mp4" else "image/jpeg",
        albumId: Long? = null,
        content: ByteArray = random.nextBytes(20_000),
    ): VaultItem {
        val base = "${name.hashCode()}-${random.nextInt(1_000_000)}"
        val size = crypto.encrypt(content.inputStream(), storage.file(base + VaultStorage.MEDIA_SUFFIX))
        val entity = VaultItemEntity(
            displayName = name,
            mimeType = mimeType,
            mediaType = if (video) MediaQueryBuilder.MEDIA_TYPE_VIDEO else MediaQueryBuilder.MEDIA_TYPE_IMAGE,
            originalPath = if (video) "Movies/" else "DCIM/Camera/",
            dateTaken = dateTaken,
            size = size,
            width = 4_000,
            height = 3_000,
            duration = if (video) 12_000 else 0,
            fileName = base + VaultStorage.MEDIA_SUFFIX,
            thumbFileName = null,
            addedAt = System.currentTimeMillis(),
            albumId = albumId,
        )
        return entity.copy(id = database.vaultDao().insert(entity)).toModel()
    }

    /**
     * MediaStore réduit à ce qu'utilisent la restauration et la synchronisation : insertion en
     * attente, écriture, chemin du fichier, publication (relecture du fichier), requêtes de la
     * table `Files` et version de la base.
     */
    class FakeMediaStore : ContentProvider() {
        lateinit var directory: File

        /** Faux : le chemin du fichier n'est pas accessible à l'application (Android 10). */
        @Volatile
        var fileAccess = true

        /** Dates de prise de vue que MediaStore trouve dans les métadonnées des fichiers, par nom. */
        val exifDates = ConcurrentHashMap<String, Long>()

        private val rows = ConcurrentHashMap<Long, ContentValues>()
        private val nextId = AtomicLong(1_000)
        private val generation = AtomicLong(1)

        fun row(name: String): ContentValues = rows.values.single { it.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) == name }

        fun ids(): List<Long> = rows.keys.toList()

        fun count(predicate: (ContentValues) -> Boolean): Int = rows.values.count(predicate)

        fun content(name: String): ByteArray = file(row(name).getAsLong(MediaStore.MediaColumns._ID)).readBytes()

        /** Ligne modifiée : elle sera relue par la synchronisation suivante. */
        fun touch(id: Long) {
            rows.getValue(id).put(MediaStore.MediaColumns.GENERATION_MODIFIED, generation.incrementAndGet())
        }

        private fun file(id: Long) = File(directory, "$id")

        override fun onCreate(): Boolean = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = nextId.incrementAndGet()
            val isVideo = uri.pathSegments.contains("video")
            val row = ContentValues(values).apply {
                put(MediaStore.MediaColumns._ID, id)
                put(MediaStore.Files.FileColumns.MEDIA_TYPE, if (isVideo) 3 else 1)
                put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.MediaColumns.BUCKET_ID, getAsString(MediaStore.MediaColumns.RELATIVE_PATH).hashCode().toLong())
                put(MediaStore.MediaColumns.VOLUME_NAME, MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            rows[id] = row
            file(id).createNewFile()
            return ContentUris.withAppendedId(uri, id)
        }

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file(ContentUris.parseId(uri)), ParcelFileDescriptor.parseMode(mode))

        @Suppress("DEPRECATION")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            val id = ContentUris.parseId(uri)
            val row = rows[id] ?: return 0
            if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) {
                // Publication : le fichier est relu, ce qu'en savait MediaStore est remplacé.
                val file = file(id)
                row.put(MediaStore.MediaColumns.IS_PENDING, 0)
                row.putNull(MediaStore.MediaColumns.DATE_TAKEN)
                exifDates[row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)]?.let { row.put(MediaStore.MediaColumns.DATE_TAKEN, it) }
                row.put(MediaStore.MediaColumns.DATE_MODIFIED, file.lastModified() / 1000)
                row.put(MediaStore.MediaColumns.SIZE, file.length())
                row.put(MediaStore.MediaColumns.GENERATION_MODIFIED, generation.incrementAndGet())
            }
            return 1
        }

        @Suppress("DEPRECATION")
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            val columns = projection ?: arrayOf(MediaStore.MediaColumns._ID)
            val single = uri.lastPathSegment?.toLongOrNull()
            val since = if (selection?.contains(MediaStore.MediaColumns.GENERATION_MODIFIED) == true) args!!.last().toLong() else null
            val matching = if (single != null) {
                listOfNotNull(rows[single])
            } else {
                rows.values.filter {
                    it.getAsInteger(MediaStore.MediaColumns.IS_PENDING) != 1 &&
                        (since == null || it.getAsLong(MediaStore.MediaColumns.GENERATION_MODIFIED) > since)
                }
            }
            return MatrixCursor(columns).apply {
                for (row in matching) {
                    addRow(
                        columns.map { column ->
                            when (column) {
                                // Chemin réel, ou chemin du stockage partagé interdit à l'application.
                                MediaStore.MediaColumns.DATA -> if (fileAccess) {
                                    file(row.getAsLong(MediaStore.MediaColumns._ID)).path
                                } else {
                                    "/storage/emulated/0/" + row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) +
                                        row.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
                                }
                                else -> row.get(column)
                            }
                        },
                    )
                }
            }
        }

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? =
            if (method == "get_version") Bundle().apply { putString(Intent.EXTRA_TEXT, "1") } else null

        override fun getType(uri: Uri): String? = null

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
            if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0
    }

    private companion object {
        /** 14 juillet 2019, 10:15:00.250 UTC. */
        const val JULY_14 = 1_563_099_300_250L
        /** 1er janvier 2020, 00:01:00 UTC. */
        const val NEW_YEAR = 1_577_836_860_000L
        /** 2 mars 2018, 08:30:00 UTC. */
        const val MARCH_2 = 1_519_979_400_000L
        const val YEAR_SECONDS = 365L * 24 * 3600
        const val PASSWORD = "cheval correct agrafe"
    }
}
