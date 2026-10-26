package app.focale.core.mediastore

import android.Manifest
import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.core.permissions.MediaAccessManager
import app.focale.perf.FakeMediaStore
import app.focale.perf.SyntheticLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Première indexation : les mots des noms de fichiers sont laissés à l'arrière-plan
 * (`MediaSyncManager.indexNames`), sans que la recherche par nom en soit faussée ; les
 * synchronisations suivantes les écrivent avec les médias.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSyncNamesTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = SyntheticLibrary(size = 600)
    private lateinit var database: FocaleDatabase
    private lateinit var store: FakeMediaStore
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Portée des tâches de fond de la synchronisation, jamais exécutées : l'index des noms attend. */
    private val background = CoroutineScope(StandardTestDispatcher())

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).build()
        store = FakeMediaStore.install()
    }

    @After
    fun tearDown() {
        background.cancel()
        stateScope.cancel()
        database.close()
    }

    @Test
    fun `la première indexation laisse les mots des noms à l'arrière-plan, la recherche reste exacte`() = runBlocking<Unit> {
        store.publish(library.media, library.withoutDateTaken)
        val manager = MediaSyncManager(
            context = context,
            database = database,
            dao = database.mediaDao(),
            source = MediaStoreSource(context.contentResolver),
            accessManager = MediaAccessManager(context),
            stateStore = PreferenceDataStoreFactory.create(scope = stateScope) { context.preferencesDataStoreFile("names-state-${System.nanoTime()}") },
            scope = background,
            io = Dispatchers.IO,
        )
        val dao = database.mediaDao()
        assertEquals(library.size, manager.sync())
        // Médias indexés, mots des noms en attente : la recherche par nom les compare un à un.
        assertTrue(dao.hasPendingNames())
        assertEquals(listOf(0L), dao.ids(SimpleSQLiteQuery("SELECT COUNT(*) FROM media_name")))
        assertNames()

        // Nouvelle photo, synchronisation suivante : son nom est indexé avec elle.
        val added = library.media.first { !it.isTrashed }.copy(id = 1_000_000, displayName = "Panorama_lac.jpg", generation = 1_000_000)
        store.publish(library.media + added, library.withoutDateTaken)
        assertEquals(1, manager.sync())
        assertEquals(listOf(added.id), dao.ids(MediaQueryBuilder.ids(MediaQuery(terms = listOf(SearchTerm(text = "panorama"))))))

        manager.indexNames()
        assertFalse(dao.hasPendingNames())
        assertNames()
    }

    /** Recherche par nom de quelques mots : exactement les médias dont le nom les contient. */
    private suspend fun assertNames() {
        for (word in listOf("shot", "screenshot", "img", "whatsapp", "vid", "pxl")) {
            val expected = database.mediaDao().ids(
                SimpleSQLiteQuery(
                    "SELECT id FROM media WHERE is_trashed = 0 AND display_name LIKE ? ORDER BY id",
                    arrayOf("%$word%"),
                ),
            )
            val found = database.mediaDao().ids(MediaQueryBuilder.ids(MediaQuery(terms = listOf(SearchTerm(text = word))))).sorted()
            assertEquals(word, expected, found)
        }
    }
}
