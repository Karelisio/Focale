package app.focale.perf

import android.app.Application
import androidx.paging.PagingData
import androidx.paging.PagingSource.LoadResult
import androidx.paging.testing.TestPager
import androidx.paging.testing.asSnapshot
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.model.GridItem
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.data.AlbumRepository
import app.focale.data.DateGrouping
import app.focale.data.MediaRepository
import app.focale.data.toGridItems
import app.focale.data.toModel
import app.focale.ml.SearchQueryParser
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Charge : chemin du défilement sur une bibliothèque analysée de 50 000 médias. Pages Paging de
 * la timeline (continu et sauts de la barre de défilement rapide), en-têtes de date, bulle de
 * date, retour de la visionneuse, dossiers, compteurs et recherche, par les DAO et repositories
 * de l'application. Mesures consignées dans docs/PERFORMANCE.md ; budgets : environ cinq fois
 * les médianes mesurées, de quoi signaler le retour d'un tri de toute la bibliothèque par page
 * (près de 100 ms au lieu de 3) sans rendre la CI instable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimelineScrollLoadTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = SyntheticLibrary.Default
    private val report = LoadReport("défilement")
    private lateinit var database: FocaleDatabase
    private lateinit var repository: MediaRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        database = openLoadTestDatabase(context)
        val fill = report.once {
            database.insertMedia(library)
            database.insertAnalysis(library)
        }
        report.note("remplissage de la base (index, analyse, texte, visages)", fill)
        repository = MediaRepository(database.mediaDao(), Dispatchers.IO, database.invalidationTracker)
    }

    @After
    fun tearDown() = database.close()

    private fun timelineSource(query: MediaQuery = MediaQuery.Timeline) = repository.pagingSource(query)

    @Test
    fun `pages de la timeline en défilement continu puis par sauts de la barre de défilement`() = runBlocking<Unit> {
        val expected = library.timelineOrder
        val pager = TestPager(MediaRepository.PAGING_CONFIG, timelineSource())
        val loaded = ArrayList<Long>(expected.size)
        var first: LoadResult.Page<Int, MediaEntity>? = null
        val initial = report.once { first = pager.refresh() as LoadResult.Page }
        first!!.data.mapTo(loaded) { it.id }
        val appends = ArrayList<Double>()
        while (true) {
            var page: LoadResult.Page<Int, MediaEntity>? = null
            appends += report.once { page = pager.append() as LoadResult.Page? }
            val loadedPage = page ?: break
            loadedPage.data.mapTo(loaded) { it.id }
            if (loadedPage.nextKey == null) break
        }
        // Toute la liste, dans l'ordre de la grille, sans trou ni doublon.
        assertEquals(expected.map { it.id }, loaded)
        report.check("chargement initial (360 médias + COUNT)", initial, budgetMs = JUMP_BUDGET_MS)
        report.check("page suivante (120 médias), toute la liste", appends.toDoubleArray(), budgetMs = 15.0, detail = "${appends.size} pages")
        report.check("page suivante, 20 dernières pages", appends.takeLast(20).toDoubleArray(), budgetMs = 25.0)

        // Sauts de la barre de défilement rapide : Paging ouvre une nouvelle PagingSource à la
        // position visée (clé de MediaPagingSource, comme celle de Room : position - initialLoadSize / 2).
        val targets = (0 until JUMPS).map { ((it + 0.5) / JUMPS * expected.size).toInt() }
        val jumps = report.samples(repeat = JUMPS) { index ->
            val target = targets[if (index < 0) 0 else index]
            val key = (target - MediaRepository.PAGING_CONFIG.initialLoadSize / 2).coerceAtLeast(0)
            val page = TestPager(MediaRepository.PAGING_CONFIG, timelineSource()).refresh(initialKey = key) as LoadResult.Page
            assertEquals(expected[key].id, page.data.first().id)
            assertEquals(key, page.itemsBefore)
        }
        report.check("saut de la barre de défilement (nouvelle source, 360 médias + COUNT)", jumps, budgetMs = JUMP_BUDGET_MS)
        val deepest = report.samples(repeat = 3) {
            val key = expected.size - MediaRepository.PAGING_CONFIG.initialLoadSize
            TestPager(MediaRepository.PAGING_CONFIG, timelineSource()).refresh(initialKey = key)
        }
        report.check("saut tout au bout de la liste", deepest, budgetMs = JUMP_BUDGET_MS)
        // Le nombre total de chaque nouvelle source passe par l'index seul (MediaPagingSource) ;
        // celui de LimitOffsetPagingSource (sous-requête triée, lignes entières), mesuré pour
        // comparaison, l'emportait sur la page dans chaque saut.
        val select = MediaQueryBuilder.select(MediaQuery.Timeline)
        val roomCount = report.samples(repeat = 3) {
            withContext(Dispatchers.IO) { database.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM ( ${select.sql} )")).use { it.moveToFirst() } }
        }
        report.check("COUNT(*) de la requête triée (LimitOffsetPagingSource, avant)", roomCount, budgetMs = 300.0)
        val indexCount = report.samples(repeat = 3) { database.mediaDao().count(MediaQueryBuilder.count(MediaQuery.Timeline)) }
        report.check("COUNT(*) par l'index (MediaPagingSource, compteur de la timeline)", indexCount, budgetMs = 15.0)

        // Filtre « Photos » (95 % des médias) : même parcours, tout au bout.
        val photos = MediaQuery(filter = MediaFilter.PHOTOS)
        val photosEnd = report.samples(repeat = 3) {
            TestPager(MediaRepository.PAGING_CONFIG, timelineSource(photos)).refresh(initialKey = 45_000)
        }
        report.check("saut tout au bout, filtre Photos", photosEnd, budgetMs = JUMP_BUDGET_MS)
    }

    @Test
    fun `en-têtes de date, bulle de la barre de défilement et retour de la visionneuse`() = runBlocking<Unit> {
        val expected = library.timelineOrder
        val items = expected.map(MediaEntity::toModel)
        val models = report.samples(repeat = 3) { expected.map(MediaEntity::toModel) }
        report.check("conversion en MediaItem (50 000 médias)", models, budgetMs = 300.0)

        // Séparateurs insérés par Paging (insertSeparators), ici sur toute la liste d'un coup.
        val grids = HashMap<GroupLevel, List<GridItem>>()
        for (level in GroupLevel.entries) {
            var headers = 0
            val separators = report.samples(repeat = 3) {
                val grid = flowOf(PagingData.from(items)).asSnapshotOf(level)
                headers = grid.count { it is GridItem.Header }
                assertEquals(items.size + headers, grid.size)
                grids[level] = grid
            }
            report.check("en-têtes ${level.name} sur 50 000 médias", separators, budgetMs = 150.0, detail = "$headers en-têtes")
        }

        // Histogramme mensuel de la bulle de date : exact sans charger les pages, mêmes périodes
        // que les en-têtes de la grille (fuseau courant).
        val zone = ZoneId.systemDefault()
        val monthHeaders = grids.getValue(GroupLevel.MONTH).filterIsInstance<GridItem.Header>().map { it.periodStart }
        var months = 0
        val histogram = report.samples(repeat = 5) {
            val buckets = repository.observeHistogram(MediaQuery.Timeline, GroupLevel.MONTH, zone).first()
            assertEquals(expected.size, buckets.sumOf { it.count })
            assertEquals(monthHeaders, buckets.map { it.periodStart })
            months = buckets.size
        }
        report.check("histogramme mensuel (bulle de date)", histogram, budgetMs = HISTOGRAM_BUDGET_MS, detail = "$months mois")

        // Retour de la visionneuse : position du média dans la grille (COUNT + en-têtes avant lui).
        val sampleIndexes = (0 until 10).map { (it * 4_957L % expected.size).toInt() }
        val positions = report.samples(repeat = sampleIndexes.size) { index ->
            val position = sampleIndexes[if (index < 0) 0 else index]
            assertEquals(position, repository.positionOf(MediaQuery.Timeline, expected[position].id))
        }
        report.check("position d'un média (visionneuse)", positions, budgetMs = 25.0)
        // Index exact dans la grille de Paging, en-têtes de jour compris.
        val dayGrid = grids.getValue(GroupLevel.DAY).withIndex()
            .mapNotNull { (index, item) -> (item as? GridItem.Media)?.let { it.item.id to index } }.toMap()
        val gridPositions = report.samples(repeat = sampleIndexes.size) { index ->
            val position = sampleIndexes[if (index < 0) 0 else index]
            val id = expected[position].id
            assertEquals(dayGrid.getValue(id), repository.gridPositionOf(MediaQuery.Timeline, id, GroupLevel.DAY, zone))
        }
        report.check("position dans la grille avec en-têtes de jour (retour de la visionneuse)", gridPositions, budgetMs = GRID_POSITION_BUDGET_MS)
        // Au bout de la timeline (3 500 jours à sonder) : le cas le plus long.
        val oldest = report.samples(repeat = 3) {
            assertEquals(dayGrid.getValue(expected.last().id), repository.gridPositionOf(MediaQuery.Timeline, expected.last().id, GroupLevel.DAY, zone))
        }
        report.check("position dans la grille, média le plus ancien", oldest, budgetMs = 2 * GRID_POSITION_BUDGET_MS)
    }

    @Test
    fun `position du média le plus ancien d'une bibliothèque de trente ans`() = runBlocking<Unit> {
        // Vingt ans de plus avant les dix de la bibliothèque, deux photos par an (albums numérisés,
        // anciens téléphones) : 7 300 jours de plus jusqu'au début de la grille, presque tous vides.
        val model = library.timelineOrder.first()
        val older = (1..40).map { i -> model.copy(id = OLDER_FIRST_ID + i, dateTaken = SyntheticLibrary.START_MS - i * 182L * DAY_MS) }
        database.mediaDao().upsert(older)
        val zone = ZoneId.systemDefault()
        val timeline = library.timelineOrder + older
        val days = timeline.mapTo(HashSet()) { DateGrouping.periodKey(it.dateTaken, GroupLevel.DAY, zone) }.size
        val oldest = older.last()
        val samples = report.samples(repeat = 3) {
            // Index dans la grille : les médias qui le précèdent, plus un en-tête par jour jusqu'au sien.
            assertEquals(timeline.size - 1 + days, repository.gridPositionOf(MediaQuery.Timeline, oldest.id, GroupLevel.DAY, zone))
        }
        report.check("position dans la grille, média le plus ancien sur trente ans", samples, budgetMs = 2 * GRID_POSITION_BUDGET_MS, detail = "$days jours non vides")
    }

    @Test
    fun `dossiers, compteurs et recherche`() = runBlocking<Unit> {
        val expected = library.timelineOrder
        val albums = AlbumRepository(database.albumDao(), database.mediaDao(), repository, Dispatchers.IO)
        var folders = 0
        val folderList = report.samples(repeat = 3) { folders = albums.observeFolders().first().size }
        assertEquals(expected.mapTo(HashSet()) { it.bucketId }.size, folders)
        report.check("liste des dossiers (onglet Albums, couvertures comprises)", folderList, budgetMs = 400.0, detail = "$folders dossiers")

        // Grille du plus gros dossier (appareil photo) et d'un petit dossier.
        val bySize = expected.groupingBy { it.bucketId }.eachCount().entries.sortedByDescending { it.value }
        val camera = MediaQuery(bucketId = bySize.first().key)
        val cameraEnd = report.samples(repeat = 3) {
            TestPager(MediaRepository.PAGING_CONFIG, timelineSource(camera)).refresh(initialKey = bySize.first().value - 360)
        }
        report.check("dossier de ${bySize.first().value} médias, saut au bout", cameraEnd, budgetMs = 100.0)
        val small = MediaQuery(bucketId = bySize[bySize.size / 2].key)
        val smallFolder = report.samples(repeat = 3) { TestPager(MediaRepository.PAGING_CONFIG, timelineSource(small)).refresh() }
        report.check("petit dossier de ${bySize[bySize.size / 2].value} médias, chargement initial", smallFolder, budgetMs = 10.0)

        // Compteurs observés par la timeline et les écrans de tri.
        val count = report.samples(repeat = 3) { assertEquals(expected.size, repository.observeCount(MediaQuery.Timeline).first()) }
        report.check("compteur de la timeline", count, budgetMs = 15.0)
        val total = report.samples(repeat = 3) { assertEquals(expected.size, database.mediaDao().observeTotalCount().first()) }
        report.check("compteur total (avancement de l'analyse)", total, budgetMs = 15.0)
        val videos = report.samples(repeat = 3) { repository.observeCount(MediaQuery(filter = MediaFilter.VIDEOS)).first() }
        report.check("compteur du filtre Vidéos", videos, budgetMs = 5.0)

        // Recherche par étiquette : « chien » (races comprises) ; compte et première page.
        val dog = SearchQueryParser.parse("chien")
        val dogLabels = dog.terms.single().labels.toSet()
        val dogIds = library.labels.filter { it.label in dogLabels }.mapTo(HashSet()) { it.mediaId }
        val expectedDogs = expected.count { it.id in dogIds }
        val dogCount = report.samples(repeat = 3) { assertEquals(expectedDogs, repository.observeCount(dog).first()) }
        report.check("recherche « chien » : nombre de résultats", dogCount, budgetMs = SEARCH_COUNT_BUDGET_MS, detail = "$expectedDogs résultats")
        val dogPage = report.samples(repeat = 3) {
            val page = TestPager(MediaRepository.PAGING_CONFIG, repository.pagingSource(dog)).refresh()
            assertEquals(expected.filter { it.id in dogIds }.take(360).map { it.id }, (page as LoadResult.Page).data.map { it.id })
        }
        report.check("recherche « chien » : première page", dogPage, budgetMs = SEARCH_PAGE_BUDGET_MS)

        // Recherche par texte : nom de fichier et texte reconnu (« facture »).
        val invoice = SearchQueryParser.parse("facture")
        var invoices = 0
        val textCount = report.samples(repeat = 3) { invoices = repository.observeCount(invoice).first() }
        assertTrue(invoices > 0)
        report.check("recherche « facture » : nombre de résultats", textCount, budgetMs = SEARCH_COUNT_BUDGET_MS, detail = "$invoices résultats")
        val textPage = report.samples(repeat = 3) {
            TestPager(MediaRepository.PAGING_CONFIG, repository.pagingSource(invoice)).refresh()
        }
        report.check("recherche « facture » : première page", textPage, budgetMs = SEARCH_PAGE_BUDGET_MS)

        // Nom de fichier : « shot » (« Screenshot_… »), cherché dans le nom et le texte reconnu ;
        // résultat comparé à un comptage en Kotlin, sans résultat perdu.
        val shot = MediaQuery(terms = listOf(SearchTerm(text = "shot")))
        val textKeys = library.texts.associate { it.mediaId to it.textKey }
        val shots = expected.filter { it.displayName.contains("shot", ignoreCase = true) || textKeys[it.id]?.contains("shot") == true }
        val shotCount = report.samples(repeat = 3) { assertEquals(shots.size, repository.observeCount(shot).first()) }
        report.check("recherche « shot » (nom de fichier) : nombre de résultats", shotCount, budgetMs = SEARCH_COUNT_BUDGET_MS, detail = "${shots.size} résultats")
        val shotPage = report.samples(repeat = 3) {
            val page = TestPager(MediaRepository.PAGING_CONFIG, repository.pagingSource(shot)).refresh()
            assertEquals(shots.take(360).map { it.id }, (page as LoadResult.Page).data.map { it.id })
        }
        report.check("recherche « shot » (nom de fichier) : première page", shotPage, budgetMs = SEARCH_PAGE_BUDGET_MS)

        // Mois de toutes les années (« juillet ») : mois local de chaque média, comme les en-têtes.
        val zone = ZoneId.systemDefault()
        val july = SearchQueryParser.parse("juillet")
        assertEquals(7, july.month)
        val julyMedia = expected.filter { Instant.ofEpochMilli(it.dateTaken).atZone(zone).monthValue == 7 }
        val julyCount = report.samples(repeat = 3) { assertEquals(julyMedia.size, repository.observeCount(july).first()) }
        report.check("recherche « juillet » : nombre de résultats", julyCount, budgetMs = MONTH_COUNT_BUDGET_MS, detail = "${julyMedia.size} résultats")
        val julyPage = report.samples(repeat = 3) {
            val page = TestPager(MediaRepository.PAGING_CONFIG, repository.pagingSource(july)).refresh()
            assertEquals(julyMedia.take(360).map { it.id }, (page as LoadResult.Page).data.map { it.id })
        }
        report.check("recherche « juillet » : première page", julyPage, budgetMs = MONTH_PAGE_BUDGET_MS)

        // Critère d'une autre table seul (étiquette ouverte depuis l'onglet Recherche, personne) :
        // le compte part de ses médias, par la clé primaire.
        val dogOnly = MediaQuery(terms = listOf(SearchTerm(labels = listOf("Dog"))))
        val dogOnlyIds = library.labels.filter { it.label == "Dog" }.mapTo(HashSet()) { it.mediaId }
        val labelCount = report.samples(repeat = 3) { assertEquals(expected.count { it.id in dogOnlyIds }, repository.observeCount(dogOnly).first()) }
        report.check("étiquette « Dog » seule : nombre de résultats", labelCount, budgetMs = 110.0)
        val person = library.persons.first().id
        val personCount = report.samples(repeat = 3) { repository.observeCount(MediaQuery(personId = person)).first() }
        report.check("personne : nombre de photos", personCount, budgetMs = 60.0)

        // Onglet Recherche : choses (étiquettes) et lieux avec leur couverture.
        val visible = expected.mapTo(HashSet()) { it.id }
        val expectedThings = library.labels.filter { it.mediaId in visible }.groupingBy { it.label }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(60).map { it.key to it.value }
        val things = report.samples(repeat = 3) { database.analysisDao().labelStats(60) }
        report.check("onglet Recherche : étiquettes les plus présentes", things, budgetMs = THINGS_BUDGET_MS)
        val stats = database.analysisDao().labelStats(60)
        assertEquals(expectedThings, stats.map { it.label to it.count })
        // Couverture : une photo de l'étiquette, hors corbeille.
        val labeled = library.labels.mapTo(HashSet()) { it.mediaId to it.label }
        assertTrue(stats.all { (it.coverId to it.label) in labeled && it.coverId in visible })
        val places = report.samples(repeat = 3) { database.analysisDao().placeStats(40) }
        report.check("onglet Recherche : lieux", places, budgetMs = 200.0)
    }

    /** Grille complète (médias et en-têtes de [level]) produite par Paging à partir de ces données. */
    private suspend fun kotlinx.coroutines.flow.Flow<PagingData<app.focale.core.model.MediaItem>>.asSnapshotOf(level: GroupLevel): List<GridItem> =
        kotlinx.coroutines.flow.flow { collect { emit(it.toGridItems(level)) } }.asSnapshot()

    private companion object {
        const val JUMPS = 12

        // Budgets : environ cinq fois les médianes mesurées (docs/PERFORMANCE.md § 4.2). Un saut à
        // plus de 60 ms signale le retour du COUNT(*) de la requête triée (55 à 70 ms à lui seul).
        const val JUMP_BUDGET_MS = 60.0
        const val HISTOGRAM_BUDGET_MS = 30.0
        const val GRID_POSITION_BUDGET_MS = 70.0
        const val SEARCH_COUNT_BUDGET_MS = 150.0
        const val SEARCH_PAGE_BUDGET_MS = 500.0

        // Étiquettes lues dans leurs comptes : 1 à 2 ms, 90 ms à les recompter. Mois de toutes les
        // années borné en Kotlin : 10 et 18 ms, 76 et 91 ms avec strftime pour chaque média.
        const val THINGS_BUDGET_MS = 20.0
        const val MONTH_COUNT_BUDGET_MS = 50.0
        const val MONTH_PAGE_BUDGET_MS = 60.0

        const val DAY_MS = 86_400_000L
        const val OLDER_FIRST_ID = 10_000_000L
    }
}
