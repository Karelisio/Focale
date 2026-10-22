package app.focale.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.map
import androidx.room.InvalidationTracker
import app.focale.core.database.MediaDao
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaPagingSource
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.di.IoDispatcher
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.model.DateBucket
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaType
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** Accès en lecture à l'index des médias. */
@Singleton
class MediaRepository @Inject constructor(
    private val dao: MediaDao,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val invalidationTracker: InvalidationTracker,
) {

    /**
     * Liste paginée des médias de [query].
     * @param initialIndex position de départ (visionneuse ouverte sur un média précis).
     */
    fun paged(query: MediaQuery, initialIndex: Int? = null): Flow<PagingData<MediaItem>> =
        Pager(
            config = PAGING_CONFIG,
            initialKey = initialIndex,
            pagingSourceFactory = { pagingSource(query) },
        ).flow.map { data -> data.map(MediaEntity::toModel) }

    /** Source paginée de [query], rechargée quand une des tables que lit sa requête change. */
    fun pagingSource(query: MediaQuery): PagingSource<Int, MediaEntity> =
        MediaPagingSource(dao, invalidationTracker, query, query.observed.tables, io)

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

    /**
     * Histogramme des périodes de [level] (bulle de la barre de défilement rapide), recalculé quand
     * une des tables que lit [query] change ; chaque période commence à son début ([histogram]).
     */
    fun observeHistogram(query: MediaQuery, level: GroupLevel, zone: ZoneId = ZoneId.systemDefault()): Flow<List<DateBucket>> =
        invalidationTracker.createFlow(*query.observed.tables)
            .mapLatest { histogram(query, level, zone) }
            .distinctUntilChanged()
            .flowOn(io)

    /**
     * Nombre de médias de [query] par période de [level], non vides, dans l'ordre de la grille.
     * Périodes bornées en Kotlin dans le fuseau [zone], comme les en-têtes ([toGridItems]), puis
     * comptées par plages de dates dans l'index (`MediaQueryBuilder.histogram`).
     */
    suspend fun histogram(query: MediaQuery, level: GroupLevel, zone: ZoneId = ZoneId.systemDefault()): List<DateBucket> {
        val range = dao.dateRange(MediaQueryBuilder.dateRange(query))
        val first = range?.first ?: return emptyList()
        val last = range.last ?: return emptyList()
        val buckets = chunks(DateGrouping.bounds(first, last, level, zone))
            .flatMap { dao.histogram(MediaQueryBuilder.histogram(query, it)) }
            .filter { it.count > 0 }
            .sortedBy { it.periodStart }
            .map { DateBucket(it.periodStart, it.count) }
        return if (query.sort.ascending) buckets else buckets.asReversed()
    }

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
    suspend fun gridPositionOf(
        query: MediaQuery,
        mediaId: Long,
        level: GroupLevel?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int? = withContext(io) {
        val anchor = dao.get(mediaId) ?: return@withContext null
        val position = dao.count(MediaQueryBuilder.position(query, anchor))
        if (level == null || !query.sort.isChronological) return@withContext position
        position + periodsBefore(query, anchor, level, zone) + 1
    }

    /**
     * En-têtes avant celui de la période de [anchor] : périodes non vides plus récentes (ordre
     * décroissant) ou plus anciennes (croissant), bornées en Kotlin dans le fuseau [zone] puis
     * cherchées une à une dans l'index (`MediaQueryBuilder.periodsWithMedia`), sans lire les
     * médias qui précèdent.
     */
    private suspend fun periodsBefore(query: MediaQuery, anchor: MediaEntity, level: GroupLevel, zone: ZoneId): Int {
        val range = dao.dateRange(MediaQueryBuilder.dateRange(query))
        val first = range?.first ?: return 0
        val last = range.last ?: return 0
        val bounds = if (query.sort.ascending) {
            val start = DateGrouping.periodStart(anchor.dateTaken, level, zone)
            if (first >= start) return 0
            DateGrouping.bounds(first, start - 1, level, zone)
        } else {
            val end = DateGrouping.periodEnd(anchor.dateTaken, level, zone)
            if (last < end) return 0
            DateGrouping.bounds(end, last, level, zone)
        }
        return chunks(bounds).sumOf { dao.count(MediaQueryBuilder.periodsWithMedia(query, it)) }
    }

    /** [bounds] par tranches d'au plus `MAX_PERIODS` périodes (une borne commune entre deux tranches). */
    private fun chunks(bounds: List<Long>): List<List<Long>> =
        bounds.windowed(MediaQueryBuilder.MAX_PERIODS + 1, MediaQueryBuilder.MAX_PERIODS, partialWindows = true).filter { it.size >= 2 }

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

/**
 * Tables dont les changements relancent une requête observée (voir [MediaQuery.observed]) : celles
 * des `observedEntities` des requêtes de [MediaDao], suivies aussi par les pages et l'histogramme.
 */
private enum class Observed(vararg val tables: String) {
    /** `media` seule : timeline, dossiers, filtres de type ou de date. */
    MEDIA("media"),

    /** Avec les favoris, les albums et l'analyse principale (lieux, étiquettes). */
    LIBRARY("media", "favorite", "album_media", "media_label", "media_analysis"),

    /** Avec aussi les étapes d'analyse complémentaires (texte, visages, empreintes). */
    ANALYSIS("media", "favorite", "album_media", "media_label", "media_analysis", "media_text", "face", "media_embedding"),
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
