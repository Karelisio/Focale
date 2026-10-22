package app.focale.core.database

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.InvalidationTracker
import app.focale.core.model.MediaQuery
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Pages d'une grille de médias, comme `LimitOffsetPagingSource` de Room : clé = position dans la
 * liste, placeholders (nombre de médias avant et après chaque page), sauts de la barre de
 * défilement rapide, page lue par `LIMIT`/`OFFSET` dans l'ordre de [MediaQueryBuilder.select].
 *
 * Le nombre total, lu au premier chargement, vient de [MediaQueryBuilder.count] : l'index seul
 * pour la timeline (2 ms sur 50 000 médias), au lieu du `SELECT COUNT(*) FROM (requête triée)` de
 * Room, qui lisait chaque ligne (57 ms à chaque saut ou rechargement, docs/PERFORMANCE.md).
 *
 * Invalidée au premier changement d'une des [tables] (celles que lit la requête) après le début
 * de son premier chargement ; comme chez Room, l'observation dure jusque-là.
 */
class MediaPagingSource(
    private val dao: MediaDao,
    private val tracker: InvalidationTracker,
    private val query: MediaQuery,
    private val tables: Array<out String>,
    io: CoroutineDispatcher,
) : PagingSource<Int, MediaEntity>() {

    private val itemCount = AtomicInteger(UNKNOWN)
    private val watching = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + io)

    init {
        registerInvalidatedCallback { scope.cancel() }
    }

    override val jumpingSupported: Boolean get() = true

    /** Page centrée sur la position visible, comme Room. */
    override fun getRefreshKey(state: PagingState<Int, MediaEntity>): Int? =
        state.anchorPosition?.let { maxOf(0, it - state.config.initialLoadSize / 2) }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaEntity> = try {
        if (watching.compareAndSet(false, true)) watch()
        if (invalid) LoadResult.Invalid() else page(params)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        LoadResult.Error(error)
    }

    /**
     * Observe les tables avant le premier comptage : un changement entre lui et une page invalide
     * la source. Rend la main une fois l'observation en place (première émission de Room), ou la
     * source invalidée entre-temps.
     */
    private suspend fun watch() {
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch {
            var initial = true
            tracker.createFlow(*tables).collect {
                if (initial) {
                    initial = false
                    ready.complete(Unit)
                } else {
                    invalidate()
                }
            }
        }
        job.invokeOnCompletion { ready.complete(Unit) }
        ready.await()
    }

    private suspend fun page(params: LoadParams<Int>): LoadResult<Int, MediaEntity> {
        val total = itemCount.get().takeIf { it != UNKNOWN } ?: dao.count(MediaQueryBuilder.count(query)).also(itemCount::set)
        val key = params.key ?: 0
        val limit = if (params is LoadParams.Prepend) minOf(key, params.loadSize) else params.loadSize
        val offset = when (params) {
            is LoadParams.Prepend -> maxOf(0, key - params.loadSize)
            is LoadParams.Append -> key
            // Saut au-delà de la fin (liste raccourcie depuis) : la dernière page.
            is LoadParams.Refresh -> if (key >= total) maxOf(0, total - params.loadSize) else key
        }
        val data = dao.list(MediaQueryBuilder.select(query, limit, offset))
        if (invalid) return LoadResult.Invalid()
        val next = offset + data.size
        return LoadResult.Page(
            data = data,
            prevKey = if (offset <= 0 || data.isEmpty()) null else offset,
            nextKey = if (data.isEmpty() || data.size < limit || next >= total) null else next,
            itemsBefore = offset,
            itemsAfter = maxOf(0, total - next),
        )
    }

    private companion object {
        const val UNKNOWN = -1
    }
}
