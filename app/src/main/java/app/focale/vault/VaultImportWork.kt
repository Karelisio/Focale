package app.focale.vault

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.hilt.work.HiltWorker
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.focale.core.model.MediaQuery
import app.focale.data.AlbumRepository
import app.focale.data.MediaRepository
import app.focale.data.OperationResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Import demandé vers le coffre, exécuté par [VaultImportWorker]. */
@Immutable
sealed interface VaultImportRequest {
    /** Album du coffre créé pour cet import, supprimé s'il ne reçoit rien. */
    val createdAlbumId: Long? get() = null

    /** Médias choisis dans la galerie, rangés dans [albumId] (aucun si null). */
    data class Selection(val ids: List<Long>, val albumId: Long?, override val createdAlbumId: Long? = null) : VaultImportRequest

    /** Tout le dossier [bucketId], dans l'album du coffre qui lui est lié. */
    data class Folder(val bucketId: Long, val name: String) : VaultImportRequest

    /** Tout l'album [albumId] de l'application, dans [vaultAlbumId] ; vidé, il est supprimé. */
    data class UserAlbum(val albumId: Long, val vaultAlbumId: Long, override val createdAlbumId: Long?) : VaultImportRequest

    /** Médias arrivés dans le dossier d'origine de l'album du coffre [vaultAlbumId]. */
    data class NewFromSource(val vaultAlbumId: Long) : VaultImportRequest

    /** Un album entier quitte la galerie (message propre, écran de l'album refermé). */
    val wholeAlbum: Boolean get() = this is Folder || this is UserAlbum
}

/** Où en est un import vers le coffre. */
@Immutable
sealed interface VaultImportState {
    /** Planifié, derrière un autre import. */
    data object Queued : VaultImportState

    /** [confirming] : chiffrement fini, suppression des originaux demandée à l'utilisateur. */
    data class Running(val done: Int, val total: Int) : VaultImportState {
        val confirming: Boolean get() = total > 0 && done >= total
    }

    data class Finished(val result: OperationResult) : VaultImportState
}

/**
 * Imports vers le coffre, confiés à WorkManager : ils vont à leur terme même si l'utilisateur
 * quitte Focale (service au premier plan, notification de progression) et reprennent après la
 * fin du processus sans rien rechiffrer (journal de [VaultRepository]). Un import à la fois,
 * dans l'ordre des demandes.
 */
@Singleton
class VaultImports @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
) {
    fun enqueue(request: VaultImportRequest): UUID {
        val work = OneTimeWorkRequestBuilder<VaultImportWorker>()
            .setInputData(encode(request, idsDirectory(context)))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        return work.id
    }

    /** Suit l'import [id] jusqu'à son issue, incluse. */
    fun observe(id: UUID): Flow<VaultImportState> = workManager.getWorkInfoByIdFlow(id)
        .map { info -> info?.toState() ?: VaultImportState.Finished(OperationResult.Failure()) }
        .transformWhile { state ->
            emit(state)
            state !is VaultImportState.Finished
        }

    /** Un import est planifié ou en cours. */
    suspend fun active(): Boolean = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_NAME).first().any { !it.state.isFinished }

    private fun WorkInfo.toState(): VaultImportState = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> VaultImportState.Queued
        WorkInfo.State.RUNNING -> VaultImportState.Running(progress.getInt(KEY_DONE, 0), progress.getInt(KEY_TOTAL, 0))
        WorkInfo.State.SUCCEEDED -> VaultImportState.Finished(resultOf(outputData))
        WorkInfo.State.FAILED -> VaultImportState.Finished(OperationResult.Failure())
        WorkInfo.State.CANCELLED -> VaultImportState.Finished(OperationResult.Cancelled)
    }

    companion object {
        const val TAG = "vault-import"
        private const val UNIQUE_NAME = "vault-import"
        internal const val KEY_KIND = "kind"
        internal const val KEY_IDS_FILE = "ids_file"
        internal const val KEY_ALBUM = "album"
        internal const val KEY_CREATED_ALBUM = "created_album"
        internal const val KEY_SOURCE_ALBUM = "source_album"
        internal const val KEY_BUCKET = "bucket"
        internal const val KEY_NAME = "name"
        internal const val KEY_DONE = "done"
        internal const val KEY_TOTAL = "total"
        internal const val KEY_OUTCOME = "outcome"
        internal const val KEY_MOVED = "moved"
        internal const val KEY_FAILED = "failed"
        internal const val KIND_SELECTION = "selection"
        internal const val KIND_FOLDER = "folder"
        internal const val KIND_USER_ALBUM = "user_album"
        internal const val KIND_NEW_FROM_SOURCE = "new_from_source"
        internal const val NONE = -1L

        /** Fichiers des sélections en attente d'import, hors sauvegarde. */
        internal fun idsDirectory(context: Context): File = File(context.noBackupFilesDir, "vault-import").apply { mkdirs() }

        /**
         * Demande en données de travail ; une sélection, de taille quelconque, est écrite dans un
         * fichier de [directory] ([Data] est limité à 10 Kio).
         */
        internal fun encode(request: VaultImportRequest, directory: File): Data = when (request) {
            is VaultImportRequest.Selection -> workDataOf(
                KEY_KIND to KIND_SELECTION,
                KEY_IDS_FILE to writeIds(request.ids, directory).path,
                KEY_ALBUM to (request.albumId ?: NONE),
                KEY_CREATED_ALBUM to (request.createdAlbumId ?: NONE),
            )
            is VaultImportRequest.Folder -> workDataOf(KEY_KIND to KIND_FOLDER, KEY_BUCKET to request.bucketId, KEY_NAME to request.name)
            is VaultImportRequest.UserAlbum -> workDataOf(
                KEY_KIND to KIND_USER_ALBUM,
                KEY_SOURCE_ALBUM to request.albumId,
                KEY_ALBUM to request.vaultAlbumId,
                KEY_CREATED_ALBUM to (request.createdAlbumId ?: NONE),
            )
            is VaultImportRequest.NewFromSource -> workDataOf(KEY_KIND to KIND_NEW_FROM_SOURCE, KEY_ALBUM to request.vaultAlbumId)
        }

        private fun writeIds(ids: List<Long>, directory: File): File {
            val file = File(directory, "${UUID.randomUUID()}.ids")
            DataOutputStream(file.outputStream().buffered()).use { output ->
                output.writeInt(ids.size)
                ids.forEach(output::writeLong)
            }
            return file
        }

        internal fun decode(data: Data): VaultImportRequest? {
            fun long(key: String): Long? = data.getLong(key, NONE).takeIf { it != NONE }
            return when (data.getString(KEY_KIND)) {
                KIND_SELECTION -> {
                    val file = data.getString(KEY_IDS_FILE)?.let(::File)?.takeIf(File::exists) ?: return null
                    val ids = DataInputStream(file.inputStream().buffered()).use { input -> List(input.readInt()) { input.readLong() } }
                    VaultImportRequest.Selection(ids, long(KEY_ALBUM), long(KEY_CREATED_ALBUM))
                }
                KIND_FOLDER -> VaultImportRequest.Folder(long(KEY_BUCKET) ?: return null, data.getString(KEY_NAME).orEmpty())
                KIND_USER_ALBUM -> VaultImportRequest.UserAlbum(
                    albumId = long(KEY_SOURCE_ALBUM) ?: return null,
                    vaultAlbumId = long(KEY_ALBUM) ?: return null,
                    createdAlbumId = long(KEY_CREATED_ALBUM),
                )
                KIND_NEW_FROM_SOURCE -> VaultImportRequest.NewFromSource(long(KEY_ALBUM) ?: return null)
                else -> null
            }
        }

        internal fun outputOf(result: OperationResult): Data = when (result) {
            is OperationResult.Success -> workDataOf(KEY_OUTCOME to "success", KEY_MOVED to result.count)
            is OperationResult.Partial -> workDataOf(KEY_OUTCOME to "partial", KEY_MOVED to result.succeeded, KEY_FAILED to result.failed)
            OperationResult.Cancelled -> workDataOf(KEY_OUTCOME to "cancelled")
            is OperationResult.Failure -> workDataOf(KEY_OUTCOME to "failure")
        }

        internal fun resultOf(data: Data): OperationResult = when (data.getString(KEY_OUTCOME)) {
            "success" -> OperationResult.Success(data.getInt(KEY_MOVED, 0))
            "partial" -> OperationResult.Partial(data.getInt(KEY_MOVED, 0), data.getInt(KEY_FAILED, 0))
            "cancelled" -> OperationResult.Cancelled
            else -> OperationResult.Failure()
        }
    }
}

/**
 * Exécute un import vers le coffre en service au premier plan. Son issue est toujours un succès
 * pour WorkManager (le bilan est dans les données de sortie) : un échec bloquerait les imports
 * demandés à sa suite. La suppression des originaux attend, au besoin, que Focale revienne à
 * l'écran pour afficher la confirmation du système.
 */
@HiltWorker
class VaultImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val vault: VaultRepository,
    private val media: MediaRepository,
    private val albums: AlbumRepository,
    private val notifications: VaultNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.importForeground(VaultImportState.Running(0, 0))

    override suspend fun doWork(): Result = coroutineScope {
        val request = VaultImports.decode(inputData) ?: return@coroutineScope Result.success(VaultImports.outputOf(OperationResult.Failure()))
        // Démarrée hors de l'écran (reprise après la fin du processus), la tâche peut se voir
        // refuser le premier plan : elle continue alors comme tâche ordinaire.
        runCatching { setForeground(getForegroundInfo()) }.onFailure { Log.w(TAG, "Service au premier plan refusé", it) }

        val progress = MutableStateFlow(VaultImportState.Running(0, 0))
        val publisher = launch {
            var published: VaultImportState.Running? = null
            while (isActive) {
                val current = progress.value
                if (current != published) {
                    published = current
                    setProgress(workDataOf(VaultImports.KEY_DONE to current.done, VaultImports.KEY_TOTAL to current.total))
                    runCatching { setForeground(notifications.importForeground(current)) }
                }
                delay(PUBLISH_INTERVAL_MS)
            }
        }
        val onProgress: (Int, Int) -> Unit = { done, total -> progress.value = VaultImportState.Running(done, total) }
        val result = try {
            execute(request, onProgress)
        } finally {
            publisher.cancel()
        }
        request.createdAlbumId?.let { if (result.moved == 0) vault.deleteAlbumIfEmpty(it) }
        if (request is VaultImportRequest.Selection) inputData.getString(VaultImports.KEY_IDS_FILE)?.let { File(it).delete() }
        if (!appVisible()) notifications.importFinished(result)
        Result.success(VaultImports.outputOf(result))
    }

    private suspend fun execute(request: VaultImportRequest, onProgress: (Int, Int) -> Unit): OperationResult = when (request) {
        is VaultImportRequest.Selection -> vault.import(
            media.getByIds(request.ids),
            request.albumId?.let(VaultDestination::Album) ?: VaultDestination.None,
            onProgress,
        )
        is VaultImportRequest.Folder -> vault.importFolder(request.bucketId, request.name, onProgress)
        is VaultImportRequest.UserAlbum -> {
            val items = media.list(MediaQuery(albumId = request.albumId))
            vault.import(items, VaultDestination.Album(request.vaultAlbumId), onProgress).also { result ->
                // Album vidé : il ne resterait de lui, dans la galerie, que son nom.
                if (result is OperationResult.Success && result.count > 0) albums.delete(request.albumId)
            }
        }
        is VaultImportRequest.NewFromSource -> vault.importNewFromSource(request.vaultAlbumId, onProgress)
    }

    private suspend fun appVisible(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private val OperationResult.moved: Int
        get() = when (this) {
            is OperationResult.Success -> count
            is OperationResult.Partial -> succeeded
            else -> 0
        }

    private companion object {
        const val TAG = "VaultImportWorker"
        /** Progression publiée (base de WorkManager, notification) au plus deux fois par seconde. */
        const val PUBLISH_INTERVAL_MS = 500L
    }
}

/**
 * Contrôle du coffre au lancement ([VaultRepository.repair]), en tâche de fond : il ne retarde
 * pas le démarrage, et laisse à un import planifié le soin de reprendre ses propres copies.
 */
@HiltWorker
class VaultRepairWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val vault: VaultRepository,
    private val imports: VaultImports,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val importActive = imports.active()
        vault.repair(importActive)
        if (!importActive) {
            // Sélections d'imports qui ne reprendront plus (une plus récente peut attendre son tour).
            val before = System.currentTimeMillis() - STALE_IDS_MS
            VaultImports.idsDirectory(applicationContext).listFiles()?.filter { it.lastModified() < before }?.forEach(File::delete)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "vault-repair"
        private const val STALE_IDS_MS = 24 * 60 * 60_000L

        fun schedule(workManager: WorkManager) {
            workManager.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<VaultRepairWorker>().build())
        }
    }
}
