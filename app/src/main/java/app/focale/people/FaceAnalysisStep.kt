package app.focale.people

import android.util.Log
import app.focale.core.database.FaceDao
import app.focale.core.database.FaceEntity
import app.focale.core.database.FaceScanEntity
import app.focale.core.database.StepCounts
import app.focale.core.model.MediaItem
import app.focale.data.toModel
import app.focale.ml.AnalysisKind
import app.focale.ml.AnalysisStep
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Compteur des modifications faites par l'utilisateur sur les personnes (fusion, retrait,
 * renommage…) : l'étape d'analyse recharge son état de regroupement quand il change.
 */
@Singleton
class PeopleEditions @Inject constructor() {
    private val counter = AtomicLong()
    val current: Long get() = counter.get()
    fun bump() {
        counter.incrementAndGet()
    }
}

/**
 * Étape « visages » de l'analyse : détecte et décrit les visages de chaque photo, puis
 * les regroupe en personnes après chaque lot ([FaceClusterer]).
 *
 * Une photo analysée à nouveau (modifiée, ou nouvelle version de l'étape) garde les
 * visages retrouvés au même endroit : leur personne, leur rattachement manuel et la
 * vignette qui les désigne sont conservés.
 */
@Singleton
class FaceAnalysisStep @Inject constructor(
    private val dao: FaceDao,
    private val analyzer: FaceAnalyzer,
    private val editions: PeopleEditions,
) : AnalysisStep {

    override val name: String = "visages"
    override val order: Int = 20

    override val kind: AnalysisKind = AnalysisKind.FACES

    override fun observeProgress(): Flow<StepCounts> = dao.observeProgress(VERSION)

    private var clusterer: FaceClusterer? = null
    private var loadedGeneration = -1L
    private var stale = true

    override suspend fun pending(limit: Int): List<MediaItem> = dao.pending(VERSION, limit).map { it.toModel() }

    override suspend fun process(items: List<MediaItem>) {
        // Modèles indisponibles : échec global, aucune photo n'est marquée comme vue.
        analyzer.prepare()
        val detections = items.map { item ->
            item to try {
                analyzer.analyze(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Visages illisibles pour ${item.id}", error)
                emptyList()
            }
        }
        val fresh = save(detections)
        cluster(fresh)
    }

    /** Enregistre les visages du lot ; renvoie les nouveaux, à regrouper. */
    private suspend fun save(detections: List<Pair<MediaItem, List<DetectedFace>>>): List<ClusterFace> {
        val previous = dao.facesOfMedia(detections.map { it.first.id }).groupBy { it.mediaId }
        val scans = ArrayList<FaceScanEntity>()
        val deleted = ArrayList<Long>()
        val updated = ArrayList<FaceEntity>()
        val inserted = ArrayList<FaceEntity>()
        val insertedEmbeddings = ArrayList<FloatArray>()
        for ((item, faces) in detections) {
            scans += FaceScanEntity(item.id, item.dateModified, VERSION, faces.size)
            val old = previous[item.id].orEmpty()
            val matches = FaceGeometry.matchPrevious(old.map { it.box() }, faces.map { it.box })
            faces.forEachIndexed { index, face ->
                val entity = FaceEntity(
                    mediaId = item.id,
                    boxLeft = face.box.left,
                    boxTop = face.box.top,
                    boxRight = face.box.right,
                    boxBottom = face.box.bottom,
                    embedding = FaceEmbeddings.encode(face.embedding),
                    quality = face.quality,
                )
                val match = matches[index]
                if (match >= 0) {
                    updated += entity.copy(id = old[match].id)
                } else {
                    inserted += entity
                    insertedEmbeddings += face.embedding
                }
            }
            val kept = matches.filter { it >= 0 }.toSet()
            old.forEachIndexed { index, face -> if (index !in kept) deleted += face.id }
            // Visages connus modifiés ou disparus : l'état en mémoire est à recharger.
            if (old.isNotEmpty()) stale = true
        }
        val ids = dao.saveDetections(scans, deleted, updated, inserted)
        return ids.mapIndexed { index, id -> ClusterFace(id, inserted[index].mediaId, insertedEmbeddings[index]) }
    }

    private suspend fun cluster(fresh: List<ClusterFace>) {
        val outcomes = ArrayList<FaceClusterer.Outcome>()
        var state = clusterer
        if (state == null || stale || loadedGeneration != editions.current) {
            loadedGeneration = editions.current
            state = load(exclude = fresh.mapTo(HashSet()) { it.id })
            clusterer = state
            stale = false
            // Après un rechargement, les visages en attente retentent leur chance.
            outcomes += state.sweep()
        }
        if (fresh.isNotEmpty()) outcomes += state.cluster(fresh)
        val assignments = outcomes.flatMap { it.assignments.entries }.associate { it.key to it.value }
        if (assignments.isEmpty()) return
        val created = outcomes.flatMap { it.created }
        stale = true
        val (ids, refused) = dao.saveClustering(created, assignments, System.currentTimeMillis())
        ids.forEach { (temporary, id) -> state.rename(temporary, id) }
        // Rattachements refusés (modifiés par l'utilisateur entre-temps) : état à recharger.
        stale = refused > 0
    }

    private suspend fun load(exclude: Set<Long>): FaceClusterer {
        val state = FaceClusterer()
        for (row in dao.clusterRows()) {
            if (row.id in exclude) continue
            val face = ClusterFace(row.id, row.mediaId, FaceEmbeddings.decode(row.embedding))
            when {
                row.personId != null -> state.addAssigned(row.personId, face)
                !row.manual -> state.addPending(face)
            }
        }
        return state
    }

    override fun release() {
        analyzer.close()
        clusterer = null
    }

    private fun FaceEntity.box() = FaceBox(boxLeft, boxTop, boxRight, boxBottom)

    companion object {
        /** Version de l'étape : l'incrémenter relance la détection sur toute la bibliothèque. */
        const val VERSION = 1
        private const val TAG = "FaceAnalysisStep"
    }
}
