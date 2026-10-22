package app.focale.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.ml.ImageAnalyzer
import app.focale.ml.semantic.ClipModels
import app.focale.ml.text.TextRecognitionStep
import app.focale.people.FaceAnalysisStep
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plans d'exécution (`EXPLAIN QUERY PLAN`) des requêtes chaudes, tels que SQLite les choisit
 * pour le SQL réellement émis par Room (rappel de requête). Sans statistiques (`ANALYZE`), le
 * plan ne dépend pas des données : une base vide suffit. Ces assertions empêchent le retour d'un
 * tri temporaire de toute la bibliothèque (`TEMP B-TREE`) ou d'un parcours complet là où un index
 * existe : un index sur `is_trashed` seul faisait trier les 50 000 médias à chaque page de la
 * timeline et pour la couverture de chaque dossier (voir docs/PERFORMANCE.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueryPlanTest {

    private val statements = CopyOnWriteArrayList<Pair<String, List<Any?>>>()
    private lateinit var database: FocaleDatabase

    @Before
    fun open() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java)
            .setQueryCallback({ sql, args -> statements += sql to args }, { it.run() })
            .build()
        // Quelques lignes, pour que chaque requête aille jusqu'au bout (le plan ne dépend pas du volume).
        database.mediaDao().upsert((1L..3L).map { media(it) })
    }

    @After
    fun close() = database.close()

    @Test
    fun `la timeline se lit dans l'ordre de son index, sans tri de toute la bibliothèque`() = runBlocking<Unit> {
        println("SQLite ${database.openHelper.readableDatabase.query("SELECT sqlite_version()").use { it.moveToFirst(); it.getString(0) }}")
        val queries = mapOf(
            MediaQuery.Timeline to TIMELINE_INDEX,
            MediaQuery(excludedBuckets = listOf(11L, 12L)) to TIMELINE_INDEX,
            MediaQuery(filter = MediaFilter.PHOTOS) to TYPE_INDEX,
            MediaQuery(filter = MediaFilter.VIDEOS) to TYPE_INDEX,
        )
        for ((query, index) in queries) {
            val plans = plansOf { page(database.mediaDao().pagingSourceOfMedia(MediaQueryBuilder.select(query)), key = 45_000) }
            // Page à une position lointaine : parcours de l'index dans l'ordre de la grille, arrêté après la page.
            val page = plans.single { it.sql.startsWith("SELECT * FROM (") }
            page.assertSearches("m", index)
            page.assertNoTempBTree()
            // Nombre total de la PagingSource (à chaque ouverture, donc à chaque saut).
            val count = plans.single { it.sql.startsWith("SELECT COUNT(*) FROM (") }
            count.assertSearches("m", index)
            count.assertNoTempBTree()
        }
    }

    @Test
    fun `grille d'un dossier et couverture de chaque dossier par l'index des dossiers`() = runBlocking<Unit> {
        val folder = plansOf { page(database.mediaDao().pagingSourceOfMedia(MediaQueryBuilder.select(MediaQuery(bucketId = 42L))), key = 20_000) }
            .single { it.sql.startsWith("SELECT * FROM (") }
        folder.assertSearches("m", FOLDER_INDEX)
        folder.assertNoTempBTree()

        // Liste des dossiers : la couverture de chacun est la première entrée de son index.
        val buckets = plansOf { database.mediaDao().observeBuckets(MediaQueryBuilder.buckets()).first() }.single()
        val cover = buckets.subtree("CORRELATED SCALAR SUBQUERY")
        cover.assertSearches("c", FOLDER_INDEX)
        cover.assertNoTempBTree()
    }

    @Test
    fun `compteurs, histogramme et position dans la visionneuse sans lire la table`() = runBlocking<Unit> {
        val dao = database.mediaDao()
        plansOf { dao.observeCountOfMedia(MediaQueryBuilder.count(MediaQuery.Timeline)).first() }.single().assertCovering("m", TIMELINE_INDEX)
        plansOf { dao.observeTotalCount().first() }.single().assertCovering("media", TIMELINE_INDEX)
        // Histogramme : tous les médias, mais par l'index seul (le regroupement par mois local reste un tri).
        plansOf { dao.observeHistogramOfMedia(MediaQueryBuilder.histogram(MediaQuery.Timeline, GroupLevel.MONTH)).first() }
            .single().assertCovering("m", TIMELINE_INDEX)
        plansOf { dao.count(MediaQueryBuilder.position(MediaQuery.Timeline, media(2))) }.single().assertCovering("m", TIMELINE_INDEX)
        // Compteurs des filtres (onglet Albums) : index des types seul.
        plansOf { dao.observeCountOfMedia(MediaQueryBuilder.count(MediaQuery(filter = MediaFilter.VIDEOS))).first() }
            .single().assertCovering("m", TYPE_INDEX)
    }

    private fun media(id: Long) = MediaEntity(
        id = id, mediaType = 1, mimeType = "image/jpeg", displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/",
        bucketId = 42, bucketName = "Camera", dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = id * 1_000,
        generation = id, size = 1, width = 1, height = 1, orientation = 0, duration = 0, volumeName = "external_primary",
        isScreenshot = false, isGif = false, isTrashed = false, dateExpires = 0,
    )

    @Test
    fun `l'analyse suit l'index de la timeline et chaque lot reprend après le précédent`() = runBlocking<Unit> {
        val analysis = database.analysisDao()
        val pending = plansOf { analysis.pending(ImageAnalyzer.VERSION, 24) }.single()
        pending.assertSearches("m", TIMELINE_INDEX)
        pending.assertSearches("a", "INTEGER PRIMARY KEY")
        pending.assertNoTempBTree()
        // Reprise : le parcours de l'index part du dernier média du lot précédent.
        val after = plansOf { analysis.pendingAfter(ImageAnalyzer.VERSION, 1_000, 7, 24) }.single()
        after.assertSearches("m", TIMELINE_INDEX, constraint = "date_taken<?")
        after.assertNoTempBTree()
        // Étapes complémentaires (photos seules) : index des types, dans l'ordre et sans tri.
        for (plan in listOf(
            plansOf { database.faceDao().pending(FaceAnalysisStep.VERSION, 16) }.single(),
            plansOf { database.embeddingDao().pending(ClipModels.VERSION, 16) }.single(),
        )) {
            plan.assertSearches("m", TYPE_INDEX)
            plan.assertNoTempBTree()
        }
        for (plan in listOf(
            plansOf { database.faceDao().pendingAfter(FaceAnalysisStep.VERSION, 1_000, 7, 16) }.single(),
            plansOf { database.embeddingDao().pendingAfter(ClipModels.VERSION, 1_000, 7, 16) }.single(),
            plansOf { database.textDao().pendingAfter(TextRecognitionStep.VERSION, screenshot = true, dateTaken = 1_000, id = 7, limit = 16) }.single(),
            plansOf { database.textDao().pendingAfter(TextRecognitionStep.VERSION, screenshot = false, dateTaken = 1_000, id = 7, limit = 16) }.single(),
        )) {
            plan.assertSearches("m", TYPE_INDEX, constraint = "date_taken<?")
            plan.assertNoTempBTree()
        }
    }

    @Test
    fun `les compteurs d'avancement lisent media et la table de l'étape dans l'ordre des identifiants`() = runBlocking<Unit> {
        // Toute la table est comptée : l'ordre des identifiants lit les deux tables dans le même
        // ordre, là où un index trié par date les lirait au hasard (trois fois plus lent à 50 000).
        val plans = listOf(
            plansOf { database.analysisDao().observePendingCount(ImageAnalyzer.VERSION).first() } to "a",
            plansOf { database.textDao().observeProgress(TextRecognitionStep.VERSION).first() } to "t",
            plansOf { database.faceDao().observeProgress(FaceAnalysisStep.VERSION).first() } to "s",
            plansOf { database.embeddingDao().observeProgress(ClipModels.VERSION).first() } to "e",
        )
        for ((statements, step) in plans) {
            val plan = statements.single()
            plan.assertScans("m")
            plan.assertSearches(step, "INTEGER PRIMARY KEY")
            plan.assertNoTempBTree()
        }
    }

    @Test
    fun `la recherche par étiquette passe par l'index des étiquettes`() = runBlocking<Unit> {
        val dog = MediaQuery(terms = listOf(SearchTerm(labels = listOf("Dog", "Poodle"))))
        val plans = plansOf { page(database.mediaDao().pagingSourceWithAnalysis(MediaQueryBuilder.select(dog)), key = 0) } +
            plansOf { database.mediaDao().observeCountWithAnalysis(MediaQueryBuilder.count(dog)).first() }
        for (plan in plans) {
            plan.assertSearches("media_label", "index_media_label_label")
            plan.assertNoScan("media_label")
        }
        // Onglet Recherche : la couverture de chaque étiquette ne parcourt que ses médias.
        val stats = plansOf { database.analysisDao().observeLabelStats(60).first() }.single()
        val cover = stats.subtree("CORRELATED SCALAR SUBQUERY")
        cover.assertSearches("l2", "index_media_label_label")
        cover.assertNoScan("l2")
    }

    @Test
    fun `la sauvegarde des données lit chaque table par tranches, dans l'ordre de sa clé`() = runBlocking<Unit> {
        // Chaque tranche reprend après la précédente : sans cela, chacune relirait la table depuis le début.
        val dao = database.backupDao()
        val paged = listOf(
            plansOf { dao.mediaRefs(after = 7, limit = 1_000) }.single() to "m",
            plansOf { dao.faceScans(after = 7, limit = 1_000) }.single() to "s",
            plansOf { dao.faces(after = 7, limit = 500) }.single() to "f",
            plansOf { dao.library(after = 7, limit = 2_000) }.single() to "media",
        )
        for ((plan, table) in paged) {
            plan.assertSearches(table, "INTEGER PRIMARY KEY", constraint = "rowid>?")
            plan.assertNoTempBTree()
        }
        val members = plansOf { dao.albumMembers(album = 3, media = 7, limit = 1_000) }.single()
        members.assertSearches("am", "sqlite_autoindex_album_media_1", constraint = "(album_id,media_id)>(?,?)")
        members.assertNoTempBTree()
    }

    /** Charge une page de [source] à la position [key], comme Paging (chargement initial). */
    private suspend fun page(source: PagingSource<Int, MediaEntity>, key: Int) {
        source.load(PagingSource.LoadParams.Refresh(key = key, loadSize = 360, placeholdersEnabled = true))
    }

    /** Plans des requêtes de lecture émises par [block] (hors suivi des tables par Room). */
    private suspend fun plansOf(block: suspend () -> Unit): List<Plan> {
        statements.clear()
        block()
        return statements
            .filter { (sql, _) -> sql.trimStart().startsWith("SELECT", ignoreCase = true) && "room_table_modification_log" !in sql }
            .map { (sql, args) -> explain(sql.trim(), args) }
    }

    private fun explain(sql: String, args: List<Any?>): Plan {
        val rows = database.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", args.toTypedArray())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PlanRow(cursor.getInt(0), cursor.getInt(1), cursor.getString(3)))
            }
        }
        return Plan(sql, rows).also { println("PLAN\t${it.sql.take(120)}\n\t${it.text}") }
    }

    /** Ligne d'`EXPLAIN QUERY PLAN` : nœud [id] sous [parent]. */
    private class PlanRow(val id: Int, val parent: Int, val detail: String)

    private class Plan(val sql: String, val rows: List<PlanRow>) {
        val text: String get() = rows.joinToString(" | ") { it.detail }

        /** Sous-arbre du premier nœud dont le détail commence par [prefix] (nœud exclu). */
        fun subtree(prefix: String): Plan {
            val root = rows.first { it.detail.startsWith(prefix) }
            val ids = mutableSetOf(root.id)
            val inside = rows.filter { row -> (row.parent in ids).also { if (it) ids += row.id } }
            return Plan(sql, inside)
        }

        // Formats de SQLite : « SEARCH TABLE media AS m USING … » (avant 3.36) ou « SEARCH m USING … ».

        /** Recherche de [table] (alias ou nom) par [index] (nom d'index ou « INTEGER PRIMARY KEY »). */
        fun assertSearches(table: String, index: String, constraint: String? = null) {
            val pattern = Regex("""^SEARCH (TABLE )?(\w+ AS )?$table USING (COVERING )?(INDEX )?$index\b(.*)""")
            val match = rows.firstNotNullOfOrNull { pattern.find(it.detail) }
            assertTrue("$table devrait passer par $index : $text\n$sql", match != null)
            if (constraint != null) assertTrue("contrainte $constraint attendue : $text", constraint in match!!.groupValues[5])
        }

        /** Lecture de [table] par l'index seul, sans ouvrir ses lignes. */
        fun assertCovering(table: String, index: String) {
            val pattern = Regex("""^SEARCH (TABLE )?(\w+ AS )?$table USING COVERING INDEX $index\b""")
            assertTrue("$table devrait être lue par l'index $index seul : $text\n$sql", rows.any { pattern.containsMatchIn(it.detail) })
        }

        fun assertNoTempBTree() = assertFalse("tri temporaire : $text\n$sql", rows.any { "TEMP B-TREE" in it.detail })

        /** Parcours de toute la table [table] dans l'ordre de ses identifiants, sans index. */
        fun assertScans(table: String) {
            val pattern = Regex("""^SCAN (TABLE )?(\w+ AS )?$table$""")
            assertTrue("$table devrait être lue dans l'ordre des identifiants : $text\n$sql", rows.any { pattern.containsMatchIn(it.detail) })
        }

        /** Aucun parcours complet de [table] (SCAN), alors qu'un index permet de la chercher. */
        fun assertNoScan(table: String) {
            val pattern = Regex("""^SCAN (TABLE )?(\w+ AS )?$table\b""")
            assertFalse("parcours complet de $table : $text\n$sql", rows.any { pattern.containsMatchIn(it.detail) })
        }
    }

    private companion object {
        const val TIMELINE_INDEX = "index_media_is_trashed_date_taken"
        const val FOLDER_INDEX = "index_media_bucket_id_is_trashed_date_taken"
        const val TYPE_INDEX = "index_media_media_type_is_trashed_date_taken"
    }
}
