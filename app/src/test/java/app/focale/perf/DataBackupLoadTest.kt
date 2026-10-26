package app.focale.perf

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.focale.backup.BackupProgress
import app.focale.backup.DataBackupRepository
import app.focale.backup.DataExportResult
import app.focale.backup.DataInspectResult
import app.focale.backup.DataRestoreResult
import app.focale.core.database.AlbumEntity
import app.focale.core.database.AlbumMediaEntity
import app.focale.core.database.FaceEntity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaDateEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.SearchHistoryEntity
import app.focale.core.settings.SettingsRepository
import app.focale.core.settings.ThemeMode
import app.focale.data.SearchChip
import app.focale.data.SearchFilters
import app.focale.people.PeopleEditions
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Charge : sauvegarde des données d'une bibliothèque de 50 000 médias (20 000 visages et leurs
 * empreintes, 40 personnes, favoris, albums virtuels, historique), vérification de l'archive puis
 * restauration sur un autre téléphone, aux identifiants différents et aux dossiers en partie
 * déplacés. Durées et mémoire retenue sont bornées : tout se fait en flux, la mémoire ne dépend
 * pas de la taille de l'archive. Mesures consignées dans docs/PERFORMANCE.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataBackupLoadTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = SyntheticLibrary.Default
    private val report = LoadReport("sauvegarde des données")
    private val random = Random(7)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val databases = ArrayList<FocaleDatabase>()

    @Before
    fun setUp() {
        // Mesures de mémoire comparables : rien d'une classe de test précédente à ramasser.
        retainedHeap()
    }

    @After
    fun tearDown() {
        databases.forEach(FocaleDatabase::close)
        scope.cancel()
    }

    @Test
    fun `sauvegarde puis restauration de 50 000 médias et 20 000 visages, en flux`() = runBlocking<Unit> {
        val old = Phone("ancien")
        old.database.insertMedia(library)
        old.database.insertAnalysis(library)
        val content = old.organize()
        val archiveFile = folder.newFile("focale-2026-10-22.focale")
        val archive = Uri.fromFile(archiveFile)

        val exportHeap = HeapProbe()
        var exported: DataExportResult? = null
        val exportMs = report.once { exported = old.backups.export(archive, PASSWORD.toCharArray(), onProgress = exportHeap::onProgress) } -
            exportHeap.spentMs
        assertEquals(DataExportResult.Success(content.favorites, content.albums, content.people, content.hiddenFolders), exported)
        report.check("sauvegarde", exportMs, budgetMs = EXPORT_BUDGET_MS, detail = "${archiveFile.length() / 1024} Kio")
        report.note("taille de l'archive", archiveFile.length() / 1_048_576.0, unit = "Mio")
        checkHeap("mémoire retenue pendant la sauvegarde", exportHeap, EXPORT_HEAP_BUDGET_MIB)

        // Nouveau téléphone : mêmes fichiers, autres identifiants ; un dossier d'album sur vingt déplacé.
        val new = Phone("nouveau")
        new.copyLibrary()
        var inspected: DataInspectResult? = null
        val inspectMs = report.once { inspected = new.backups.inspect(archive, PASSWORD.toCharArray()) }
        val backup = (inspected as DataInspectResult.Ready).backup
        report.check("vérification", inspectMs, budgetMs = INSPECT_BUDGET_MS, detail = "${backup.summary.records} enregistrements")
        assertEquals(content.faces, backup.summary.faces)

        val restoreHeap = HeapProbe()
        var restored: DataRestoreResult? = null
        val restoreMs = report.once { restored = backup.use { new.backups.restore(it, onProgress = restoreHeap::onProgress) } } -
            restoreHeap.spentMs
        val result = (restored as DataRestoreResult.Success).report
        report.check("restauration", restoreMs, budgetMs = RESTORE_BUDGET_MS, detail = "${result.matched} médias retrouvés")
        checkHeap("mémoire retenue pendant la restauration", restoreHeap, RESTORE_HEAP_BUDGET_MIB)

        // Tout est retrouvé, au bon endroit malgré les dossiers déplacés.
        assertEquals(result.media, result.matched)
        assertEquals(content.faces, result.faces)
        assertEquals(content.favorites, result.favorites)
        assertEquals(content.people, result.people)
        val db = new.database
        assertEquals(content.faces, db.count("SELECT COUNT(*) FROM face"))
        assertEquals(content.favorites, db.count("SELECT COUNT(*) FROM favorite"))
        assertEquals(content.members, db.count("SELECT COUNT(*) FROM album_media"))
        assertEquals(0, db.count("SELECT COUNT(*) FROM face f LEFT JOIN media m ON m.id = f.media_id WHERE m.id IS NULL"))
        // Les visages rattachés sont sur les mêmes photos (échantillon : noms finissant par 5).
        val sample = old.database.count("SELECT COUNT(*) FROM face f JOIN media m ON m.id = f.media_id WHERE m.display_name LIKE '%5.jpg' AND f.person_id IS NOT NULL")
        assertEquals(sample, db.count("SELECT COUNT(*) FROM face f JOIN media m ON m.id = f.media_id WHERE m.display_name LIKE '%5.jpg' AND f.person_id IS NOT NULL"))
        assertEquals(ThemeMode.DARK, new.settings.settings.first().themeMode)
    }

    /** Vérifie la plus grande mémoire retenue relevée par [probe] (au-delà de celle d'avant l'opération). */
    private fun checkHeap(name: String, probe: HeapProbe, budgetMib: Double) {
        val mib = probe.peak / 1_048_576.0
        report.note(name, mib, unit = "Mio", detail = "budget ${LoadReport.format(budgetMib)} Mio, relevée à ${probe.samples} points")
        assertTrue("$name : relevée ${probe.samples} fois seulement", probe.samples >= 2)
        assertTrue("$name : ${LoadReport.format(mib)} Mio au-delà du budget de ${LoadReport.format(budgetMib)} Mio", mib <= budgetMib)
    }

    /** Contenu sauvegardé attendu. */
    private class Content(val favorites: Int, val albums: Int, val members: Int, val people: Int, val faces: Int, val hiddenFolders: Int)

    /** Un téléphone : sa base ouverte comme en production, ses réglages et sa sauvegarde des données. */
    private inner class Phone(name: String) {
        val database = openLoadTestDatabase(context, "focale-perf-backup-$name.db").also { databases += it }
        val settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name.preferences_pb") },
            scope,
        )
        val backups = DataBackupRepository(
            context.contentResolver, database, settings, PeopleEditions(), { emptyMap() }, {}, Dispatchers.IO,
            iterations = 1_000, appVersion = "1.9.0", clock = { 1_761_000_000_000 }, copies = File(folder.root, "$name-copies"),
        )

        /**
         * Complète l'analyse de la bibliothèque comme l'aurait fait l'utilisateur : 20 000 visages,
         * albums virtuels, dates d'origine, historique, réglages et dossiers masqués.
         */
        suspend fun organize(): Content {
            settings.setThemeMode(ThemeMode.DARK)
            val buckets = library.media.map { it.bucketId }.distinct().take(3)
            buckets.forEach { settings.setFolderHidden(it, true) }
            return database.withTransaction { organizeIndex(buckets.size) }
        }

        private suspend fun organizeIndex(hiddenFolders: Int): Content {
            val photos = library.media.filter { !it.isTrashed && it.mediaType == MediaQueryBuilder.MEDIA_TYPE_IMAGE && !it.isScreenshot && !it.isGif }
            val extra = (library.faces.size until FACES).map {
                val left = random.nextFloat() * 0.8f
                FaceEntity(
                    mediaId = photos[random.nextInt(photos.size)].id,
                    personId = if (random.nextFloat() < 0.85f) random.nextLong(1, 41) else null,
                    boxLeft = left,
                    boxTop = 0.1f,
                    boxRight = left + 0.1f,
                    boxBottom = 0.25f,
                    embedding = random.nextBytes(512),
                    quality = random.nextFloat(),
                )
            }
            extra.chunked(1_000).forEach { database.faceDao().insertFaces(it) }
            var members = 0
            repeat(ALBUMS) { index ->
                val album = database.albumDao().insert(AlbumEntity(name = "Album ${index + 1}", createdAt = 1_700_000_000_000 + index))
                val chosen = photos.shuffled(random).take(random.nextInt(20, 400)).map { AlbumMediaEntity(album, it.id, 1_700_000_000_000) }
                database.albumDao().addMedia(chosen)
                database.albumDao().setCover(album, chosen.first().mediaId)
                members += chosen.size
            }
            library.withoutDateTaken.take(500).forEach { database.mediaDao().upsertOriginalDate(MediaDateEntity(it, 1_500_000_000_000 + it)) }
            repeat(SEARCHES) { index ->
                val query = if (index % 5 == 0) {
                    SearchFilters(chips = listOf(SearchChip.Person(1L + index % 40, "Personne $index"))).historyKey
                } else {
                    "recherche $index"
                }
                database.searchHistoryDao().upsert(SearchHistoryEntity(query, 1_760_000_000_000 + index))
            }
            return Content(
                favorites = library.favorites.size,
                albums = ALBUMS,
                members = members,
                people = database.backupDao().listedPeople((1L..40L).toList(), minPhotos = 3),
                faces = FACES,
                hiddenFolders = hiddenFolders,
            )
        }

        /**
         * Les médias de l'ancien téléphone copiés sur celui-ci : autres `_ID`, ordre d'arrivée
         * conservé ; un dossier d'album sur vingt renommé (appariement par nom, taille et date).
         */
        suspend fun copyLibrary() = database.withTransaction {
            val copies = library.media.filter { !it.isTrashed }.map { media ->
                val moved = media.relativePath.startsWith("Pictures/Album") && media.bucketId % 20 == 0L
                val path = if (moved) "Pictures/Ancien téléphone/${media.bucketName}/" else media.relativePath
                media.copy(id = media.id + ID_SHIFT, relativePath = path, bucketId = SyntheticLibrary.bucketId(path))
            }
            copies.chunked(1_000).forEach { database.mediaDao().upsert(it) }
        }
    }

    /**
     * Mémoire retenue (tas après ramasse-miettes) au-delà de celle d'avant l'opération, relevée à
     * mi-parcours et vers la fin de son avancement : ce que l'opération garde en mémoire, sans ce
     * qu'elle a déjà libéré. Le temps passé à la relever ([spentMs]) est à retirer de sa durée.
     */
    private class HeapProbe {
        private val baseline = retainedHeap()
        private val points = ArrayDeque(listOf(0.5f, 0.85f))
        var peak = 0L
            private set
        var samples = 0
            private set
        var spentMs = 0.0
            private set

        fun onProgress(progress: BackupProgress) {
            if (progress.stage != BackupProgress.Stage.TRANSFERRING) return
            val next = points.firstOrNull() ?: return
            if (progress.fraction < next) return
            points.removeFirst()
            samples++
            val start = System.nanoTime()
            peak = maxOf(peak, retainedHeap() - baseline)
            spentMs += (System.nanoTime() - start) / 1e6
        }
    }

    private suspend fun FocaleDatabase.count(sql: String): Int = withTransaction {
        openHelper.readableDatabase.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    private companion object {
        const val PASSWORD = "cheval correct agrafe"
        const val FACES = 20_000
        const val ALBUMS = 30
        const val SEARCHES = 50
        const val ID_SHIFT = 10_000_000L

        // Durées : environ cinq fois les mesures de docs/PERFORMANCE.md (§ 4.6).
        const val EXPORT_BUDGET_MS = 10_000.0
        const val INSPECT_BUDGET_MS = 2_000.0
        const val RESTORE_BUDGET_MS = 12_000.0

        // Mémoire : environ deux fois la mesure, dépassée si les visages et leurs empreintes (une
        // dizaine de Mio) ou l'archive (11,7 Mio, plus de 20 Mio décompressée) étaient chargés d'un bloc.
        const val EXPORT_HEAP_BUDGET_MIB = 12.0
        const val RESTORE_HEAP_BUDGET_MIB = 28.0
    }
}

/** Tas occupé après ramasse-miettes (plusieurs passages : références faibles, objets à finaliser). */
private fun retainedHeap(): Long {
    val runtime = Runtime.getRuntime()
    repeat(3) { System.gc() }
    return runtime.totalMemory() - runtime.freeMemory()
}
