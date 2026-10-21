package app.focale.vault

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.VaultAlbumEntity
import app.focale.core.database.VaultItemEntity
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sauvegarde automatique de bout en bout : coffre réel (fichiers chiffrés par une clé d'appareil),
 * base Room en mémoire, dossier choisi servi par un fournisseur de documents (DocumentsContract),
 * tâche lancée comme par WorkManager à des jours différents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultAutoBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage = VaultStorage(context)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val random = Random(2026)
    private val devices = ArrayList<Device>()
    private lateinit var backupDirectory: File
    private lateinit var provider: FolderDocumentsProvider

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        storage.directory.listFiles()?.forEach(File::delete)
        storage.clearStaging()
        backupDirectory = folder.newFolder("Sauvegardes")
        provider = FolderDocumentsProvider.install(backupDirectory)
    }

    @After
    fun tearDown() {
        devices.forEach { it.database.close() }
    }

    @Test
    fun `une sauvegarde automatique se restaure avec le mot de passe sur un autre appareil`() = runTest {
        val phone = Device("ancien")
        val trip = phone.dao.insertAlbum(VaultAlbumEntity(name = "Vacances", createdAt = 1_751_000_000_000))
        phone.add("IMG_0001.jpg", random.nextBytes(2 * MIB + 17), thumbnail = random.nextBytes(20_000), album = trip)
        phone.add("VID_0002.mp4", random.nextBytes(400_000), thumbnail = null, video = true)
        // Dans la corbeille du coffre : pas sauvegardée.
        val trashed = phone.add("IMG_0003.jpg", random.nextBytes(50_000), thumbnail = null)
        phone.dao.setDeleted(listOf(trashed.id), phone.now)
        assertNull(phone.enable(AutoBackupFrequency.DAILY))
        assertEquals(listOf(AutoBackupFrequency.DAILY to true), phone.scheduler.scheduled)
        assertEquals("Sauvegardes", phone.autoPrefs.settings.first().folderName)

        // Coffre verrouillé : la tâche n'a besoin que de la clé de l'appareil.
        assertEquals(AutoBackupResult.Saved(items = 2), phone.autoBackup.run())
        assertEquals(listOf("focale-coffre-auto-2026-10-01.fvault"), backupNames())
        val settings = phone.autoPrefs.settings.first()
        assertEquals(phone.now, settings.lastBackupAt)
        assertNull(settings.error)
        // Le rappel « aucune sauvegarde » du coffre en tient compte.
        assertNotNull(phone.prefs.lastBackupAt.first())

        // Nouveau téléphone : autre clé d'appareil, coffre vide ; restauration normale, mot de passe.
        wipeVault()
        val newPhone = Device("nouveau")
        val archive = Uri.fromFile(File(backupDirectory, "focale-coffre-auto-2026-10-01.fvault"))
        assertEquals(RestoreResult.Success(added = 2, duplicates = 0, unreadable = 0), newPhone.backups.restore(archive, password()))
        val restored = newPhone.items().associateBy { it.displayName }
        assertEquals(setOf("IMG_0001.jpg", "VID_0002.mp4"), restored.keys)
        assertArrayEquals(phone.media.getValue("IMG_0001.jpg"), newPhone.read(restored.getValue("IMG_0001.jpg").fileName))
        assertArrayEquals(phone.thumbnails.getValue("IMG_0001.jpg"), newPhone.read(requireNotNull(restored.getValue("IMG_0001.jpg").thumbFileName)))
        assertArrayEquals(phone.media.getValue("VID_0002.mp4"), newPhone.read(restored.getValue("VID_0002.mp4").fileName))
        assertEquals("Vacances", newPhone.dao.albums().single { it.id == restored.getValue("IMG_0001.jpg").albumId }.name)
        // Un mot de passe faux est refusé, comme pour une sauvegarde manuelle.
        assertEquals(RestoreResult.WrongPassword, newPhone.backups.restore(archive, "pas le bon mot de passe".toCharArray()))
    }

    @Test
    fun `seules les trois dernières sauvegardes automatiques sont gardées, les autres fichiers restent`() = runTest {
        val others = listOf(
            "notes.txt",
            // Sauvegarde manuelle, et noms voisins qui ne sont pas ceux de la sauvegarde automatique.
            "focale-coffre-2026-09-30.fvault",
            "focale-coffre-auto-2026-09-30.fvault.txt",
            "copie de focale-coffre-auto-2026-09-29.fvault",
        )
        others.forEach { File(backupDirectory, it).writeText("à garder : $it") }
        val phone = Device("téléphone")
        phone.add("IMG_0001.jpg", random.nextBytes(30_000), thumbnail = null)
        assertNull(phone.enable(AutoBackupFrequency.DAILY))

        for (day in 1..5) {
            phone.now = noon(2026, 10, day)
            // Une photo de plus chaque jour : rien n'est sauté.
            if (day > 1) phone.add("IMG_000$day.jpg", random.nextBytes(10_000), thumbnail = null)
            assertEquals(AutoBackupResult.Saved(items = day), phone.autoBackup.run())
            assertTrue(backupNames().filter { it.startsWith("focale-coffre-auto-2026-10") }.size <= VaultAutoBackup.KEEP)
        }
        assertEquals(
            listOf("focale-coffre-auto-2026-10-03.fvault", "focale-coffre-auto-2026-10-04.fvault", "focale-coffre-auto-2026-10-05.fvault"),
            backupNames().filter { it.startsWith("focale-coffre-auto-2026-10") }.sorted(),
        )
        for (name in others) assertEquals("à garder : $name", File(backupDirectory, name).readText())

        // Deuxième sauvegarde du même jour (mot de passe changé) : renommée par le fournisseur, gardée.
        assertTrue(phone.changePassword("un autre mot de passe sûr"))
        assertEquals(AutoBackupResult.Saved(items = 5), phone.autoBackup.run())
        assertEquals(
            listOf(
                "focale-coffre-auto-2026-10-04.fvault",
                "focale-coffre-auto-2026-10-05 (1).fvault",
                "focale-coffre-auto-2026-10-05.fvault",
            ),
            backupNames().filter { it.startsWith("focale-coffre-auto-2026-10") }.sorted(),
        )
        assertEquals(others.size + VaultAutoBackup.KEEP, backupNames().size)
    }

    @Test
    fun `rien n'est écrit si le coffre n'a pas changé depuis la dernière sauvegarde`() = runTest {
        val phone = Device("téléphone")
        val photo = phone.add("IMG_0001.jpg", random.nextBytes(30_000), thumbnail = null)
        phone.add("IMG_0002.jpg", random.nextBytes(20_000), thumbnail = null)
        assertNull(phone.enable(AutoBackupFrequency.WEEKLY))
        assertEquals(AutoBackupResult.Saved(items = 2), phone.autoBackup.run())
        val first = File(backupDirectory, "focale-coffre-auto-2026-10-01.fvault")
        val written = first.readBytes()

        phone.now = noon(2026, 10, 8)
        assertEquals(AutoBackupResult.Skipped(AutoBackupResult.Skipped.Reason.UNCHANGED), phone.autoBackup.run())
        assertEquals(listOf(first.name), backupNames())
        assertArrayEquals(written, first.readBytes())
        assertEquals(noon(2026, 10, 1), phone.autoPrefs.settings.first().lastBackupAt)

        // Un rangement suffit à changer l'empreinte : nouvelle sauvegarde.
        val album = phone.dao.insertAlbum(VaultAlbumEntity(name = "Papiers", createdAt = 1_760_000_000_000))
        assertEquals(AutoBackupResult.Saved(items = 2), phone.autoBackup.run())
        phone.now = noon(2026, 10, 15)
        assertEquals(AutoBackupResult.Skipped(AutoBackupResult.Skipped.Reason.UNCHANGED), phone.autoBackup.run())
        phone.dao.setAlbum(listOf(photo.id), album)
        assertEquals(AutoBackupResult.Saved(items = 2), phone.autoBackup.run())
        assertEquals(3, backupNames().size)

        // La dernière sauvegarde a disparu du dossier : elle est refaite, même sans changement.
        phone.now = noon(2026, 10, 22)
        File(backupDirectory, "focale-coffre-auto-2026-10-15.fvault").delete()
        assertEquals(AutoBackupResult.Saved(items = 2), phone.autoBackup.run())
        assertTrue("focale-coffre-auto-2026-10-22.fvault" in backupNames())

        // Coffre vide : rien à sauvegarder, les sauvegardes précédentes restent.
        phone.dao.setDeleted(phone.items().map { it.id }, phone.now)
        phone.now = noon(2026, 10, 29)
        assertEquals(AutoBackupResult.Skipped(AutoBackupResult.Skipped.Reason.EMPTY), phone.autoBackup.run())
        assertEquals(3, backupNames().size)
    }

    @Test
    fun `un dossier inaccessible est signalé dans le réglage et par une notification`() = runTest {
        val phone = Device("téléphone")
        val album = phone.dao.insertAlbum(VaultAlbumEntity(name = "Album secret", createdAt = 1_760_000_000_000))
        phone.add("Photo très privée.jpg", random.nextBytes(30_000), thumbnail = null, album = album)
        // Dossier en lecture seule : refusé dès le réglage, sans autorisation gardée.
        provider.readOnly = true
        assertEquals(AutoBackupError.FOLDER_UNAVAILABLE, phone.enable(AutoBackupFrequency.DAILY))
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        assertFalse(phone.autoPrefs.settings.first().enabled)
        assertNull(phone.keys.load())
        provider.readOnly = false
        assertNull(phone.enable(AutoBackupFrequency.DAILY))

        // Autorisation retirée (données du fournisseur effacées, réglages du système…).
        context.contentResolver.releasePersistableUriPermission(FolderDocumentsProvider.TREE, BackupFolder.PERMISSIONS)
        assertEquals(AutoBackupResult.Failed(AutoBackupError.FOLDER_UNAVAILABLE), phone.autoBackup.run())
        assertTrue(backupNames().isEmpty())
        val settings = phone.autoPrefs.settings.first()
        assertEquals(AutoBackupError.FOLDER_UNAVAILABLE, settings.error)
        assertEquals(phone.now, settings.errorAt)
        val notification = requireNotNull(failure())
        assertEquals("Automatic vault backup failed", NotificationCompat.getContentTitle(notification))
        val text = NotificationCompat.getContentText(notification).toString()
        assertEquals("The backup folder can no longer be reached. Choose it again in the vault.", text)
        // Rien du coffre dans la notification, ni sur l'écran verrouillé.
        for (secret in listOf("Album secret", "Photo très privée", "Sauvegardes")) assertFalse(text.contains(secret))
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("Focale", NotificationCompat.getContentTitle(requireNotNull(notification.publicVersion)))

        // Dossier choisi de nouveau : la sauvegarde passe, l'échec est oublié.
        assertTrue(phone.autoBackup.changeFolder(FolderDocumentsProvider.TREE))
        assertEquals(1, phone.scheduler.soon)
        assertEquals(AutoBackupResult.Saved(items = 1), phone.autoBackup.run())
        assertNull(phone.autoPrefs.settings.first().error)
        assertNull(failure())

        // Dossier supprimé, ou support retiré : le fournisseur ne le trouve plus.
        phone.now = noon(2026, 10, 2)
        phone.add("IMG_0002.jpg", random.nextBytes(10_000), thumbnail = null)
        backupDirectory.deleteRecursively()
        assertEquals(AutoBackupResult.Failed(AutoBackupError.FOLDER_UNAVAILABLE), phone.autoBackup.run())
        assertEquals(AutoBackupError.FOLDER_UNAVAILABLE, phone.autoPrefs.settings.first().error)
        assertNotNull(failure())

        // Fournisseur qui refuse tout accès.
        backupDirectory.mkdirs()
        provider.denied = true
        assertEquals(AutoBackupResult.Failed(AutoBackupError.FOLDER_UNAVAILABLE), phone.autoBackup.run())
    }

    @Test
    fun `le mot de passe n'est conservé nulle part, la clé seulement et chiffrée`() = runTest {
        val phone = Device("téléphone")
        phone.add("IMG_0001.jpg", random.nextBytes(10_000), thumbnail = null)
        val password = "cheval correct agrafe"
        assertNull(phone.enable(AutoBackupFrequency.DAILY, password))
        assertEquals(AutoBackupResult.Saved(items = 1), phone.autoBackup.run())

        val keys = requireNotNull(phone.keys.load())
        val plain = keys.encode()
        val salt = plain.copyOfRange(6, 6 + VaultArchive.SALT_BYTES)
        val streamKey = plain.copyOfRange(plain.size - VaultArchive.KEY_BYTES, plain.size)
        val master = BackupPassword.derive(password.toCharArray(), salt, keys.iterations)
        val secrets = mapOf(
            "mot de passe (UTF-8)" to password.toByteArray(Charsets.UTF_8),
            "mot de passe (UTF-16)" to password.toByteArray(Charsets.UTF_16LE),
            "clé dérivée" to master,
            "clé du flux" to streamKey,
            "clés en clair" to plain,
        )
        // Réglages du coffre (DataStore) et préférences partagées de l'application.
        val stored = listOf(phone.storeFile) + File(context.applicationInfo.dataDir, "shared_prefs").walk().filter { it.isFile }
        for (file in stored) {
            val bytes = file.readBytes()
            for ((what, secret) in secrets) assertFalse("$what dans ${file.name}", bytes.contains(secret))
        }
        // L'archive écrite non plus ne les contient pas.
        val archive = File(backupDirectory, "focale-coffre-auto-2026-10-01.fvault").readBytes()
        for ((what, secret) in secrets) assertFalse("$what dans l'archive", archive.contains(secret))

        // Désactivée : clés et réglage oubliés, autorisation rendue ; les sauvegardes restent.
        phone.autoBackup.disable()
        assertNull(phone.keys.load())
        assertEquals(AutoBackupSettings(), phone.autoPrefs.settings.first())
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        assertEquals(1, phone.scheduler.cancelled)
        assertEquals(AutoBackupResult.Skipped(AutoBackupResult.Skipped.Reason.DISABLED), phone.autoBackup.run())
        assertEquals(1, backupNames().size)
    }

    private fun password() = "cheval correct agrafe".toCharArray()

    private fun backupNames(): List<String> = backupDirectory.list().orEmpty().sorted()

    private fun failure() = shadowOf(manager).activeNotifications
        .firstOrNull { it.id == VaultNotifications.AUTO_BACKUP_FAILED_ID }
        ?.notification

    private fun wipeVault() {
        storage.directory.listFiles()?.forEach(File::delete)
    }

    private fun noon(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun ByteArray.contains(part: ByteArray): Boolean =
        (0..size - part.size).any { start -> part.indices.all { this[start + it] == part[it] } }

    /** Planification enregistrée au lieu d'être confiée à WorkManager. */
    private class RecordingScheduler : AutoBackupScheduler {
        val scheduled = ArrayList<Pair<AutoBackupFrequency, Boolean>>()
        var soon = 0
        var cancelled = 0

        override fun schedule(frequency: AutoBackupFrequency, restart: Boolean) {
            scheduled += frequency to restart
        }

        override fun runSoon() {
            soon++
        }

        override fun cancel() {
            cancelled++
        }
    }

    /** Coffre d'un appareil : sa clé, sa base, ses réglages et sa sauvegarde automatique. */
    private inner class Device(name: String) {
        val crypto = VaultCrypto {
            StreamingAeadConfig.register()
            KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB)
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        }
        val database: FocaleDatabase = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { devices += this }
        val dao = database.vaultDao()
        val storeFile = File(folder.root, "$name.preferences_pb")
        private val store = PreferenceDataStoreFactory.create { storeFile }
        val prefs = VaultBackupPrefs(store)
        val backups = VaultBackupRepository(context.contentResolver, dao, crypto, storage, prefs, Dispatchers.IO, iterations = 1_000)
        val autoPrefs = VaultAutoBackupPrefs(store)
        val keys = VaultAutoBackupKeys(store) {
            AeadConfig.register()
            KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
        val scheduler = RecordingScheduler()
        var now = noon(2026, 10, 1)
        val autoBackup = VaultAutoBackup(
            context.contentResolver, backups, autoPrefs, keys, VaultNotifications(context), scheduler, Dispatchers.IO,
            iterations = 1_000, clock = { now },
        )
        val media = HashMap<String, ByteArray>()
        val thumbnails = HashMap<String, ByteArray>()

        /** Active la sauvegarde automatique comme le réglage : mot de passe, puis dossier choisi. */
        suspend fun enable(frequency: AutoBackupFrequency, password: String = "cheval correct agrafe"): AutoBackupError? =
            autoBackup.enable(FolderDocumentsProvider.TREE, autoBackup.deriveKeys(password.toCharArray()), frequency)

        suspend fun changePassword(password: String): Boolean =
            autoBackup.changePassword(autoBackup.deriveKeys(password.toCharArray()))

        suspend fun items(): List<VaultItemEntity> = dao.observeAll().first()

        suspend fun add(
            name: String,
            content: ByteArray,
            thumbnail: ByteArray?,
            video: Boolean = false,
            album: Long? = null,
        ): VaultItemEntity {
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
                albumId = album,
            )
            return entity.copy(id = dao.insert(entity))
        }

        fun read(fileName: String): ByteArray = crypto.openDecrypting(storage.file(fileName)).use { it.readBytes() }
    }

    private companion object {
        const val MIB = 1 shl 20
    }
}
