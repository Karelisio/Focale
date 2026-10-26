package app.focale.core.database

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.data.MediaRepository
import app.focale.ml.ImageAnalyzer
import app.focale.ml.semantic.ClipModels
import app.focale.ml.text.TextRecognitionStep
import app.focale.people.FaceAnalysisStep
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
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
    private lateinit var repository: MediaRepository

    @Before
    fun open() = runBlocking<Unit> {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FocaleDatabase::class.java)
            .setQueryCallback({ sql, args -> statements += sql to args }, { it.run() })
            .build()
        repository = MediaRepository(database.mediaDao(), Dispatchers.IO, database.invalidationTracker)
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
            val plans = plansOf { page(repository.pagingSource(query), key = 45_000) }
            // Page à une position lointaine : parcours de l'index dans l'ordre de la grille, arrêté après la page.
            val page = plans.single { it.sql.startsWith("SELECT m.* FROM media m") }
            page.assertSearches("m", index)
            page.assertNoTempBTree()
            // La base de test n'a que trois médias : la page lointaine elle-même, par son SQL.
            val far = explain(MediaQueryBuilder.select(query, limit = 360, offset = 45_000).sql, emptyList())
            assertTrue(far.sql, far.sql.endsWith("LIMIT 360 OFFSET 45000"))
            far.assertSearches("m", index)
            far.assertNoTempBTree()
            // Nombre total de la PagingSource (à chaque ouverture, donc à chaque saut) : l'index seul,
            // sans relire la requête triée ligne à ligne (`SELECT COUNT(*) FROM (…)` de Room).
            val count = plans.single { it.sql.startsWith("SELECT COUNT(*) FROM media m") }
            if (query.excludedBuckets.isEmpty()) count.assertCovering("m", index) else count.assertSearches("m", index)
            count.assertNoTempBTree()
            assertFalse(plans.any { it.sql.startsWith("SELECT COUNT(*) FROM (") })
        }
    }

    @Test
    fun `grille d'un dossier et couverture de chaque dossier par l'index des dossiers`() = runBlocking<Unit> {
        val folder = plansOf { page(repository.pagingSource(MediaQuery(bucketId = 42L)), key = 20_000) }
            .single { it.sql.startsWith("SELECT m.* FROM media m") }
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
        plansOf { dao.count(MediaQueryBuilder.position(MediaQuery.Timeline, media(2))) }.single().assertCovering("m", TIMELINE_INDEX)
        // Compteurs des filtres (onglet Albums) : index des types seul.
        plansOf { dao.observeCountOfMedia(MediaQueryBuilder.count(MediaQuery(filter = MediaFilter.VIDEOS))).first() }
            .single().assertCovering("m", TYPE_INDEX)
    }

    @Test
    fun `histogramme et position dans la grille par plages de dates de l'index, sans date locale par média`() = runBlocking<Unit> {
        // Histogramme (bulle de date) et en-têtes avant un média (retour de la visionneuse) : bornes
        // des périodes calculées en Kotlin, chaque période cherchée dans l'index par sa plage de dates.
        for ((query, index) in mapOf(MediaQuery.Timeline to TIMELINE_INDEX, MediaQuery(filter = MediaFilter.PHOTOS) to TYPE_INDEX)) {
            val plans = (
                plansOf { repository.histogram(query, GroupLevel.MONTH) } +
                    plansOf { repository.gridPositionOf(query, mediaId = 2, level = GroupLevel.DAY) }
                ).filter { "FROM media m" in it.sql }
            // Dates extrêmes, comptes par période, position du média, périodes non vides avant lui.
            assertTrue(plans.joinToString("\n") { it.sql }, plans.size == 4)
            for (plan in plans) {
                assertFalse("date locale calculée pour chaque média : ${plan.sql}", "strftime" in plan.sql)
                plan.assertCovering("m", index)
                plan.assertNoTempBTree()
            }
            for (plan in plans.filter { "column1" in it.sql }) plan.assertSearches("m", index, constraint = "date_taken<?")
        }
    }

    @Test
    fun `en-têtes avant un média lointain, mois puis jours sondés dans l'index d'une table récursive`() = runBlocking<Unit> {
        // Deux ans après les autres : plus de deux mois de jours, d'où les mois non vides d'abord.
        database.mediaDao().upsert(listOf(media(4).copy(dateTaken = 2 * 365 * DAY_MS)))
        val plans = plansOf { repository.gridPositionOf(MediaQuery.Timeline, mediaId = 2, level = GroupLevel.DAY) }
            .filter { it.sql.startsWith("WITH RECURSIVE") }
        // Mois non vides, puis jours de ces mois : une requête chacun, quelques suites de périodes.
        assertTrue(plans.joinToString("\n") { it.sql }, plans.size == 2)
        assertTrue(plans[0].sql, plans[0].sql.contains("AS period_start"))
        for (plan in plans) {
            assertFalse(plan.sql, "strftime" in plan.sql)
            plan.assertCovering("m", TIMELINE_INDEX)
            plan.assertSearches("m", TIMELINE_INDEX, constraint = "date_taken<?")
            plan.assertNoTempBTree()
        }
    }

    @Test
    fun `mois de toutes les années par l'index de la timeline, sans date locale par média`() = runBlocking<Unit> {
        val july = MediaQuery(month = 7)
        val count = plansOf { database.mediaDao().observeCountOfMedia(MediaQueryBuilder.count(july)).first() }.single()
        count.assertCovering("m", TIMELINE_INDEX)
        val page = plansOf { page(repository.pagingSource(july), key = 0) }.single { it.sql.startsWith("SELECT m.* FROM media m") }
        page.assertSearches("m", TIMELINE_INDEX)
        for (plan in listOf(count, page)) {
            plan.assertNoTempBTree()
            // Le mois local de SQLite ne sert qu'aux dates hors des années bornées en Kotlin.
            assertFalse(plan.sql, "strftime" in plan.sql.substringAfter(" THEN ").substringAfter(" ELSE "))
        }
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
            plansOf { database.analysisDao().pendingCount(ImageAnalyzer.VERSION) } to "a",
            plansOf { database.textDao().progress(TextRecognitionStep.VERSION) } to "t",
            plansOf { database.faceDao().progress(FaceAnalysisStep.VERSION) } to "s",
            plansOf { database.embeddingDao().progress(ClipModels.VERSION) } to "e",
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
        val page = plansOf { page(repository.pagingSource(dog), key = 0) }.filter { "FROM media m" in it.sql }
        val count = plansOf { database.mediaDao().observeCountWithAnalysis(MediaQueryBuilder.count(dog)).first() }.single()
        for (plan in page + count) {
            plan.assertCovering("media_label", LABEL_INDEX)
            plan.assertNoScan("media_label")
        }
        // Nombre de résultats : depuis les médias de l'étiquette (par la clé primaire), pas depuis
        // toute la timeline ; de même pour une personne et les favoris.
        count.assertSearches("m", "INTEGER PRIMARY KEY")
        for (query in listOf(MediaQuery(personId = 3), MediaQuery(favoritesOnly = true))) {
            plansOf { database.mediaDao().observeCount(MediaQueryBuilder.count(query)).first() }.single().assertSearches("m", "INTEGER PRIMARY KEY")
        }
        // Mot de lettres : les noms par l'index plein texte, puis chaque alternative depuis ses médias
        // (clé primaire), sans lire toute la table `media` ; les pages gardent l'index des dates.
        val words = MediaQuery(terms = listOf(SearchTerm(labels = listOf("Dog"), text = "chien")))
        val wordCount = plansOf { database.mediaDao().observeCountWithAnalysis(MediaQueryBuilder.count(words)).first() }.single()
        wordCount.assertSearches("m", "INTEGER PRIMARY KEY")
        wordCount.assertNoScan("m")
        wordCount.assertFullText("media_name")
        val wordPage = plansOf { page(repository.pagingSource(words), key = 0) }.single { it.sql.startsWith("SELECT m.* FROM media m") }
        wordPage.assertSearches("m", TIMELINE_INDEX)
        wordPage.assertFullText("media_name")
        wordPage.assertNoTempBTree()
        // Texte reconnu : débuts de mots par son index plein texte ; le LIKE sur chaque texte (repli
        // quand aucun mot ne commence ainsi) ne se lit qu'à travers la ligne du repli, vide sinon.
        for (plan in listOf(wordCount, wordPage)) {
            plan.assertFullText("media_text_fts")
            plan.assertGated(gate = "g", table = "media_text", alias = "t")
        }
        // Texte avec un chiffre ou un séparateur : le nom de chaque média, `media` lue dans l'ordre des identifiants.
        val digits = MediaQuery(terms = listOf(SearchTerm(labels = listOf("Dog"), text = "img2024")))
        plansOf { database.mediaDao().observeCountWithAnalysis(MediaQueryBuilder.count(digits)).first() }.single().assertScans("m")
    }

    @Test
    fun `un lieu se cherche parmi les clés distinctes, puis ses médias par l'index des clés`() = runBlocking<Unit> {
        val malo = MediaQuery(terms = listOf(SearchTerm(place = "malo")))
        val count = plansOf { database.mediaDao().observeCountWithAnalysis(MediaQueryBuilder.count(malo)).first() }.single()
        val page = plansOf { page(repository.pagingSource(malo), key = 0) }.single { it.sql.startsWith("SELECT m.* FROM media m") }
        for (plan in listOf(count, page)) {
            plan.assertScans("place_count")
            plan.assertSearches("media_analysis", PLACE_INDEX, constraint = "place_key=?")
            plan.assertNoScan("media_analysis")
        }
        // Mot d'un lieu (recherche par le sens) : les clés distinctes seules.
        val word = plansOf { database.embeddingDao().isPlaceWord("malo") }.single()
        word.assertScans("place_count")
        word.assertNoScan("media_analysis")
        // Comptes des lieux tenus à chaque lot : les analyses du lot par leur clé primaire ;
        // recalcul complet par l'index des clés, sans tri.
        for (tally in listOf(
            plansOf(writes = true) { database.analysisDao().uncountPlaces(listOf(1L, 2L, 3L)) }.single(),
            plansOf(writes = true) { database.analysisDao().countPlaces(listOf(1L, 2L, 3L)) }.single(),
        )) {
            tally.assertSearches("media_analysis", "INTEGER PRIMARY KEY")
            tally.assertNoScan("media_analysis")
        }
        val recount = plansOf(writes = true) { database.analysisDao().countAllPlaces() }.single()
        recount.assertIndexOnly("media_analysis", PLACE_INDEX)
        recount.assertNoTempBTree()
    }

    @Test
    fun `le contrôle des comptes lit les lignes comptées sans les regrouper`() = runBlocking<Unit> {
        val analysis = database.analysisDao()
        // Étiquettes des médias visibles : media_label par l'index de sa clé primaire seul, le média
        // de chacune par la sienne, sans regroupement.
        val labels = plansOf { analysis.visibleLabelTotal() }.single()
        labels.assertIndexOnly("l", "sqlite_autoindex_media_label_1")
        labels.assertSearches("m", "INTEGER PRIMARY KEY")
        labels.assertNoTempBTree()
        // Analyses avec un lieu : l'index des clés seul.
        plansOf { analysis.placedTotal() }.single().assertIndexOnly("media_analysis", PLACE_INDEX)
        for (total in listOf(plansOf { analysis.labelCountTotal() }.single(), plansOf { analysis.placeCountTotal() }.single())) {
            total.assertNoTempBTree()
        }
    }

    @Test
    fun `étiquettes de l'onglet Recherche lues dans leurs comptes, couverture des seules retenues`() = runBlocking<Unit> {
        val stats = plansOf { database.analysisDao().labelStats(60) }.single()
        // Nombres tenus à jour dans label_count : aucune ligne de media_label relue pour compter.
        stats.assertScans("label_count")
        stats.assertNoScan("media_label")
        stats.assertNoScan("l2")
        assertFalse(stats.text, "TEMP B-TREE FOR GROUP BY" in stats.text)
        // Couverture : la première de l'index de l'étiquette, par confiance décroissante.
        val cover = stats.subtree("CORRELATED SCALAR SUBQUERY")
        cover.assertCovering("l2", LABEL_INDEX)
        cover.assertNoScan("l2")
        // Tri des seules égalités de confiance (« RIGHT PART »), jamais de toutes ses photos.
        assertFalse(cover.text, cover.rows.any { it.detail == "USE TEMP B-TREE FOR ORDER BY" })
        // Comptes tenus à chaque lot et à chaque synchronisation : les étiquettes du lot seules, par
        // leur clé primaire, jamais toute la timeline.
        for (tally in listOf(
            plansOf(writes = true) { database.analysisDao().uncountLabels(listOf(1L, 2L, 3L)) }.single(),
            plansOf(writes = true) { database.analysisDao().countLabels(listOf(1L, 2L, 3L)) }.single(),
        )) {
            tally.assertSearches("l", "sqlite_autoindex_media_label_1", constraint = "media_id=?")
            tally.assertSearches("m", "INTEGER PRIMARY KEY")
            tally.assertNoScan("m")
            tally.assertNoScan("l")
        }
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

    /** Plans des requêtes de lecture émises par [block] (hors suivi des tables par Room), et des insertions avec [writes]. */
    private suspend fun plansOf(writes: Boolean = false, block: suspend () -> Unit): List<Plan> {
        statements.clear()
        block()
        val kinds = listOf("SELECT", "WITH") + if (writes) listOf("INSERT") else emptyList()
        return statements
            .filter { (sql, _) -> kinds.any { sql.trimStart().startsWith(it, ignoreCase = true) } && "room_table_modification_log" !in sql }
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

        /** Lecture de [table] par l'index [index] seul, en entier ou par une plage. */
        fun assertIndexOnly(table: String, index: String) {
            val pattern = Regex("""^(SCAN|SEARCH) (TABLE )?(\w+ AS )?$table USING COVERING INDEX $index\b""")
            assertTrue("$table devrait être lue par l'index $index seul : $text\n$sql", rows.any { pattern.containsMatchIn(it.detail) })
        }

        fun assertNoTempBTree() = assertFalse("tri temporaire : $text\n$sql", rows.any { "TEMP B-TREE" in it.detail })

        /** Parcours de toute la table [table] dans l'ordre de ses identifiants, sans index. */
        fun assertScans(table: String) {
            val pattern = Regex("""^SCAN (TABLE )?(\w+ AS )?$table$""")
            assertTrue("$table devrait être lue dans l'ordre des identifiants : $text\n$sql", rows.any { pattern.containsMatchIn(it.detail) })
        }

        /** Recherche dans l'index plein texte [table] (FTS4 : `MATCH`). */
        fun assertFullText(table: String) {
            val pattern = Regex("""^SCAN (TABLE )?$table VIRTUAL TABLE INDEX \d+:""")
            assertTrue("$table devrait être cherchée par son index plein texte : $text\n$sql", rows.any { pattern.containsMatchIn(it.detail) })
        }

        /**
         * [table] (alias [alias]) parcourue seulement sous la sous-requête [gate], boucle extérieure
         * de leur jointure : rien n'est lu quand elle ne rend aucune ligne.
         */
        fun assertGated(gate: String, table: String, alias: String) {
            val outer = rows.indexOfFirst { Regex("""^SCAN (SUBQUERY \d+ AS )?$gate$""").matches(it.detail) }
            val inner = rows.indexOfFirst { Regex("""^SCAN (TABLE )?($table AS )?$alias$""").matches(it.detail) }
            assertTrue("$table devrait être lue sous $gate : $text\n$sql", outer >= 0 && inner > outer && rows[outer].parent == rows[inner].parent)
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
        const val LABEL_INDEX = "index_media_label_label_confidence_media_id"
        const val PLACE_INDEX = "index_media_analysis_place_key"
        const val DAY_MS = 86_400_000L
    }
}
