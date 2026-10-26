package app.focale.core.mediastore

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.withTransaction
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaDao
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaSyncKey
import app.focale.core.di.ApplicationScope
import app.focale.core.di.IoDispatcher
import app.focale.core.di.StateStore
import app.focale.core.permissions.MediaAccess
import app.focale.core.permissions.MediaAccessManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/** État de la synchronisation, affiché par l'interface lors de la première indexation. */
sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val indexed: Int) : SyncState
}

/**
 * Maintient l'index Room aligné sur MediaStore.
 *
 * - Android 11+ : synchronisation incrémentale grâce à `GENERATION_MODIFIED`, plus une
 *   lecture des seuls identifiants pour détecter les suppressions.
 * - Sinon, ou si la base MediaStore a changé de version, ou en accès partiel :
 *   comparaison complète (identifiant + date de modification).
 *
 * Les écritures sont faites par lots dans des transactions ; Room invalide les
 * PagingSource et l'interface se met à jour d'elle-même. À la première indexation, les mots
 * des noms de fichiers (recherche) sont écrits ensuite, en arrière-plan ([indexNames]).
 */
@Singleton
class MediaSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: FocaleDatabase,
    private val dao: MediaDao,
    private val source: MediaStoreSource,
    private val accessManager: MediaAccessManager,
    @StateStore private val stateStore: DataStore<Preferences>,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _changes = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Émet le nombre de lignes modifiées après chaque synchronisation qui a changé l'index. */
    val changes: SharedFlow<Int> = _changes.asSharedFlow()

    /** Lance une synchronisation en tâche de fond (ignorée si une autre est en cours). */
    fun requestSync() {
        scope.launch { sync() }
    }

    @Volatile
    private var rerunRequested = false

    /**
     * Synchronise et retourne le nombre de lignes modifiées. Un appel pendant une
     * synchronisation en cours la fait simplement recommencer une fois terminée.
     */
    suspend fun sync(): Int = withContext(io) {
        if (!mutex.tryLock()) {
            rerunRequested = true
            return@withContext 0
        }
        runAndUnlock()
    }

    /**
     * Synchronise et attend la fin : celle en cours, puis une de plus, qui voit tous les changements
     * survenus jusqu'à l'appel (appariement d'une sauvegarde restaurée, qui doit trouver les photos
     * tout juste copiées). Retourne le nombre de lignes modifiées par la seconde.
     */
    suspend fun syncNow(): Int = withContext(io) {
        mutex.lock()
        runAndUnlock()
    }

    /** Synchronise, verrou pris, autant de fois que demandé entre-temps, puis rend le verrou. */
    private suspend fun runAndUnlock(): Int {
        try {
            var changed = 0
            do {
                rerunRequested = false
                val access = accessManager.access.value
                if (access == MediaAccess.DENIED) break
                changed += runCatching { syncLocked(access) }
                    .onFailure { Log.w(TAG, "Synchronisation MediaStore impossible", it) }
                    .getOrDefault(0)
            } while (rerunRequested)
            if (changed > 0) _changes.tryEmit(changed)
            // Mots des noms laissés par la première indexation (ou par un passage interrompu).
            if (runCatching { dao.hasPendingNames() }.getOrDefault(false)) {
                scope.launch { runCatching { indexNames() }.onFailure { Log.w(TAG, "Index des noms impossible", it) } }
            }
            return changed
        } finally {
            _state.value = SyncState.Idle
            mutex.unlock()
        }
    }

    private val namesMutex = Mutex()

    /**
     * Écrit les mots des noms laissés en attente par la première indexation
     * ([MediaDao.upsertNamesLater]), par lots, chacun dans sa transaction : lancé en arrière-plan
     * après la synchronisation, il n'en retarde ni la fin ni l'affichage de la timeline (qui ne lit
     * pas ces tables), et la recherche par nom reste exacte pendant ce temps. Un appel pendant
     * l'écriture attend qu'elle se termine.
     */
    suspend fun indexNames() = withContext(io) {
        namesMutex.withLock {
            while (dao.indexPendingNames(NAMES_BATCH_SIZE) > 0) yield()
        }
    }

    private suspend fun syncLocked(access: MediaAccess): Int {
        val prefs = stateStore.data.first()
        val version = source.version(context)
        val lastGeneration = prefs[KEY_GENERATION] ?: 0L
        val incremental = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            access == MediaAccess.FULL &&
            prefs[KEY_VERSION] == version &&
            prefs[KEY_ACCESS] == access.name &&
            lastGeneration > 0

        // Base MediaStore recréée : les dates retenues par l'application visaient d'autres médias.
        val previousVersion = prefs[KEY_VERSION]
        if (previousVersion != null && previousVersion != version) dao.clearOriginalDates()
        val originalDates = dao.originalDates().associate { it.mediaId to it.dateTaken }

        val existing: Map<Long, MediaSyncKey> = dao.syncKeys().associateBy { it.id }
        val batch = ArrayList<MediaEntity>(BATCH_SIZE)
        var changed = 0
        var maxGeneration = lastGeneration
        var seen = 0

        // Première indexation : les mots des noms viennent après (indexNames), la timeline s'affiche sans eux.
        val firstIndexing = existing.isEmpty()

        suspend fun flush() {
            if (batch.isEmpty()) return
            val rows = batch.toList()
            batch.clear()
            database.withTransaction { if (firstIndexing) dao.upsertNamesLater(rows) else dao.upsert(rows) }
            changed += rows.size
        }

        // 1. Ajouts et modifications, écrits par lots pendant le parcours du curseur.
        if (firstIndexing) _state.value = SyncState.Running(0)
        source.scan(if (incremental) lastGeneration else null, originalDates) { row ->
            seen++
            if (row.generation > maxGeneration) maxGeneration = row.generation
            val known = existing[row.id]
            if (known == null || known.dateModified != row.dateModified ||
                known.generation != row.generation || known.isTrashed != row.isTrashed
            ) {
                batch += row
                if (batch.size >= BATCH_SIZE) {
                    flush()
                    if (firstIndexing) _state.value = SyncState.Running(changed)
                }
            }
        }
        flush()

        // 2. Suppressions.
        val currentIds = source.allIds()
        currentIds.sort()
        val removed = existing.keys.filter { currentIds.binarySearch(it) < 0 }
        removed.chunked(MediaRepositoryLimits.MAX_SQL_ARGS).forEach { chunk ->
            database.withTransaction { dao.deleteByIds(chunk) }
        }
        changed += removed.size

        // 3. Métadonnées orphelines : seulement avec un accès complet, sinon un accès
        //    partiel temporaire effacerait favoris et albums des médias non sélectionnés.
        if (access == MediaAccess.FULL && removed.isNotEmpty()) {
            database.withTransaction {
                dao.deleteOrphanFavorites()
                dao.deleteOrphanAlbumEntries()
                dao.deleteOrphanLabels()
                dao.deleteOrphanAnalysis()
                dao.deleteOrphanTexts()
                dao.deleteOrphanFaceScans()
                dao.deleteOrphanFaces()
                dao.deleteOrphanEmbeddings()
                dao.deleteOrphanDates()
            }
        }

        stateStore.edit {
            it[KEY_VERSION] = version
            it[KEY_GENERATION] = maxGeneration
            it[KEY_ACCESS] = access.name
        }
        Log.d(TAG, "Synchro ${if (incremental) "incrémentale" else "complète"} : $seen lues, $changed modifiées")
        return changed
    }

    private companion object {
        const val TAG = "MediaSync"
        const val BATCH_SIZE = 1000
        const val NAMES_BATCH_SIZE = 1000
        val KEY_VERSION = stringPreferencesKey("mediastore_version")
        val KEY_GENERATION = longPreferencesKey("mediastore_generation")
        val KEY_ACCESS = stringPreferencesKey("mediastore_access")
    }
}
