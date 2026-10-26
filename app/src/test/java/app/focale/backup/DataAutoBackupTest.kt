package app.focale.backup

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.AlbumEntity
import app.focale.core.database.AlbumMediaEntity
import app.focale.core.database.FavoriteEntity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEntity
import app.focale.core.locale.AppLocales
import app.focale.core.settings.SettingsRepository
import app.focale.core.settings.ThemeMode
import app.focale.people.PeopleEditions
import app.focale.vault.FolderDocumentsProvider
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Sauvegarde automatique des données de bout en bout : base Room en mémoire, réglages et clés dans
 * leurs DataStore, dossier choisi servi par un fournisseur de documents (DocumentsContract), tâche
 * lancée comme par WorkManager à des jours différents. Aucun coffre : elle n'en dépend pas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataAutoBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val devices = ArrayList<Device>()
    private lateinit var backupDirectory: File
    private lateinit var provider: FolderDocumentsProvider

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        backupDirectory = folder.newFolder("Sauvegardes")
        provider = FolderDocumentsProvider.install(backupDirectory)
    }

    @After
    fun tearDown() {
        devices.forEach(Device::close)
    }

    @Test
    fun `planifiée au réglage, la sauvegarde automatique se restaure sur un autre téléphone`() = runTest {
        val phone = Device("ancien")
        phone.photo(11, "IMG_0001.jpg")
        phone.photo(12, "IMG_0002.jpg")
        phone.database.favoriteDao().insert(listOf(FavoriteEntity(11, T), FavoriteEntity(12, T)))
        val album = phone.database.albumDao().insert(AlbumEntity(name = "Vacances", createdAt = T))
        phone.database.albumDao().addMedia(listOf(AlbumMediaEntity(album, 12, T)))
        phone.settings.setThemeMode(ThemeMode.DARK)
        assertNull(phone.enable(AutoBackupFrequency.DAILY))
        assertEquals(listOf(AutoBackupFrequency.DAILY to true), phone.scheduler.scheduled)
        assertEquals("Sauvegardes", phone.autoPrefs.settings.first().folderName)

        // Deux favoris et un album.
        assertEquals(AutoBackupResult.Saved(items = 3), phone.autoBackup.run())
        assertEquals(listOf("focale-auto-2026-10-01.focale"), backupNames())
        val settings = phone.autoPrefs.settings.first()
        assertEquals(phone.now, settings.lastBackupAt)
        assertNull(settings.error)

        // Nouvelle fréquence : l'échéance suivante en tient compte, sans sauvegarde de plus.
        phone.autoBackup.setFrequency(AutoBackupFrequency.WEEKLY)
        assertEquals(AutoBackupFrequency.WEEKLY to false, phone.scheduler.scheduled.last())

        // Nouveau téléphone, mêmes photos sous d'autres identifiants : restauration normale, mot de passe.
        val newPhone = Device("nouveau")
        newPhone.photo(501, "IMG_0002.jpg", size = phone.sizeOf(12))
        newPhone.photo(502, "IMG_0001.jpg", size = phone.sizeOf(11))
        val archive = Uri.fromFile(File(backupDirectory, "focale-auto-2026-10-01.focale"))
        assertEquals(DataInspectResult.WrongPassword, newPhone.backups.inspect(archive, "pas le bon mot de passe".toCharArray()))
        val inspected = newPhone.backups.inspect(archive, password()) as DataInspectResult.Ready
        val report = inspected.backup.use { (newPhone.backups.restore(it) as DataRestoreResult.Success).report }
        assertEquals(2, report.favorites)
        assertEquals(0, report.missing)
        assertEquals(setOf(501L, 502L), newPhone.database.favoriteDao().favoritesAmong(listOf(501, 502)).toSet())
        assertEquals(ThemeMode.DARK, newPhone.settings.settings.first().themeMode)
    }

    @Test
    fun `seules les trois dernières sauvegardes automatiques sont gardées, les autres fichiers restent`() = runTest {
        val others = listOf(
            "notes.txt",
            // Sauvegarde manuelle, celles du coffre, et des noms voisins de ceux de la sauvegarde automatique.
            "focale-2026-09-30.focale",
            "focale-coffre-auto-2026-09-30.fvault",
            "focale-coffre-auto-2026-10-01.fvault",
            "focale-auto-2026-09-30.focale.txt",
            "copie de focale-auto-2026-09-29.focale",
        )
        others.forEach { File(backupDirectory, it).writeText("à garder : $it") }
        val phone = Device("téléphone")
        assertNull(phone.enable(AutoBackupFrequency.DAILY))

        for (day in 1..5) {
            phone.now = noon(2026, 10, day)
            // Un favori de plus chaque jour : rien n'est sauté.
            phone.photo(day.toLong(), "IMG_000$day.jpg")
            phone.database.favoriteDao().insert(listOf(FavoriteEntity(day.toLong(), phone.now)))
            assertEquals(AutoBackupResult.Saved(items = day), phone.autoBackup.run())
            assertTrue(autoBackups().size <= AutoBackupEngine.KEEP)
        }
        assertEquals(listOf("focale-auto-2026-10-03.focale", "focale-auto-2026-10-04.focale", "focale-auto-2026-10-05.focale"), autoBackups())
        for (name in others) assertEquals("à garder : $name", File(backupDirectory, name).readText())

        // Deuxième sauvegarde du même jour (mot de passe changé) : renommée par le fournisseur, gardée.
        assertTrue(phone.autoBackup.changePassword(phone.autoBackup.deriveKeys("un autre mot de passe sûr".toCharArray())))
        assertEquals(1, phone.scheduler.soon)
        assertEquals(AutoBackupResult.Saved(items = 5), phone.autoBackup.run())
        assertEquals(
            listOf("focale-auto-2026-10-04.focale", "focale-auto-2026-10-05 (1).focale", "focale-auto-2026-10-05.focale"),
            autoBackups(),
        )
        assertEquals(others.size + AutoBackupEngine.KEEP, backupNames().size)
    }

    @Test
    fun `rien n'est écrit si les données n'ont pas changé depuis la dernière sauvegarde`() = runTest {
        val phone = Device("téléphone")
        phone.photo(1, "IMG_0001.jpg")
        phone.database.favoriteDao().insert(listOf(FavoriteEntity(1, T)))
        assertNull(phone.enable(AutoBackupFrequency.WEEKLY))
        assertEquals(AutoBackupResult.Saved(items = 1), phone.autoBackup.run())
        val first = File(backupDirectory, "focale-auto-2026-10-01.focale")
        val written = first.readBytes()

        phone.now = noon(2026, 10, 8)
        assertEquals(AutoBackupResult.Skipped(AutoBackupResult.Skipped.Reason.UNCHANGED), phone.autoBackup.run())
        assertArrayEquals(written, first.readBytes())
        assertEquals(noon(2026, 10, 1), phone.autoPrefs.settings.first().lastBackupAt)

        // Un réglage suffit à changer l'empreinte : nouvelle sauvegarde.
        phone.settings.setGridColumns(5)
        assertEquals(AutoBackupResult.Saved(items = 1), phone.autoBackup.run())
        phone.now = noon(2026, 10, 15)
        assertEquals(AutoBackupResult.Skipped(AutoBackupResult.Skipped.Reason.UNCHANGED), phone.autoBackup.run())

        // La dernière sauvegarde a disparu du dossier : elle est refaite, même sans changement.
        File(backupDirectory, "focale-auto-2026-10-08.focale").delete()
        assertEquals(AutoBackupResult.Saved(items = 1), phone.autoBackup.run())
        assertEquals(listOf("focale-auto-2026-10-01.focale", "focale-auto-2026-10-15.focale"), backupNames())
    }

    @Test
    fun `un échec est signalé dans le réglage et par une notification, sans rien des données`() = runTest {
        val phone = Device("téléphone")
        phone.photo(1, "Anniversaire de Léa.jpg")
        phone.database.albumDao().insert(AlbumEntity(name = "Album très privé", createdAt = T))
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
        assertEquals("Automatic data backup failed", NotificationCompat.getContentTitle(notification))
        val text = NotificationCompat.getContentText(notification).toString()
        assertEquals("The backup folder can no longer be reached. Choose it again in Settings.", text)
        for (secret in listOf("Album très privé", "Léa", "Sauvegardes")) assertFalse(text.contains(secret))
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals("Focale", NotificationCompat.getContentTitle(requireNotNull(notification.publicVersion)))

        // Dossier choisi de nouveau : la sauvegarde passe, l'échec est oublié.
        assertTrue(phone.autoBackup.changeFolder(FolderDocumentsProvider.TREE))
        assertEquals(1, phone.scheduler.soon)
        assertEquals(AutoBackupResult.Saved(items = 1), phone.autoBackup.run())
        assertNull(phone.autoPrefs.settings.first().error)
        assertNull(failure())

        // Keystore réinitialisé (restauration de l'appareil…) : clés illisibles, mot de passe à redonner.
        phone.now = noon(2026, 10, 2)
        phone.resetKeystore()
        assertEquals(AutoBackupResult.Failed(AutoBackupError.KEYS_UNAVAILABLE), phone.autoBackup.run())
        assertEquals(AutoBackupError.KEYS_UNAVAILABLE, phone.autoPrefs.settings.first().error)
        assertEquals(
            "The backup key is no longer available on this device. Enter the password again in Settings.",
            NotificationCompat.getContentText(requireNotNull(failure())).toString(),
        )
        assertTrue(phone.autoBackup.changePassword(phone.autoBackup.deriveKeys(password())))
        assertEquals(AutoBackupResult.Saved(items = 1), phone.autoBackup.run())
        assertNull(failure())

        // Désactivée : clés et réglage oubliés, autorisation rendue, tâches annulées ; les sauvegardes restent.
        phone.autoBackup.disable()
        assertNull(phone.keys.load())
        assertEquals(AutoBackupSettings(), phone.autoPrefs.settings.first())
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        assertEquals(1, phone.scheduler.cancelled)
        assertEquals(AutoBackupResult.Skipped(AutoBackupResult.Skipped.Reason.DISABLED), phone.autoBackup.run())
        assertEquals(2, backupNames().size)
    }

    @Test
    fun `le mot de passe n'est conservé nulle part, les clés seulement et chiffrées`() = runTest {
        val phone = Device("téléphone")
        phone.photo(1, "IMG_0001.jpg")
        phone.database.favoriteDao().insert(listOf(FavoriteEntity(1, T)))
        val password = "cheval correct agrafe"
        assertNull(phone.enable(AutoBackupFrequency.DAILY, password))
        assertEquals(AutoBackupResult.Saved(items = 1), phone.autoBackup.run())

        val keys = requireNotNull(phone.keys.load())
        assertEquals(ArchiveFormat.DATA, keys.format)
        val plain = keys.encode()
        val salt = plain.copyOfRange(6, 6 + PasswordArchive.SALT_BYTES)
        val streamKey = plain.copyOfRange(plain.size - PasswordArchive.KEY_BYTES, plain.size)
        val master = BackupPassword.derive(password.toCharArray(), salt, keys.iterations)
        val secrets = mapOf(
            "mot de passe (UTF-8)" to password.toByteArray(Charsets.UTF_8),
            "mot de passe (UTF-16)" to password.toByteArray(Charsets.UTF_16LE),
            "clé dérivée" to master,
            "clé du flux" to streamKey,
            "clés en clair" to plain,
        )
        val stored = listOf(phone.storeFile) + File(context.applicationInfo.dataDir, "shared_prefs").walk().filter { it.isFile }
        for (file in stored) {
            val bytes = file.readBytes()
            for ((what, secret) in secrets) assertFalse("$what dans ${file.name}", bytes.contains(secret))
        }
        val archive = File(backupDirectory, "focale-auto-2026-10-01.focale").readBytes()
        for ((what, secret) in secrets) assertFalse("$what dans l'archive", archive.contains(secret))
        // Le mot de passe, lui, ouvre la sauvegarde écrite.
        DataArchive.open(archive.inputStream()).keysFor(password.toCharArray()).use { assertEquals(ArchiveFormat.DATA, it.format) }
    }

    private fun password() = "cheval correct agrafe".toCharArray()

    private fun backupNames(): List<String> = backupDirectory.list().orEmpty().sorted()

    /** Sauvegardes automatiques des données d'octobre, seules concernées par la rotation. */
    private fun autoBackups(): List<String> = backupNames().filter { it.startsWith("focale-auto-2026-10") }

    private fun failure() = shadowOf(manager).activeNotifications
        .firstOrNull { it.id == DataBackupNotifications.AUTO_BACKUP_FAILED_ID }
        ?.notification

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

    /** Un téléphone : sa base, ses réglages, sa sauvegarde des données et sa sauvegarde automatique. */
    private inner class Device(name: String) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database: FocaleDatabase = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name-settings.preferences_pb") },
            scope,
        )
        val storeFile = File(folder.root, "$name-data-backup.preferences_pb")
        private val store = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
        var now = noon(2026, 10, 1)
        val backups = DataBackupRepository(
            context.contentResolver, database, settings, PeopleEditions(), { emptyMap() }, {}, Dispatchers.IO,
            iterations = 1_000, appVersion = "1.9.0", clock = { now }, copies = File(folder.root, "$name-copies"),
        )
        val autoPrefs = DataAutoBackupPrefs(store)

        /** Clé du Keystore qui protège les clés enregistrées : remplacée par [resetKeystore]. */
        private var sealing: Aead = newAead()
        val keys = DataAutoBackupKeys(store) {
            object : Aead {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray = sealing.encrypt(plaintext, associatedData)

                override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray = sealing.decrypt(ciphertext, associatedData)
            }
        }
        val scheduler = RecordingScheduler()
        val autoBackup = DataAutoBackup(
            context.contentResolver, backups, autoPrefs, keys, DataBackupNotifications(context, AppLocales(context)), scheduler, Dispatchers.IO,
            iterations = 1_000, clock = { now },
        )

        init {
            devices += this
        }

        /** Active la sauvegarde automatique comme le réglage : mot de passe, puis dossier choisi. */
        suspend fun enable(frequency: AutoBackupFrequency, password: String = "cheval correct agrafe"): AutoBackupError? =
            autoBackup.enable(FolderDocumentsProvider.TREE, autoBackup.deriveKeys(password.toCharArray()), frequency)

        fun resetKeystore() {
            sealing = newAead()
        }

        suspend fun photo(id: Long, name: String, size: Long = 10_000 + id) {
            database.mediaDao().upsert(
                listOf(
                    MediaEntity(
                        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = name, relativePath = "DCIM/Camera/",
                        bucketId = 1, bucketName = "Camera", dateTaken = T + id, dateAdded = T + id, dateModified = T + id,
                        generation = 0, size = size, width = 4_000, height = 3_000, orientation = 0, duration = 0,
                        volumeName = "external_primary", isScreenshot = false, isGif = false, isTrashed = false, dateExpires = 0,
                    ),
                ),
            )
        }

        suspend fun sizeOf(id: Long): Long = requireNotNull(database.mediaDao().get(id)).size

        fun close() {
            database.close()
            scope.cancel()
        }

        private fun newAead(): Aead {
            AeadConfig.register()
            return KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }
    }

    private companion object {
        const val T = 1_750_000_000_000L
    }
}
