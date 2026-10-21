package app.focale.people

import androidx.compose.runtime.Immutable
import app.focale.core.database.FaceDao
import app.focale.core.database.MergeCandidateRow
import app.focale.core.di.DefaultDispatcher
import app.focale.data.MediaRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deux personnes peut-être identiques : il est proposé de fusionner [sourceId] dans [targetId]. */
@Immutable
data class MergeSuggestion(val targetId: Long, val sourceId: Long, val similarity: Float)

/**
 * « Est-ce la même personne ? » : paires de personnes dont les empreintes moyennes
 * (centroïdes, comme dans [FaceClusterer]) sont proches. Logique pure.
 */
object MergeSuggester {

    /** Personne candidate ; [centroid] : moyenne normalisée des empreintes de ses visages. */
    class Candidate(val id: Long, val name: String?, val photoCount: Int, val centroid: FloatArray)

    /**
     * Similarité minimale de deux centroïdes, mesurée sur LFW (`tools/faces/`) : au plus une
     * fausse suggestion pour 5 000 paires de personnes distinctes, en retrouvant au moins 99 %
     * des personnes coupées en deux (âge, lunettes, lumière). Moyenner les visages ne sépare pas
     * deux personnes, au contraire : les empreintes SFace partagent une composante commune que
     * le bruit de chaque photo masquait. À 0,38, 0,3 % (3 + 3 visages) à 0,6 % (7 + 7) des
     * paires d'inconnus étaient proposées ; à 0,55, 0,001 à 0,005 %, et environ 0,014 % pour
     * de très grandes personnes. Deux moitiés d'une même personne restent bien au-dessus :
     * 0,62 au 1er centile quand on la coupe là où ses visages diffèrent le plus.
     */
    const val THRESHOLD = 0.55f

    /**
     * Paires d'au moins [threshold], les plus probables d'abord, hors paires [refused] (plus
     * petit identifiant en premier) et hors paires de deux personnes nommées différemment, que
     * l'utilisateur distingue déjà. La cible garde son nom : la personne nommée, sinon celle
     * qui a le plus de photos.
     */
    fun suggest(candidates: List<Candidate>, refused: Set<Pair<Long, Long>>, threshold: Float = THRESHOLD): List<MergeSuggestion> {
        val names = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val suggestions = ArrayList<MergeSuggestion>()
        for (i in candidates.indices) {
            val a = candidates[i]
            for (j in i + 1 until candidates.size) {
                val b = candidates[j]
                val similarity = FaceEmbeddings.dot(a.centroid, b.centroid)
                if (similarity < threshold || (minOf(a.id, b.id) to maxOf(a.id, b.id)) in refused) continue
                if (a.name != null && b.name != null && names.compare(a.name, b.name) != 0) continue
                val (target, source) = if (isTarget(a, b)) a to b else b to a
                suggestions += MergeSuggestion(target.id, source.id, similarity)
            }
        }
        suggestions.sortWith(compareByDescending<MergeSuggestion> { it.similarity }.thenBy { it.targetId }.thenBy { it.sourceId })
        return suggestions
    }

    private fun isTarget(a: Candidate, b: Candidate): Boolean = when {
        (a.name == null) != (b.name == null) -> a.name != null
        a.photoCount != b.photoCount -> a.photoCount > b.photoCount
        else -> a.id < b.id
    }
}

/**
 * Suggestions de fusion tenues à jour depuis la base : recalculées quand les personnes ou les
 * refus changent, hors thread principal. Seules les personnes dont les visages ont changé
 * voient leur centroïde recalculé, depuis leurs empreintes relues par tranches ; les autres
 * restent en mémoire. 128 flottants par personne : inutile de quantifier sur 8 bits comme
 * les empreintes des photos (`EmbeddingIndex`).
 */
@Singleton
class MergeSuggestions @Inject constructor(
    private val dao: FaceDao,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    private class Centroid(val faceCount: Int, val faceSum: Long, val vector: FloatArray)

    private val mutex = Mutex()
    private val centroids = HashMap<Long, Centroid>()

    /** Suggestions des personnes visibles, les plus probables d'abord. */
    fun observe(): Flow<List<MergeSuggestion>> =
        combine(
            dao.observeMergeCandidates(PeopleRepository.MIN_PHOTOS).distinctUntilChanged(),
            dao.observeRefusals().distinctUntilChanged(),
        ) { rows, refusals -> rows to refusals }
            .mapLatest { (rows, refusals) ->
                MergeSuggester.suggest(candidates(rows), refusals.mapTo(HashSet()) { it.personLow to it.personHigh })
            }
            .distinctUntilChanged()
            .flowOn(dispatcher)

    /** Candidats de [rows] ; centroïde recalculé pour les personnes dont les visages ont changé. */
    private suspend fun candidates(rows: List<MergeCandidateRow>): List<MergeSuggester.Candidate> = mutex.withLock {
        centroids.keys.retainAll(rows.mapTo(HashSet()) { it.id })
        val stale = rows.filter { row ->
            centroids[row.id]?.let { it.faceCount == row.faceCount && it.faceSum == row.faceSum } != true
        }
        val sums = sums(stale.map { it.id })
        for (row in stale) {
            val sum = sums[row.id] ?: FloatArray(FaceEmbeddings.DIMENSION)
            centroids[row.id] = Centroid(row.faceCount, row.faceSum, FaceEmbeddings.normalize(sum))
        }
        rows.map { MergeSuggester.Candidate(it.id, it.name, it.photoCount, centroids.getValue(it.id).vector) }
    }

    /** Somme des empreintes des visages de chaque personne, lues par tranches de [PAGE_SIZE]. */
    private suspend fun sums(personIds: List<Long>): Map<Long, FloatArray> {
        val sums = HashMap<Long, FloatArray>()
        for (chunk in personIds.chunked(MediaRepository.MAX_SQL_ARGS)) {
            var after = 0L
            do {
                val page = dao.personEmbeddings(chunk, after, PAGE_SIZE)
                for (face in page) add(sums.getOrPut(face.personId) { FloatArray(FaceEmbeddings.DIMENSION) }, face.embedding)
                after = page.lastOrNull()?.id ?: after
            } while (page.size == PAGE_SIZE)
        }
        return sums
    }

    /** Ajoute une empreinte (format de `face.embedding`) à [sum], sans allocation. */
    private fun add(sum: FloatArray, embedding: ByteArray) {
        val values = ByteBuffer.wrap(embedding).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        if (values.remaining() != sum.size) return
        for (i in sum.indices) sum[i] += values.get(i)
    }

    private companion object {
        const val PAGE_SIZE = 1_000
    }
}
