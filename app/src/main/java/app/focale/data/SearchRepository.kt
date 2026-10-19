package app.focale.data

import androidx.compose.runtime.Immutable
import app.focale.core.database.AnalysisDao
import app.focale.core.database.SearchHistoryDao
import app.focale.core.database.SearchHistoryEntity
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.ml.SearchQueryParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** « Chose » reconnue dans la bibliothèque (étiquette ML Kit) avec une image représentative. */
@Immutable
data class ThingSuggestion(val label: String, val count: Int, val cover: MediaItem?)

/** Lieu visité, avec la photo la plus récente prise sur place. */
@Immutable
data class PlaceSuggestion(val name: String, val countryCode: String, val count: Int, val cover: MediaItem?)

/** Recherche : analyse des requêtes, catégories à explorer et historique local. */
@Singleton
class SearchRepository @Inject constructor(
    private val analysisDao: AnalysisDao,
    private val historyDao: SearchHistoryDao,
    private val mediaRepository: MediaRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun parse(text: String): MediaQuery = SearchQueryParser.parse(text)

    fun observeThings(limit: Int = 60): Flow<List<ThingSuggestion>> =
        analysisDao.observeLabelStats(limit).mapLatest { stats ->
            val covers = mediaRepository.getByIds(stats.map { it.coverId }).associateBy { it.id }
            stats.map { ThingSuggestion(it.label, it.count, covers[it.coverId]) }
        }.flowOn(io)

    fun observePlaces(limit: Int = 40): Flow<List<PlaceSuggestion>> =
        analysisDao.observePlaceStats(limit).mapLatest { stats ->
            val covers = mediaRepository.getByIds(stats.map { it.coverId }).associateBy { it.id }
            stats.map { PlaceSuggestion(it.place, it.country, it.count, covers[it.coverId]) }
        }.flowOn(io)

    fun observeRecent(limit: Int = 8): Flow<List<String>> =
        historyDao.observeRecent(limit).map { rows -> rows.map { it.query } }

    suspend fun remember(text: String) = withContext(io) {
        val clean = text.trim()
        if (clean.isNotEmpty()) historyDao.upsert(SearchHistoryEntity(clean, System.currentTimeMillis()))
    }

    suspend fun forget(text: String) = withContext(io) { historyDao.delete(text) }

    suspend fun clearHistory() = withContext(io) { historyDao.clear() }
}
