package app.focale.vault

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.MainActivity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEntity
import app.focale.core.database.VaultAlbumEntity
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.permissions.MediaAccessManager
import app.focale.core.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * Notification des nouveaux médias d'un dossier lié à un album du coffre : nombre à jour, une
 * seule alerte par hausse, ajout d'un geste, retrait quand tout est au coffre ou si l'utilisateur
 * la coupe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultNewMediaAlertsTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private lateinit var database: FocaleDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var alerts: VaultNewMediaAlerts
    private var albumId = 0L

    @Before
    fun setUp() = runTest {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }, scope)
        val state = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "state.preferences_pb") }
        // Sans accès à la galerie, la synchronisation ne fait rien : l'index est rempli par le test.
        val sync = MediaSyncManager(
            context, database, database.mediaDao(), MediaStoreSource(context.contentResolver), MediaAccessManager(context), state, scope,
            Dispatchers.IO,
        )
        alerts = VaultNewMediaAlerts(database.vaultDao(), sync, settings, VaultNotifications(context), state)
        albumId = database.vaultDao().insertAlbum(
            VaultAlbumEntity(
                name = "Perso",
                sourceBucketId = BUCKET,
                sourcePath = "Pictures/WhatsApp Images/",
                createdAt = 0,
                lastImportAt = IMPORTED_AT,
            ),
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun `les nouvelles photos d'un dossier lié sont signalées, à mettre au coffre d'un geste`() = runTest {
        // Une photo déjà là avant le dernier import, deux arrivées depuis.
        index(photo(1, addedAt = IMPORTED_AT - 3_600_000), photo(2), photo(3))
        assertTrue(alerts.check())

        val notification = posted()!!
        assertEquals("2 new photos in “WhatsApp Images” to move to the vault", NotificationCompat.getContentText(notification))
        val action = notification.actions.single()
        assertEquals("Move to vault", action.title)
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(MainActivity.ACTION_VAULT_ADD_NEW, intent.action)
        assertEquals(albumId, intent.getLongExtra(MainActivity.EXTRA_VAULT_ALBUM_ID, -1))
        // Sur l'écran verrouillé, ni le dossier ni le nombre.
        assertEquals("New photos to move to the vault", NotificationCompat.getContentText(notification.publicVersion))

        // Une troisième arrive : le nombre suit.
        index(photo(4))
        assertTrue(alerts.check())
        assertEquals("3 new photos in “WhatsApp Images” to move to the vault", NotificationCompat.getContentText(posted()!!))

        // Mises au coffre : les originaux quittent la galerie, la notification disparaît.
        database.mediaDao().deleteByIds(listOf(2L, 3L, 4L))
        assertTrue(alerts.check())
        assertNull(posted())
    }

    @Test
    fun `coupée par l'utilisateur, la surveillance s'arrête et retire sa notification`() = runTest {
        index(photo(2))
        alerts.check()
        assertTrue(posted() != null)

        settings.setVaultNewMediaAlerts(false)
        assertFalse(alerts.check())
        assertNull(posted())
    }

    @Test
    fun `sans album lié à un dossier, rien n'est surveillé`() = runTest {
        database.vaultDao().deleteAlbum(albumId)
        index(photo(2))
        assertFalse(alerts.check())
        assertNull(posted())
    }

    private fun posted() = shadowOf(manager).activeNotifications.firstOrNull()?.notification

    private suspend fun index(vararg photos: MediaEntity) = database.mediaDao().upsert(photos.toList())

    private fun photo(id: Long, addedAt: Long = IMPORTED_AT + id * 60_000) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG-$id-WA0001.jpg",
        relativePath = "Pictures/WhatsApp Images/", bucketId = BUCKET, bucketName = "WhatsApp Images",
        dateTaken = addedAt, dateAdded = addedAt, dateModified = addedAt / 1000, generation = 0,
        size = 4_000, width = 1_600, height = 1_200, orientation = 0, duration = 0,
        volumeName = "external_primary", isScreenshot = false, isGif = false, isTrashed = false, dateExpires = 0,
    )

    private companion object {
        const val BUCKET = 77L
        const val IMPORTED_AT = 1_760_000_000_000L
    }
}
