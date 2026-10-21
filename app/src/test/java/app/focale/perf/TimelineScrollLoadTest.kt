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
import app.focale.data.AlbumRepository
import app.focale.data.MediaRepository
import app.focale.data.toGridItems
import app.focale.data.toModel
import app.focale.ml.SearchQueryParser
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
        repository = MediaRepository(database.mediaDao(), Dispatchers.IO)
    }

    @After
    fun tearDown() = database.close()

    private fun timelineSource(query: MediaQuery = MediaQuery.Timeline) = database.mediaDao().pagingSourceOfMedia(MediaQueryBuilder.select(query))

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
        report.check("chargement initial (360 médias + COUNT)", initial, budgetMs = 300.0)
        report.check("page suivante (120 médias), toute la liste", appends.toDoubleArray(), budgetMs = 15.0, detail = "${appends.size} pages")
        report.check("page suivante, 20 dernières pages", appends.takeLast(20).toDoubleArray(), budgetMs = 25.0)

        // Sauts de la barre de défilement rapide : Paging ouvre une nouvelle PagingSource à la
        // position visée (clé de LimitOffsetPagingSource : position - initialLoadSize / 2).
        val targets = (0 until JUMPS).map { ((it + 0.5) / JUMPS * expected.size).toInt() }
        val jumps = report.samples(repeat = JUMPS) { index ->
            val target = targets[if (index < 0) 0 else index]
            val key = (target - MediaRepository.PAGING_CONFIG.initialLoadSize / 2).coerceAtLeast(0)
            val page = TestPager(MediaRepository.PAGING_CONFIG, timelineSource()).refresh(initialKey = key) as LoadResult.Page
            assertEquals(expected[key].id, page.data.first().id)
            assertEquals(key, page.itemsBefore)
        }
        report.check("saut de la barre de défilement (nouvelle source, 360 médias + COUNT)", jumps, budgetMs = 350.0)
        val deepest = report.samples(repeat = 3) {
            val key = expected.size - MediaRepository.PAGING_CONFIG.initialLoadSize
            TestPager(MediaRepository.PAGING_CONFIG, timelineSource()).refresh(initialKey = key)
        }
        report.check("saut tout au bout de la liste", deepest, budgetMs = 300.0)
        // Dans un saut, le nombre total de LimitOffsetPagingSource (sous-requête triée, lignes
        // entières) l'emporte sur la page ; le compteur de la timeline passe par l'index seul.
        val select = MediaQueryBuilder.select(MediaQuery.Timeline)
        val roomCount = report.samples(repeat = 3) {
            withContext(Dispatchers.IO) { database.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM ( ${select.sql} )")).use { it.moveToFirst() } }
        }
        report.check("dont COUNT(*) de LimitOffsetPagingSource", roomCount, budgetMs = 300.0)
        val indexCount = report.samples(repeat = 3) { database.mediaDao().count(MediaQueryBuilder.count(MediaQuery.Timeline)) }
        report.check("COUNT(*) par l'index (compteur de la timeline)", indexCount, budgetMs = 15.0)

        // Filtre « Photos » (95 % des médias) : même parcours, tout au bout.
        val photos = MediaQuery(filter = MediaFilter.PHOTOS)
        val photosEnd = report.samples(repeat = 3) {
            TestPager(MediaRepository.PAGING_CONFIG, timelineSource(photos)).refresh(initialKey = 45_000)
        }
        report.check("saut tout au bout, filtre Photos", photosEnd, budgetMs = 300.0)
    }

    @Test
    fun `en-têtes de date, bulle de la barre de défilement et retour de la visionneuse`() = runBlocking<Unit> {
        val expected = library.timelineOrder
        val items = expected.map(MediaEntity::toModel)
        val models = report.samples(repeat = 3) { expected.map(MediaEntity::toModel) }
        report.check("conversion en MediaItem (50 000 médias)", models, budgetMs = 300.0)

        // Séparateurs insérés par Paging (insertSeparators), ici sur toute la liste d'un coup.
        for (level in GroupLevel.entries) {
            var headers = 0
            val separators = report.samples(repeat = 3) {
                val grid = flowOf(PagingData.from(items)).asSnapshotOf(level)
                headers = grid.count { it is GridItem.Header }
                assertEquals(items.size + headers, grid.size)
            }
            report.check("en-têtes ${level.name} sur 50 000 médias", separators, budgetMs = 150.0, detail = "$headers en-têtes")
        }

        // Histogramme mensuel de la bulle de date : exact sans charger les pages.
        var months = 0
        val histogram = report.samples(repeat = 3) {
            val buckets = repository.observeHistogram(MediaQuery.Timeline, GroupLevel.MONTH).first()
            assertEquals(expected.size, buckets.sumOf { it.count })
            months = buckets.size
        }
        report.check("histogramme mensuel (bulle de date)", histogram, budgetMs = 500.0, detail = "$months mois")

        // Retour de la visionneuse : position du média dans la grille (COUNT + en-têtes avant lui).
        val sampleIndexes = (0 until 10).map { (it * 4_957L % expected.size).toInt() }
        val positions = report.samples(repeat = sampleIndexes.size) { index ->
            val position = sampleIndexes[if (index < 0) 0 else index]
            assertEquals(position, repository.positionOf(MediaQuery.Timeline, expected[position].id))
        }
        report.check("position d'un média (visionneuse)", positions, budgetMs = 25.0)
        val gridPositions = report.samples(repeat = sampleIndexes.size) { index ->
            val position = sampleIndexes[if (index < 0) 0 else index]
            val grid = repository.gridPositionOf(MediaQuery.Timeline, expected[position].id, GroupLevel.DAY)!!
            // En-têtes : au moins celui de son jour, au plus un par média qui le précède.
            assertTrue(grid in position + 1..2 * position + 1)
        }
        report.check("position dans la grille avec en-têtes de jour (retour de la visionneuse)", gridPositions, budgetMs = 700.0)
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
        report.check("dossier de ${bySize.first().value} médias, saut au bout", cameraEnd, budgetMs = 250.0)
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
        report.check("recherche « chien » : nombre de résultats", dogCount, budgetMs = 500.0, detail = "$expectedDogs résultats")
        val dogPage = report.samples(repeat = 3) {
            val page = TestPager(MediaRepository.PAGING_CONFIG, database.mediaDao().pagingSourceWithAnalysis(MediaQueryBuilder.select(dog))).refresh()
            assertEquals(expected.filter { it.id in dogIds }.take(360).map { it.id }, (page as LoadResult.Page).data.map { it.id })
        }
        report.check("recherche « chien » : première page", dogPage, budgetMs = 800.0)

        // Recherche par texte : nom de fichier et texte reconnu (« facture »).
        val invoice = SearchQueryParser.parse("facture")
        var invoices = 0
        val textCount = report.samples(repeat = 3) { invoices = repository.observeCount(invoice).first() }
        assertTrue(invoices > 0)
        report.check("recherche « facture » : nombre de résultats", textCount, budgetMs = 550.0, detail = "$invoices résultats")
        val textPage = report.samples(repeat = 3) {
            TestPager(MediaRepository.PAGING_CONFIG, database.mediaDao().pagingSourceWithAnalysis(MediaQueryBuilder.select(invoice))).refresh()
        }
        report.check("recherche « facture » : première page", textPage, budgetMs = 750.0)

        // Onglet Recherche : choses (étiquettes) et lieux avec leur couverture.
        val things = report.samples(repeat = 3) { database.analysisDao().observeLabelStats(60).first() }
        report.check("onglet Recherche : étiquettes les plus présentes", things, budgetMs = 1_800.0)
        val places = report.samples(repeat = 3) { database.analysisDao().observePlaceStats(40).first() }
        report.check("onglet Recherche : lieux", places, budgetMs = 200.0)
    }

    /** Grille complète (médias et en-têtes de [level]) produite par Paging à partir de ces données. */
    private suspend fun kotlinx.coroutines.flow.Flow<PagingData<app.focale.core.model.MediaItem>>.asSnapshotOf(level: GroupLevel): List<GridItem> =
        kotlinx.coroutines.flow.flow { collect { emit(it.toGridItems(level)) } }.asSnapshot()

    private companion object {
        const val JUMPS = 12
    }
}
