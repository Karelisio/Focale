package app.focale.video

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.data.MediaOperations
import app.focale.data.OperationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as ExoMediaItem

/** Copie demandée : plage conservée et suppression éventuelle de la piste audio. */
data class TrimRequest(val range: TrimRange, val removeAudio: Boolean) {
    val hasChanges: Boolean get() = !range.isFull || removeAudio
}

/**
 * Enregistre une copie coupée (et éventuellement muette) d'une vidéo avec Media3 Transformer.
 * L'original n'est jamais modifié : l'export est écrit dans un fichier temporaire, puis publié
 * via MediaStore (en attente pendant l'écriture) dans le même dossier, avec la date de prise de
 * vue de l'original, comme le fait l'éditeur photo.
 *
 * Sans réencodage quand c'est possible : couper la fin ou retirer le son est un simple
 * remultiplexage ; couper le début aussi, le point exact étant donné aux lecteurs par une liste
 * d'édition MP4 (les images entre l'image clé précédente et le point de coupe sont décodées mais
 * pas affichées). Si ce mode échoue (piste que le multiplexeur MP4 ne sait pas recopier…),
 * l'export est relancé avec l'optimisation de coupe de Transformer, qui ne réencode que le
 * début de la vidéo et se replie d'elle-même sur un export complet si nécessaire.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
@Singleton
class VideoTrimmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val operations: MediaOperations,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private enum class Mode { TRANSMUX, EDIT_LIST, TRIM_OPTIMIZATION }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Exporte [request] de [item] et publie la copie. [onProgress] reçoit l'avancement global
     * (de 0 à 1), depuis n'importe quel thread. Annuler l'appelant interrompt l'export et efface
     * toute trace de la copie.
     */
    suspend fun saveCopy(item: MediaItem, request: TrimRequest, onProgress: (Float) -> Unit): OperationResult {
        val output = withContext(io) { prepareOutput() }
        return try {
            export(item, request, output) { onProgress(it * EXPORT_SHARE) }
            publish(item, output) { onProgress(EXPORT_SHARE + it * (1f - EXPORT_SHARE)) }
            OperationResult.Success(1)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Copie impossible : ${item.displayName}", error)
            OperationResult.Failure(error)
        } finally {
            output.delete()
        }
    }

    private fun prepareOutput(): File {
        val dir = File(context.cacheDir, WORK_DIR).apply { mkdirs() }
        // Restes d'un export interrompu par l'arrêt du processus.
        val limit = System.currentTimeMillis() - STALE_MS
        dir.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.delete() }
        return File(dir, "${UUID.randomUUID()}.mp4")
    }

    private suspend fun export(item: MediaItem, request: TrimRequest, output: File, onProgress: (Float) -> Unit) {
        val modes = if (request.range.trimsStart) listOf(Mode.EDIT_LIST, Mode.TRIM_OPTIMIZATION) else listOf(Mode.TRANSMUX)
        for ((index, mode) in modes.withIndex()) {
            try {
                val result = transform(item, request, output, mode, onProgress)
                Log.i(TAG, "Copie exportée (mode $mode, optimisation ${result.optimizationResult})")
                return
            } catch (error: ExportException) {
                if (index == modes.lastIndex) throw error
                Log.i(TAG, "Export en mode $mode impossible, nouvel essai", error)
                output.delete()
            }
        }
    }

    /** Un export par Transformer, piloté depuis le thread principal (son thread d'application). */
    private suspend fun transform(
        item: MediaItem,
        request: TrimRequest,
        output: File,
        mode: Mode,
        onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.Main) {
        val transformer = Transformer.Builder(context)
            // Rien n'est transmis à la plateforme (statistiques d'édition) : tout reste local.
            .setUsePlatformDiagnostics(false)
            .apply {
                when (mode) {
                    Mode.EDIT_LIST -> experimentalSetMp4EditListTrimEnabled(true)
                    Mode.TRIM_OPTIMIZATION -> experimentalSetTrimOptimizationEnabled(true)
                    Mode.TRANSMUX -> Unit
                }
            }
            .build()
        val edited = EditedMediaItem.Builder(request.toMediaItem(item.uri))
            .setRemoveAudio(request.removeAudio)
            .build()
        coroutineScope {
            val progress = launch {
                val holder = ProgressHolder()
                while (true) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress / 100f)
                    }
                    delay(PROGRESS_INTERVAL_MS)
                }
            }
            try {
                suspendCancellableCoroutine { continuation ->
                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            continuation.resume(exportResult)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            continuation.resumeWithException(exportException)
                        }
                    })
                    // Transformer ne s'utilise que depuis son thread : l'annulation y est postée.
                    continuation.invokeOnCancellation { mainHandler.post { transformer.cancel() } }
                    transformer.start(edited, output.path)
                }
            } finally {
                progress.cancel()
            }
        }
    }

    /** Publie le fichier exporté à côté de l'original ; l'entrée reste en attente jusqu'à la fin. */
    private suspend fun publish(item: MediaItem, file: File, onProgress: (Float) -> Unit) = withContext(io) {
        val job = coroutineContext[Job]
        val total = file.length().coerceAtLeast(1L)
        operations.insertAndWrite(
            displayName = item.displayName.substringBeforeLast('.').ifBlank { "video" } + "_edit.mp4",
            mimeType = MimeTypes.VIDEO_MP4,
            relativePath = item.relativePath.ifBlank { DEFAULT_FOLDER },
            dateTaken = item.dateTaken,
            isVideo = true,
        ) { output ->
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                var copied = 0L
                while (true) {
                    // Annulée en cours de copie : insertAndWrite supprime l'entrée incomplète.
                    job?.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    onProgress(copied.toFloat() / total)
                }
            }
        }
    }

    private companion object {
        const val TAG = "VideoTrimmer"
        const val WORK_DIR = "video_edit"
        const val DEFAULT_FOLDER = "Movies/Focale/"
        const val COPY_BUFFER = 256 * 1024
        const val PROGRESS_INTERVAL_MS = 200L

        /** Part de l'export dans la progression affichée, le reste allant à la copie. */
        const val EXPORT_SHARE = 0.9f
        val STALE_MS: Long = TimeUnit.HOURS.toMillis(1)
    }
}

/** Élément Media3 à exporter : l'URI de l'original, rognée si la plage n'est pas complète. */
internal fun TrimRequest.toMediaItem(uri: Uri): ExoMediaItem {
    val builder = ExoMediaItem.Builder().setUri(uri)
    if (!range.isFull) {
        val clipping = ExoMediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.startMs)
        if (range.trimsEnd) clipping.setEndPositionMs(range.endMs)
        builder.setClippingConfiguration(clipping.build())
    }
    return builder.build()
}
