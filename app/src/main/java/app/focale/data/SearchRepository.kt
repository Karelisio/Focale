package app.focale.data

import android.util.Log
import androidx.compose.runtime.Immutable
import app.focale.core.database.AnalysisDao
import app.focale.core.database.EmbeddingDao
import app.focale.core.database.SearchHistoryDao
import app.focale.core.database.SearchHistoryEntity
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.ml.CountryResolver
import app.focale.ml.SearchQueryParser
import app.focale.ml.semantic.QueryTranslator
import app.focale.ml.semantic.SemanticSearch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
    private val embeddingDao: EmbeddingDao,
    private val mediaRepository: MediaRepository,
    private val semanticSearch: SemanticSearch,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** @param people personnes nommées (identifiant → nom), cherchées par leur nom. */
    fun parse(text: String, people: Map<Long, String> = emptyMap()): MediaQuery =
        SearchQueryParser.parse(text, people = people)

    /**
     * [parse] complétée par la recherche par le sens : les photos les plus proches du texte
     * libre (« chien sur la plage ») s'ajoutent aux correspondances exactes de chaque terme.
     *
     * Un mot qui désigne un pays ou un lieu de la bibliothèque, sans être un mot du
     * dictionnaire, reste une contrainte : « plage biarritz » cherche des plages à Biarritz.
     */
    suspend fun resolve(text: String, people: Map<Long, String> = emptyMap()): MediaQuery {
        val query = parse(text, people)
        if (query.terms.isEmpty()) return query
        return try {
            // Hors du thread principal : le premier appel construit le dictionnaire de traduction.
            val (constraints, prompt) = withContext(io) {
                val words = SearchQueryParser.freeWords(text, people = people)
                val constraints = words.filterTo(mutableSetOf()) { isPlaceConstraint(it) }
                constraints to QueryTranslator.prompt(words.filterNot { it in constraints })
            }
            if (prompt == null) return query
            val similar = semanticSearch.search(prompt)
            if (similar.isEmpty()) {
                query
            } else {
                query.copy(terms = query.terms.map { term -> if (term.text in constraints) term else term.copy(similarIds = similar) })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Modèle indisponible… : les correspondances exactes restent.
            Log.w(TAG, "Recherche par le sens impossible", error)
            query
        }
    }

    /** Charge le modèle de texte et les empreintes avant la première recherche. */
    suspend fun warmUp() {
        try {
            semanticSearch.warmUp()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Préchargement de la recherche par le sens impossible", error)
        }
    }

    private suspend fun isPlaceConstraint(word: String): Boolean {
        if (word.length < 3 || QueryTranslator.isKnownWord(word)) return false
        return CountryResolver.resolve(word).isNotEmpty() || embeddingDao.isPlaceWord(word)
    }

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

    /** Recherches récentes : texte seul, ou clé d'une recherche avec pastilles ([SearchFilters.historyKey]). */
    fun observeRecent(limit: Int = 8): Flow<List<String>> =
        historyDao.observeRecent(limit).map { rows -> rows.map { it.query } }

    /**
     * Mémorise [text] en tête de l'historique. [replacing] : entrée de la même recherche, retirée
     * pour qu'une recherche affinée pastille après pastille n'y laisse qu'une entrée.
     */
    suspend fun remember(text: String, replacing: String? = null) = withContext(io) {
        val clean = text.trim()
        if (clean.isEmpty()) return@withContext
        if (replacing != null && replacing != clean) historyDao.delete(replacing)
        historyDao.upsert(SearchHistoryEntity(clean, System.currentTimeMillis()))
    }

    suspend fun forget(text: String) = withContext(io) { historyDao.delete(text) }

    suspend fun clearHistory() = withContext(io) { historyDao.clear() }

    private companion object {
        const val TAG = "SearchRepository"
    }
}
