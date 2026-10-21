package app.focale.perf

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.data.toModel
import app.focale.ml.AnalysisStep
import app.focale.ml.ImageAnalyzer
import app.focale.ml.semantic.ClipModels
import app.focale.ml.semantic.EmbeddingStep
import app.focale.ml.text.FakeTextReader
import app.focale.ml.text.TextRecognitionStep
import app.focale.people.FaceAnalysisStep
import app.focale.people.FaceAnalyzer
import app.focale.people.PeopleEditions
import app.focale.work.AnalysisQueue
import app.focale.work.AnalysisWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Charge : coût base de données de l'analyse de 50 000 médias. Sélection de chaque lot
 * (`AnalysisQueue`, puis `pending` des étapes complémentaires) et compteurs d'avancement
 * observés, au fil de l'avancement : le coût d'un lot ne doit pas croître avec lui (coût total
 * quadratique). Les calculs par photo et l'inférence sont mesurés par `AnalysisComputeLoadTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisQueueLoadTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val library = SyntheticLibrary.Default
    private val report = LoadReport("analyse")
    private lateinit var database: FocaleDatabase

    @Before
    fun setUp() = runBlocking<Unit> {
        database = openLoadTestDatabase(context)
        database.insertMedia(library)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `analyse principale de toute la bibliothèque, sélection des lots et enregistrement`() = runBlocking<Unit> {
        val dao = database.analysisDao()
        val results = library.analyses.associateBy { it.mediaId }
        val labels = library.labels.groupBy { it.mediaId }
        val expected = library.timelineOrder
        val queue = AnalysisQueue(dao)
        val selection = ArrayList<Double>()
        val saving = ArrayList<Double>()
        val processed = ArrayList<Long>(expected.size)
        val checkpoints = listOf(0.0, 0.25, 0.5, 0.75, 0.95).map { (it * expected.size).toInt() }.toMutableList()
        while (true) {
            if (checkpoints.isNotEmpty() && processed.size >= checkpoints.first()) {
                val percent = 100 * checkpoints.removeAt(0) / expected.size
                // Sans curseur (premier lot d'un passage, contrôle de fin) et compteur observé.
                val restart = report.samples(repeat = 3) { dao.pending(ImageAnalyzer.VERSION, AnalysisWorker.BATCH_SIZE) }
                report.check("premier lot d'un passage à $percent %", restart, budgetMs = RESTART_BUDGET_MS)
                val counter = report.samples(repeat = 3) {
                    assertEquals(expected.size - processed.size, dao.observePendingCount(ImageAnalyzer.VERSION).first())
                }
                report.check("compteur observé à $percent %", counter, budgetMs = COUNTER_BUDGET_MS)
            }
            var batch: List<MediaEntity> = emptyList()
            selection += report.once { batch = queue.next(AnalysisWorker.BATCH_SIZE) }
            if (batch.isEmpty()) break
            batch.mapTo(processed) { it.id }
            val analyses = batch.map { results.getValue(it.id) }
            saving += report.once { dao.save(analyses, analyses.flatMap { labels[it.mediaId].orEmpty() }) }
        }
        // Les plus récents d'abord, chacun une fois : l'ordre de la timeline.
        assertEquals(expected.map { it.id }, processed)
        assertEquals(0, dao.observePendingCount(ImageAnalyzer.VERSION).first())

        val batches = selection.size - 1
        val tenth = batches / 10
        val firstTenth = report.check("lot suivant, premier dixième", selection.take(tenth).toDoubleArray(), budgetMs = NEXT_BUDGET_MS)
        val lastTenth = report.check("lot suivant, dernier dixième", selection.subList(batches - tenth, batches).toDoubleArray(), budgetMs = NEXT_BUDGET_MS)
        // Pas de croissance avec l'avancement (marge pour le bruit de mesure).
        assertTrue("sélection : $firstTenth ms au début, $lastTenth ms à la fin", lastTenth <= 3 * firstTenth + 1.0)
        report.note("sélection des $batches lots, total", selection.sum())
        report.check("enregistrement d'un lot (analyse et étiquettes)", saving.toDoubleArray(), budgetMs = SAVE_BUDGET_MS)
        report.note("enregistrement des $batches lots, total", saving.sum())
    }

    @Test
    fun `étapes complémentaires, coût d'un lot et de l'avancement au fil de l'analyse`() = runBlocking<Unit> {
        val db = database.openHelper.writableDatabase
        val photos = library.timelineOrder.filter { it.mediaType == MediaQueryBuilder.MEDIA_TYPE_IMAGE }
        val io = Dispatchers.IO
        val steps = listOf(
            // Captures d'écran d'abord, puis les autres photos (TextDao.pending).
            StepUnderTest(
                step = TextRecognitionStep(database.textDao(), FakeTextReader(emptyMap())),
                order = photos.sortedWith(compareByDescending<MediaEntity> { it.isScreenshot }.thenByDescending { it.dateTaken }.thenByDescending { it.id }),
                mark = { ids -> "INSERT INTO media_text (media_id, analyzed_modified, version, text, text_key) SELECT id, date_modified, ${TextRecognitionStep.VERSION}, '', '' FROM media WHERE id IN ($ids)" },
                progress = { database.textDao().observeProgress(TextRecognitionStep.VERSION).first().done },
            ),
            // Ni captures ni GIF (FaceDao.pending).
            StepUnderTest(
                step = FaceAnalysisStep(database.faceDao(), FaceAnalyzer(context, context.contentResolver, io), PeopleEditions()),
                order = photos.filter { !it.isScreenshot && !it.isGif },
                mark = { ids -> "INSERT INTO face_scan (media_id, analyzed_modified, version, face_count) SELECT id, date_modified, ${FaceAnalysisStep.VERSION}, 0 FROM media WHERE id IN ($ids)" },
                progress = { database.faceDao().observeProgress(FaceAnalysisStep.VERSION).first().done },
            ),
            StepUnderTest(
                step = EmbeddingStep(context, context.contentResolver, database.embeddingDao(), io, Dispatchers.Default),
                order = photos,
                mark = { ids -> "INSERT INTO media_embedding (media_id, analyzed_modified, version, vector) SELECT id, date_modified, ${ClipModels.VERSION}, NULL FROM media WHERE id IN ($ids)" },
                progress = { database.embeddingDao().observeProgress(ClipModels.VERSION).first().done },
            ),
        )
        for ((step, order, mark, progress) in steps) {
            var done = 0
            val next = ArrayList<Double>()
            for (fraction in listOf(0.0, 0.5, 0.95)) {
                // Avancement simulé : les premiers médias de la file de l'étape marqués traités, en SQL.
                val target = (fraction * order.size).toInt()
                order.subList(done, target).chunked(500).forEach { chunk -> db.execSQL(mark(chunk.joinToString(",") { it.id.toString() })) }
                done = target
                val percent = (fraction * 100).toInt()
                // Lot suivant d'un passage, après le dernier média traité (AnalysisWorker).
                if (done > 0) {
                    val after = order[done - 1].toModel()
                    val samples = report.samples(repeat = 5) {
                        assertEquals(order.subList(done, done + AnalysisWorker.STEP_BATCH_SIZE).map { it.id }, step.pendingAfter(after, AnalysisWorker.STEP_BATCH_SIZE).map { it.id })
                    }
                    next += report.check("${step.name} : lot suivant à $percent %", samples, budgetMs = NEXT_BUDGET_MS)
                }
                // Premier lot d'un passage (sans curseur) et avancement observé.
                val restart = report.samples(repeat = 2) { assertEquals(order[done].id, step.pending(AnalysisWorker.STEP_BATCH_SIZE).first().id) }
                report.check("${step.name} : premier lot d'un passage à $percent %", restart, budgetMs = RESTART_BUDGET_MS)
                val counter = report.samples(repeat = 2) { assertEquals(done, progress()) }
                report.check("${step.name} : avancement observé à $percent %", counter, budgetMs = COUNTER_BUDGET_MS)
            }
            // Pas de croissance avec l'avancement (marge pour le bruit de mesure).
            assertTrue("${step.name} : lot suivant $next", next.last() <= 3 * next.first() + 1.0)
            val batches = order.size / AnalysisWorker.STEP_BATCH_SIZE
            report.note("${step.name} : sélection des $batches lots, estimation", next.average() * batches)
        }
    }

    /** Étape mesurée : file de ses médias dans l'ordre de traitement, et SQL qui les marque traités. */
    private data class StepUnderTest(
        val step: AnalysisStep,
        val order: List<MediaEntity>,
        val mark: (String) -> String,
        val progress: suspend () -> Int,
    )

    private companion object {
        // Budgets : environ cinq fois les médianes mesurées (voir docs/PERFORMANCE.md).
        const val NEXT_BUDGET_MS = 10.0
        const val RESTART_BUDGET_MS = 1_000.0
        const val COUNTER_BUDGET_MS = 1_000.0
        const val SAVE_BUDGET_MS = 15.0
    }
}
