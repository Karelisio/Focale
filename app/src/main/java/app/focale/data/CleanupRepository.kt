package app.focale.data

import androidx.compose.runtime.Immutable
import app.focale.core.database.AnalysisDao
import app.focale.core.database.MediaEntity
import app.focale.core.di.DefaultDispatcher
import app.focale.core.model.MediaItem
import app.focale.ml.DuplicateFinder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Groupe de photos similaires ; [keepId] est la meilleure (résolution puis taille). */
@Immutable
data class DuplicateGroup(val items: List<MediaItem>, val keepId: Long)

/** Nettoyage : doublons et photos floues issus de l'analyse sur l'appareil. */
@Singleton
class CleanupRepository @Inject constructor(
    private val analysisDao: AnalysisDao,
    private val mediaRepository: MediaRepository,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) {

    suspend fun findDuplicates(maxDistance: Int = DEFAULT_DISTANCE): List<DuplicateGroup> = withContext(dispatcher) {
        val rows = analysisDao.hashes()
        val groups = DuplicateFinder.group(rows.map { it.id to it.phash }, maxDistance)
        val items = mediaRepository.getByIds(groups.flatten()).associateBy { it.id }
        groups.mapNotNull { ids ->
            val members = ids.mapNotNull(items::get).sortedByDescending { it.dateTaken }
            if (members.size < 2) return@mapNotNull null
            val best = members.maxWith(compareBy<MediaItem>({ it.width.toLong() * it.height }, { it.size }))
            DuplicateGroup(members, best.id)
        }.sortedByDescending { group -> group.items.maxOf { it.dateTaken } }
    }

    fun observeBlurry(threshold: Float): Flow<List<MediaItem>> =
        analysisDao.observeBlurry(threshold).map { rows -> rows.map(MediaEntity::toModel) }

    companion object {
        const val DEFAULT_DISTANCE = 6
        /** Seuil par défaut de la variance du laplacien (image de 384 px). */
        const val DEFAULT_BLUR_THRESHOLD = 40f
    }
}
