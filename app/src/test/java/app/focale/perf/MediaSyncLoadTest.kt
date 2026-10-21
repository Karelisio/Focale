package app.focale.perf

import android.Manifest
import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEntity
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.permissions.MediaAccessManager
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Charge : synchronisation de 50 000 médias depuis un faux MediaStore, complète (premier
 * lancement) puis incrémentale (quelques centaines de changements), avec `MediaSyncManager`
 * et une base Room ouverte comme en production. Mesures consignées dans docs/PERFORMANCE.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSyncLoadTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = SyntheticLibrary.Default
    private val report = LoadReport("synchro")
    private lateinit var database: FocaleDatabase
    private lateinit var store: FakeMediaStore
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        database = openLoadTestDatabase(context)
        store = FakeMediaStore.install()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    private fun syncManager() = MediaSyncManager(
        context = context,
        database = database,
        dao = database.mediaDao(),
        source = MediaStoreSource(context.contentResolver),
        accessManager = MediaAccessManager(context),
        stateStore = PreferenceDataStoreFactory.create(scope = scope) { context.preferencesDataStoreFile("perf-state-${System.nanoTime()}") },
        scope = scope,
        io = Dispatchers.IO,
    )

    @Test
    fun `synchronisation complète puis incrémentale de 50 000 médias`() = runBlocking<Unit> {
        describe(library)
        store.publish(library.media, library.withoutDateTaken)
        val manager = syncManager()

        var changed = 0
        val full = report.once { changed = manager.sync() }
        assertEquals(library.size, changed)
        assertEquals(library.size, database.mediaDao().totalCount())
        // L'index écrit est celui que décrit la bibliothèque (date de tri comprise).
        library.media.filterIndexed { index, _ -> index % 997 == 0 }.forEach { expected ->
            assertEquals(expected, database.mediaDao().get(expected.id))
        }
        report.check("complète (50 000 médias)", full, budgetMs = FULL_SYNC_BUDGET_MS, detail = "lots de 1 000, transaction par lot")

        // Bibliothèque analysée : la purge des métadonnées orphelines porte sur des tables réalistes.
        database.insertAnalysis(library)

        // Quelques centaines de changements : modifiés (édition), ajoutés (nouvelles photos), supprimés.
        val random = Random(7)
        val lastGeneration = library.media.maxOf { it.generation }
        val visible = library.media.filter { !it.isTrashed }.shuffled(random)
        val modified = visible.take(MODIFIED).mapIndexed { index, media ->
            val dateModified = media.dateModified + 60_000
            // Sans date de prise de vue, la date de tri suit la date de modification.
            val dateTaken = if (media.id in library.withoutDateTaken) dateModified else media.dateTaken
            media.copy(dateModified = dateModified, dateTaken = dateTaken, generation = lastGeneration + 1 + index)
        }
        val deleted = visible.drop(MODIFIED).take(DELETED).map { it.id }.toSet()
        val lastId = library.media.maxOf { it.id }
        val template = visible.first { it.mediaType == 1 && !it.isScreenshot }
        val added = (1..ADDED).map { index ->
            template.copy(
                id = lastId + index,
                displayName = "IMG_nouvelle_$index.jpg",
                dateTaken = SyntheticLibrary.END_MS + index * 1_000L,
                dateAdded = SyntheticLibrary.END_MS + index * 1_000L,
                dateModified = SyntheticLibrary.END_MS + index * 1_000L,
                generation = lastGeneration + MODIFIED + index,
            )
        }
        val byId = modified.associateBy { it.id }
        val current: List<MediaEntity> = library.media.filter { it.id !in deleted }.map { byId[it.id] ?: it } + added
        store.publish(current, library.withoutDateTaken)

        val incremental = report.once { changed = manager.sync() }
        assertEquals(MODIFIED + ADDED + DELETED, changed)
        assertEquals(library.size + ADDED - DELETED, database.mediaDao().totalCount())
        assertEquals(modified.first(), database.mediaDao().get(modified.first().id))
        report.check(
            "incrémentale ($MODIFIED modifiés, $ADDED ajoutés, $DELETED supprimés)",
            incremental,
            budgetMs = INCREMENTAL_SYNC_BUDGET_MS,
            detail = "dont lecture des 50 000 identifiants et purge des métadonnées orphelines",
        )

        // Rien n'a changé : le cas le plus fréquent (retour au premier plan, observateur MediaStore).
        val idle = report.samples(repeat = 3, warmup = 0) { assertEquals(0, manager.sync()) }
        report.check("sans changement", idle, budgetMs = IDLE_SYNC_BUDGET_MS)
    }

    /** Composition de la bibliothèque de synthèse, consignée avec les mesures (docs/PERFORMANCE.md). */
    private fun describe(library: SyntheticLibrary) {
        val visible = library.media.filter { !it.isTrashed }
        fun percent(count: Int) = 100.0 * count / library.size
        report.note("bibliothèque : médias", library.size.toDouble(), unit = "médias", detail = "${library.folderCount} dossiers")
        report.note("bibliothèque : vidéos", percent(library.media.count { it.mediaType == 3 }), unit = "%")
        report.note("bibliothèque : captures d'écran", percent(library.media.count { it.isScreenshot }), unit = "%")
        report.note("bibliothèque : GIF", percent(library.media.count { it.isGif }), unit = "%")
        report.note("bibliothèque : corbeille", percent(library.media.count { it.isTrashed }), unit = "%")
        report.note("bibliothèque : géolocalisés", percent(library.analyses.count { it.latitude != null }), unit = "%")
        report.note("bibliothèque : étiquettes par média", library.labels.size.toDouble() / visible.size, unit = "étiquettes")
        report.note("bibliothèque : photos avec du texte", library.texts.count { it.text.isNotEmpty() }.toDouble(), unit = "photos", detail = "sur ${library.texts.size}")
        report.note("bibliothèque : visages", library.faces.size.toDouble(), unit = "visages", detail = "sur ${library.faceScans.count { it.faceCount > 0 }} photos, ${library.persons.size} personnes")
        report.note("bibliothèque : sans DATE_TAKEN", percent(library.withoutDateTaken.size), unit = "%")
        report.note("bibliothèque : dossier le plus gros", visible.groupingBy { it.bucketId }.eachCount().values.max().toDouble(), unit = "médias")
    }

    private companion object {
        const val MODIFIED = 300
        const val ADDED = 150
        const val DELETED = 50

        // Budgets : environ cinq fois les médianes mesurées sur la machine de développement
        // (voir docs/PERFORMANCE.md), pour ne signaler qu'une régression nette.
        const val FULL_SYNC_BUDGET_MS = 5_000.0
        const val INCREMENTAL_SYNC_BUDGET_MS = 1_500.0
        const val IDLE_SYNC_BUDGET_MS = 300.0
    }
}
