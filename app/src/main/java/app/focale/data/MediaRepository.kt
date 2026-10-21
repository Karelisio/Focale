package app.focale.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import app.focale.core.database.MediaDao
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.di.IoDispatcher
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.model.DateBucket
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Accès en lecture à l'index des médias. */
@Singleton
class MediaRepository @Inject constructor(
    private val dao: MediaDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Liste paginée des médias de [query].
     * @param initialIndex position de départ (visionneuse ouverte sur un média précis).
     */
    fun paged(query: MediaQuery, initialIndex: Int? = null): Flow<PagingData<MediaItem>> =
        Pager(
            config = PAGING_CONFIG,
            initialKey = initialIndex,
            pagingSourceFactory = {
                val sql = MediaQueryBuilder.select(query)
                when (query.observed) {
                    Observed.MEDIA -> dao.pagingSourceOfMedia(sql)
                    Observed.LIBRARY -> dao.pagingSource(sql)
                    Observed.ANALYSIS -> dao.pagingSourceWithAnalysis(sql)
                }
            },
        ).flow.map { data -> data.map(MediaEntity::toModel) }

    fun observeCount(query: MediaQuery): Flow<Int> =
        MediaQueryBuilder.count(query)
            .let {
                when (query.observed) {
                    Observed.MEDIA -> dao.observeCountOfMedia(it)
                    Observed.LIBRARY -> dao.observeCount(it)
                    Observed.ANALYSIS -> dao.observeCountWithAnalysis(it)
                }
            }
            .distinctUntilChanged()

    fun observeList(query: MediaQuery): Flow<List<MediaItem>> =
        MediaQueryBuilder.select(query)
            .let {
                when (query.observed) {
                    Observed.MEDIA -> dao.observeListOfMedia(it)
                    Observed.LIBRARY -> dao.observeList(it)
                    Observed.ANALYSIS -> dao.observeListWithAnalysis(it)
                }
            }
            .map { rows -> rows.map(MediaEntity::toModel) }

    suspend fun list(query: MediaQuery, limit: Int? = null): List<MediaItem> = withContext(io) {
        dao.list(MediaQueryBuilder.select(query, limit)).map(MediaEntity::toModel)
    }

    /** Premier média de [query] (couverture), observé. */
    fun observeFirst(query: MediaQuery): Flow<MediaItem?> =
        MediaQueryBuilder.select(query, limit = 1)
            .let { if (query.observed == Observed.MEDIA) dao.observeListOfMedia(it) else dao.observeList(it) }
            .map { it.firstOrNull()?.toModel() }

    suspend fun ids(query: MediaQuery): List<Long> = withContext(io) {
        dao.ids(MediaQueryBuilder.ids(query))
    }

    fun observeHistogram(query: MediaQuery, level: GroupLevel): Flow<List<DateBucket>> =
        MediaQueryBuilder.histogram(query, level)
            .let {
                when (query.observed) {
                    Observed.MEDIA -> dao.observeHistogramOfMedia(it)
                    Observed.LIBRARY -> dao.observeHistogram(it)
                    Observed.ANALYSIS -> dao.observeHistogramWithAnalysis(it)
                }
            }
            .map { rows -> rows.map { DateBucket(it.periodStart, it.count) } }
            .distinctUntilChanged()

    fun observe(id: Long): Flow<MediaItem?> = dao.observe(id).map { it?.toModel() }

    suspend fun get(id: Long): MediaItem? = withContext(io) { dao.get(id)?.toModel() }

    suspend fun getByIds(ids: Collection<Long>): List<MediaItem> = withContext(io) {
        ids.chunked(MAX_SQL_ARGS).flatMap { dao.getByIds(it) }.map(MediaEntity::toModel)
    }

    /** Index d'un média dans la liste de [query], ou null s'il n'en fait pas partie. */
    suspend fun positionOf(query: MediaQuery, mediaId: Long): Int? = withContext(io) {
        val anchor = dao.get(mediaId) ?: return@withContext null
        dao.count(MediaQueryBuilder.position(query, anchor))
    }

    /**
     * Index d'un média dans une grille avec en-têtes de période : position du média
     * plus le nombre d'en-têtes qui le précèdent (y compris celui de sa période).
     */
    suspend fun gridPositionOf(query: MediaQuery, mediaId: Long, level: GroupLevel?): Int? = withContext(io) {
        val anchor = dao.get(mediaId) ?: return@withContext null
        val position = dao.count(MediaQueryBuilder.position(query, anchor))
        if (level == null || !query.sort.isChronological) return@withContext position
        val headersBefore = dao.count(MediaQueryBuilder.periodsBefore(query, anchor, level))
        position + headersBefore + 1
    }

    companion object {
        /** Taille de page adaptée aux grilles denses (jusqu'à 7 colonnes × 20 lignes). */
        val PAGING_CONFIG = PagingConfig(
            pageSize = 120,
            prefetchDistance = 240,
            initialLoadSize = 360,
            enablePlaceholders = true,
            maxSize = 2400,
            jumpThreshold = 720,
        )
        const val MAX_SQL_ARGS = 500
    }
}

fun MediaEntity.toModel(): MediaItem {
    val isVideo = mediaType == MediaQueryBuilder.MEDIA_TYPE_VIDEO
    return MediaItem(
        id = id,
        uri = MediaStoreSource.contentUri(id, isVideo),
        type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
        mimeType = mimeType,
        displayName = displayName,
        relativePath = relativePath,
        bucketId = bucketId,
        bucketName = bucketName,
        dateTaken = dateTaken,
        dateModified = dateModified,
        size = size,
        width = width,
        height = height,
        orientation = orientation,
        durationMs = duration,
        isGif = isGif,
        isScreenshot = isScreenshot,
    )
}

/** Tables dont les changements relancent une requête observée (voir [MediaQuery.observed]). */
private enum class Observed {
    /** `media` seule : timeline, dossiers, filtres de type ou de date. */
    MEDIA,

    /** Avec les favoris, les albums et l'analyse principale (lieux, étiquettes). */
    LIBRARY,

    /** Avec aussi les étapes d'analyse complémentaires (texte, visages, empreintes). */
    ANALYSIS,
}

/**
 * Tables à observer pour une requête. Recherche et personne dépendent des étapes d'analyse
 * complémentaires, dont les résultats s'affinent pendant l'analyse ; une requête qui ne lit que
 * `media` ne dépend ni des favoris ni de l'analyse, réécrite à chaque lot.
 */
private val MediaQuery.observed: Observed
    get() = when {
        terms.isNotEmpty() || personId != null -> Observed.ANALYSIS
        MediaQueryBuilder.readsOnlyMedia(this) -> Observed.MEDIA
        else -> Observed.LIBRARY
    }
