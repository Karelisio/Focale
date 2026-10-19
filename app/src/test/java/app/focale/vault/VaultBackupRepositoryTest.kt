package app.focale.vault

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.VaultDao
import app.focale.core.database.VaultItemEntity
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sauvegarde et restauration de bout en bout : coffre réel (fichiers chiffrés par une clé
 * d'appareil), base simulée, archive dans un fichier comme celui du sélecteur système.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultBackupRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = VaultStorage(context)
    private val random = Random(2024)
    private lateinit var archiveFile: File
    private lateinit var archive: Uri

    @Before
    fun setUp() {
        storage.directory.listFiles()?.forEach(File::delete)
        storage.clearStaging()
        // Document vide, comme celui que crée le sélecteur système avant de rendre la main.
        archiveFile = folder.newFile("focale-coffre-2026-10-19.fvault")
        archive = Uri.fromFile(archiveFile)
    }

    @Test
    fun `sauvegarde puis restauration sur un nouvel appareil, à l'identique`() = runTest {
        val phone = Device("ancien")
        phone.add("IMG_0001.jpg", random.nextBytes(3 * MIB + 5), thumbnail = random.nextBytes(30_000))
        phone.add("VID_0002.mp4", random.nextBytes(600_000), thumbnail = random.nextBytes(10_000), video = true)
        phone.add("scan.png", random.nextBytes(1_000), thumbnail = null)
        assertNull(phone.prefs.lastBackupAt.first())

        val exportSteps = ArrayList<BackupProgress>()
        assertEquals(ExportResult.Success(saved = 3, unreadable = 0), phone.backups.export(archive, password(), onProgress = { exportSteps += it }))
        val backedUpAt = phone.prefs.lastBackupAt.first()
        assertNotNull(backedUpAt)
        assertEquals(BackupProgress.Stage.PREPARING, exportSteps.first().stage)
        assertEquals(BackupProgress(BackupProgress.Stage.TRANSFERRING, 3, 3, phone.totalBytes, phone.totalBytes), exportSteps.last())

        // Autre téléphone : coffre vide, clé d'appareil différente.
        wipeVault()
        val newPhone = Device("nouveau")
        val restoreSteps = ArrayList<BackupProgress>()
        assertEquals(
            RestoreResult.Success(added = 3, duplicates = 0, unreadable = 0),
            newPhone.backups.restore(archive, password(), onProgress = { restoreSteps += it }),
        )
        assertEquals(BackupProgress.Stage.FINISHING, restoreSteps.last().stage)
        assertEquals(3, newPhone.dao.items.value.size)
        for (original in phone.dao.items.value) {
            val copy = newPhone.dao.items.value.single { it.displayName == original.displayName }
            assertEquals(original.copy(id = 0, fileName = "", thumbFileName = null), copy.copy(id = 0, fileName = "", thumbFileName = null))
            assertArrayEquals(phone.media.getValue(original.displayName), newPhone.read(copy.fileName))
            assertEquals(original.thumbFileName != null, copy.thumbFileName != null)
            copy.thumbFileName?.let { assertArrayEquals(phone.thumbnails.getValue(original.displayName), newPhone.read(it)) }
        }
        assertTrue(stagedFiles().isEmpty())
        // Coffre vide restauré en entier : il est sauvegardé, à la date de création de l'archive.
        val restoredAt = newPhone.prefs.lastBackupAt.first()
        assertNotNull(restoredAt)
        assertTrue(restoredAt!! <= backedUpAt!!)
    }

    @Test
    fun `les doublons sont ignorés, y compris dans l'archive elle-même`() = runTest {
        val phone = Device("ancien")
        val photo = random.nextBytes(200_000)
        phone.add("IMG_0001.jpg", photo, thumbnail = random.nextBytes(5_000))
        phone.add("IMG_0001 (1).jpg", photo, thumbnail = random.nextBytes(5_000))
        // Même taille, contenu différent : pas un doublon.
        phone.add("IMG_0002.jpg", random.nextBytes(200_000), thumbnail = null)
        phone.backups.export(archive, password())

        // Restauration dans le même coffre : tout y est déjà, rien n'est écrit.
        val files = storage.directory.list()!!.toSet()
        val backedUpAt = phone.prefs.lastBackupAt.first()
        assertEquals(RestoreResult.Success(added = 0, duplicates = 3, unreadable = 0), phone.backups.restore(archive, password()))
        assertEquals(3, phone.dao.items.value.size)
        assertEquals(files, storage.directory.list()!!.toSet())
        assertEquals(backedUpAt, phone.prefs.lastBackupAt.first())

        // Coffre vide : la copie présente deux fois dans l'archive n'est ajoutée qu'une fois.
        wipeVault()
        val newPhone = Device("nouveau")
        assertEquals(RestoreResult.Success(added = 2, duplicates = 1, unreadable = 0), newPhone.backups.restore(archive, password()))
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `un mot de passe faux est signalé sans rien importer`() = runTest {
        val phone = Device("ancien")
        phone.add("IMG_0001.jpg", random.nextBytes(50_000), thumbnail = null)
        phone.backups.export(archive, password())
        wipeVault()
        val newPhone = Device("nouveau")
        assertEquals(RestoreResult.WrongPassword, newPhone.backups.restore(archive, "pas le bon mot de passe".toCharArray()))
        assertTrue(newPhone.dao.items.value.isEmpty())
        // Nouvel essai, avec le bon mot de passe.
        assertEquals(RestoreResult.Success(1, 0, 0), newPhone.backups.restore(archive, password()))
    }

    @Test
    fun `une archive modifiée ou tronquée n'importe rien`() = runTest {
        val phone = Device("ancien")
        phone.add("IMG_0001.jpg", random.nextBytes(2 * MIB), thumbnail = random.nextBytes(8_000))
        phone.add("IMG_0002.jpg", random.nextBytes(300_000), thumbnail = random.nextBytes(8_000))
        phone.backups.export(archive, password())
        val bytes = archiveFile.readBytes()
        wipeVault()
        val newPhone = Device("nouveau")

        // Altération ou coupure en fin d'archive : le premier élément est déjà rechiffré quand
        // elle apparaît, il ne doit pas pour autant entrer dans le coffre.
        val damaged = listOf(
            bytes.copyOf().also { it[it.size - 10] = (it[it.size - 10].toInt() xor 0x40).toByte() },
            bytes.copyOf(bytes.size - 100),
            bytes.copyOf(bytes.size / 2),
        )
        for (variant in damaged) {
            archiveFile.writeBytes(variant)
            assertEquals(RestoreResult.Failed(RestoreResult.Failed.Reason.CORRUPTED), newPhone.backups.restore(archive, password()))
            assertTrue(newPhone.dao.items.value.isEmpty())
            assertTrue(storage.directory.list().isNullOrEmpty())
            assertTrue(stagedFiles().isEmpty())
        }
        archiveFile.writeBytes(Random(1).nextBytes(10_000))
        assertEquals(RestoreResult.Failed(RestoreResult.Failed.Reason.NOT_AN_ARCHIVE), newPhone.backups.restore(archive, password()))
    }

    @Test
    fun `l'annulation supprime la sauvegarde inachevée et n'importe rien`() = runTest {
        val phone = Device("ancien")
        phone.add("VID_0001.mp4", random.nextBytes(3 * MIB), thumbnail = null, video = true)
        var checks = 0
        assertEquals(
            ExportResult.Cancelled(partialFileLeft = false),
            phone.backups.export(archive, password(), cancelled = { ++checks > 3 }),
        )
        assertFalse(archiveFile.exists())
        assertNull(phone.prefs.lastBackupAt.first())

        archiveFile.createNewFile()
        phone.backups.export(archive, password())
        wipeVault()
        val newPhone = Device("nouveau")
        checks = 0
        assertEquals(RestoreResult.Cancelled, newPhone.backups.restore(archive, password(), cancelled = { ++checks > 5 }))
        assertTrue(newPhone.dao.items.value.isEmpty())
        assertTrue(storage.directory.list().isNullOrEmpty())
        assertTrue(stagedFiles().isEmpty())
    }

    @Test
    fun `un élément illisible du coffre n'empêche pas la sauvegarde`() = runTest {
        val phone = Device("ancien")
        val broken = phone.add("IMG_0001.jpg", random.nextBytes(100_000), thumbnail = null)
        phone.add("IMG_0002.jpg", random.nextBytes(100_000), thumbnail = random.nextBytes(2_000))
        val file = storage.file(broken.fileName)
        file.writeBytes(file.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() })

        assertEquals(ExportResult.Success(saved = 1, unreadable = 1), phone.backups.export(archive, password()))
        wipeVault()
        val newPhone = Device("nouveau")
        assertEquals(RestoreResult.Success(added = 1, duplicates = 0, unreadable = 1), newPhone.backups.restore(archive, password()))
        assertEquals(listOf("IMG_0002.jpg"), newPhone.dao.items.value.map { it.displayName })
        // Restauration incomplète : le rappel de sauvegarde reste affiché.
        assertNull(newPhone.prefs.lastBackupAt.first())
    }

    private fun password() = "cheval correct agrafe".toCharArray()

    private fun wipeVault() {
        storage.directory.listFiles()?.forEach(File::delete)
    }

    private fun stagedFiles(): List<String> = File(context.noBackupFilesDir, "vault-staging").list().orEmpty().toList()

    /** Coffre d'un appareil : sa clé, sa base et ses réglages, sur le stockage de l'application. */
    private inner class Device(name: String) {
        val crypto = VaultCrypto {
            StreamingAeadConfig.register()
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        }
        val dao = FakeVaultDao()
        val prefs = VaultBackupPrefs(PreferenceDataStoreFactory.create { File(folder.root, "$name.preferences_pb") })
        val backups = VaultBackupRepository(context.contentResolver, dao, crypto, storage, prefs, Dispatchers.IO, iterations = 1_000)
        val media = HashMap<String, ByteArray>()
        val thumbnails = HashMap<String, ByteArray>()
        val totalBytes: Long get() = media.values.sumOf { it.size.toLong() }

        suspend fun add(name: String, content: ByteArray, thumbnail: ByteArray?, video: Boolean = false): VaultItemEntity {
            val base = "${media.size}-${random.nextInt(1_000_000)}"
            crypto.encrypt(content.inputStream(), storage.file(base + VaultStorage.MEDIA_SUFFIX))
            thumbnail?.let { crypto.encrypt(it.inputStream(), storage.file(base + VaultStorage.THUMB_SUFFIX)) }
            media[name] = content
            thumbnail?.let { thumbnails[name] = it }
            val entity = VaultItemEntity(
                displayName = name,
                mimeType = if (video) "video/mp4" else "image/jpeg",
                mediaType = if (video) MediaQueryBuilder.MEDIA_TYPE_VIDEO else MediaQueryBuilder.MEDIA_TYPE_IMAGE,
                originalPath = if (video) "Movies/" else "DCIM/Camera/",
                dateTaken = 1_700_000_000_000 + media.size,
                size = content.size.toLong(),
                width = if (video) 1_920 else 4_000,
                height = if (video) 1_080 else 3_000,
                duration = if (video) 42_000 else 0,
                fileName = base + VaultStorage.MEDIA_SUFFIX,
                thumbFileName = thumbnail?.let { base + VaultStorage.THUMB_SUFFIX },
                addedAt = 1_750_000_000_000,
            )
            return entity.copy(id = dao.insert(entity))
        }

        fun read(fileName: String): ByteArray = crypto.openDecrypting(storage.file(fileName)).use { it.readBytes() }
    }

    private class FakeVaultDao : VaultDao {
        val items = MutableStateFlow<List<VaultItemEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAll(): Flow<List<VaultItemEntity>> = items

        override suspend fun insert(item: VaultItemEntity): Long = insertAll(listOf(item)).single()

        override suspend fun insertAll(items: List<VaultItemEntity>): List<Long> {
            val added = items.map { it.copy(id = nextId++) }
            this.items.update { it + added }
            return added.map { it.id }
        }

        override suspend fun delete(ids: List<Long>) {
            items.update { list -> list.filterNot { it.id in ids } }
        }
    }

    private companion object {
        const val MIB = 1 shl 20
    }
}
