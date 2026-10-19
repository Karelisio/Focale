package app.focale.ml.semantic

import android.content.Context
import android.os.SystemClock
import app.focale.core.database.EmbeddingDao
import app.focale.core.database.FocaleDatabase
import app.focale.core.di.ApplicationScope
import app.focale.core.di.DefaultDispatcher
import app.focale.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Recherche par le sens : compare l'empreinte d'un texte anglais à celles de toutes les
 * photos de la bibliothèque, gardées en mémoire.
 *
 * Les empreintes sont chargées à la première recherche et rechargées après une écriture
 * dans `media_embedding` (au plus une fois toutes les [MIN_RELOAD_MS] pendant une analyse
 * en cours). Modèle et matrice sont libérés après [IDLE_RELEASE_MS] sans recherche. Tous
 * les calculs ont lieu hors du thread principal.
 */
@Singleton
class SemanticSearch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: EmbeddingDao,
    private val database: FocaleDatabase,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val compute: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var encoder: ClipTextEncoder? = null
    private var index: EmbeddingIndex? = null
    private var loadedAt = 0L
    private var releaseJob: Job? = null
    private var watchJob: Job? = null

    @Volatile
    private var stale = true

    /**
     * Photos proches de [prompt] (phrase anglaise, voir [QueryTranslator.prompt]), de la
     * plus à la moins proche, selon [threshold] ; vide si aucune photo n'a encore d'empreinte.
     */
    suspend fun search(prompt: String, threshold: SimilarityThreshold = SimilarityThreshold.Default): List<Long> =
        mutex.withLock {
            try {
                val index = currentIndex()
                if (index.size == 0) return@withLock emptyList()
                val query = withContext(compute) { textEncoder().embed(prompt) }
                currentCoroutineContext().ensureActive()
                val scores = score(index, query)
                withContext(compute) { threshold.select(scores, index.size) }.map(index::id)
            } finally {
                scheduleRelease()
            }
        }

    /** Prépare modèle et empreintes avant la première recherche (ouverture de la barre de recherche). */
    suspend fun warmUp() {
        mutex.withLock {
            try {
                currentIndex()
                withContext(compute) { textEncoder() }
            } finally {
                scheduleRelease()
            }
        }
    }

    private suspend fun currentIndex(): EmbeddingIndex {
        watchTable()
        val current = index
        val now = SystemClock.elapsedRealtime()
        if (current != null && (!stale || now - loadedAt < MIN_RELOAD_MS)) return current
        stale = false
        return load().also {
            index = it
            loadedAt = SystemClock.elapsedRealtime()
        }
    }

    /** Lecture par tranches : la mémoire de pointe reste celle de l'index quantifié. */
    private suspend fun load(): EmbeddingIndex = withContext(io) {
        val builder = EmbeddingIndex.Builder(ClipModels.DIMENSION)
        val vector = FloatArray(ClipModels.DIMENSION)
        var after = Long.MIN_VALUE
        while (true) {
            val page = dao.page(ClipModels.VERSION, after, PAGE_SIZE)
            for (row in page) {
                if (row.vector.size != ClipModels.DIMENSION * Float.SIZE_BYTES) continue
                EmbeddingCodec.decodeInto(row.vector, vector)
                builder.add(row.mediaId, vector)
            }
            if (page.size < PAGE_SIZE) break
            after = page.last().mediaId
            ensureActive()
        }
        builder.build()
    }

    private fun textEncoder(): ClipTextEncoder = encoder ?: run {
        val tokenizer = context.assets.open(ClipModels.VOCABULARY_ASSET).use { ClipTokenizer.load(it) }
        ClipTextEncoder(ClipModels.openAsset(context, ClipModels.TEXT_ASSET, THREADS), tokenizer)
    }.also { encoder = it }

    /** Similarités en parallèle, par tranches de lignes. */
    private suspend fun score(index: EmbeddingIndex, query: FloatArray): FloatArray = coroutineScope {
        val scores = FloatArray(index.size)
        val chunk = maxOf(MIN_CHUNK, (index.size + PARALLELISM - 1) / PARALLELISM)
        (0 until index.size step chunk).map { from ->
            async(compute) { index.score(query, scores, from, minOf(from + chunk, index.size)) }
        }.awaitAll()
        scores
    }

    /** Toute écriture dans la table des empreintes rend l'index périmé. */
    private fun watchTable() {
        if (watchJob != null) return
        watchJob = scope.launch {
            database.invalidationTracker.createFlow(TABLE, emitInitialState = false).collect { stale = true }
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            mutex.withLock {
                encoder?.close()
                encoder = null
                index = null
                stale = true
            }
        }
    }

    private companion object {
        const val TABLE = "media_embedding"
        const val PAGE_SIZE = 500
        const val THREADS = 2
        const val PARALLELISM = 4
        const val MIN_CHUNK = 2048
        const val MIN_RELOAD_MS = 15_000L
        const val IDLE_RELEASE_MS = 120_000L
    }
}
