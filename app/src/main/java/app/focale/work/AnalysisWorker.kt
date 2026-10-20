package app.focale.work

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.focale.core.database.AnalysisDao
import app.focale.core.database.MediaAnalysisEntity
import app.focale.core.database.MediaLabelEntity
import app.focale.core.settings.SettingsRepository
import app.focale.data.toModel
import app.focale.ml.AnalysisKind
import app.focale.ml.AnalysisStep
import app.focale.ml.ImageAnalyzer
import app.focale.ml.LabelDictionary
import app.focale.ml.OfflineGeocoder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Analyse en arrière-plan de la bibliothèque, par lots, les médias récents d'abord :
 * analyse principale (étiquettes, doublons, netteté, lieux), puis étapes
 * complémentaires ([AnalysisStep] : texte, visages, empreintes).
 *
 * Un passage dure au plus quelques minutes (limite de WorkManager pour les tâches
 * ordinaires) ; s'il reste du travail, le worker programme sa propre continuation.
 */
@HiltWorker
class AnalysisWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: AnalysisDao,
    private val analyzer: ImageAnalyzer,
    private val geocoder: OfflineGeocoder,
    private val settings: SettingsRepository,
    private val scheduler: AnalysisScheduler,
    private val issues: AnalysisIssues,
    private val steps: Set<@JvmSuppressWildcards AnalysisStep>,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.current.value.analysisEnabled) return Result.success()
        // Passage lancé à la main : ses continuations ignorent aussi « uniquement en charge ».
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val deadline = SystemClock.elapsedRealtime() + MAX_RUN_MS
        var processed = 0
        try {
            while (!isStopped) {
                val batch = dao.pending(ImageAnalyzer.VERSION, BATCH_SIZE)
                if (batch.isEmpty()) break

                val analyses = ArrayList<MediaAnalysisEntity>(batch.size)
                val labels = ArrayList<MediaLabelEntity>()
                var labeled = 0
                var labelError: Exception? = null
                for (entity in batch) {
                    if (isStopped) break
                    val result = try {
                        analyzer.analyze(entity.toModel())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Analyse impossible pour ${entity.id}", error)
                        null
                    }
                    if (result?.labels != null) labeled++
                    result?.labelError?.let { labelError = it }
                    // Même en cas d'échec, on mémorise le passage pour ne pas réessayer en boucle.
                    analyses += MediaAnalysisEntity(
                        mediaId = entity.id,
                        analyzedModified = entity.dateModified,
                        version = ImageAnalyzer.VERSION,
                        phash = result?.phash,
                        blurScore = result?.blurScore,
                        latitude = result?.latitude,
                        longitude = result?.longitude,
                        place = result?.place?.name,
                        placeKey = result?.place?.name?.let(LabelDictionary::normalize),
                        country = result?.place?.countryCode,
                    )
                    result?.labels?.forEach { (label, confidence) ->
                        labels += MediaLabelEntity(entity.id, label, confidence)
                    }
                }
                // Étiquetage en échec sur tout le lot : ML Kit est indisponible. Rien n'est
                // enregistré, sans quoi ces photos resteraient sans étiquettes ; les étapes
                // complémentaires passent quand même, la prochaine planification réessaiera.
                val unavailable = labelError
                if (unavailable != null && labeled == 0) {
                    Log.w(TAG, "Étiquetage indisponible", unavailable)
                    issues.report(AnalysisKind.LABELS, unavailable)
                    break
                }
                issues.clear(AnalysisKind.LABELS)
                dao.save(analyses, labels)
                processed += analyses.size
                setProgress(workDataOf(KEY_PROCESSED to processed))

                if (SystemClock.elapsedRealtime() > deadline) {
                    scheduler.continueLater(manual)
                    return Result.success()
                }
            }
            // Étapes complémentaires, une fois l'analyse principale à jour.
            for (step in steps.sortedBy { it.order }) {
                while (!isStopped) {
                    val items = step.pending(STEP_BATCH_SIZE)
                    if (items.isEmpty()) {
                        issues.clear(step.kind)
                        break
                    }
                    try {
                        step.process(items)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Échec global (modèle indisponible…) : noté pour l'affichage, puis
                        // on passe à l'étape suivante.
                        Log.w(TAG, "Étape ${step.name} interrompue", error)
                        issues.report(step.kind, error)
                        break
                    }
                    issues.clear(step.kind)
                    processed += items.size
                    setProgress(workDataOf(KEY_PROCESSED to processed))
                    if (SystemClock.elapsedRealtime() > deadline) {
                        scheduler.continueLater(manual)
                        return Result.success()
                    }
                }
            }
            return Result.success()
        } finally {
            geocoder.release()
            steps.forEach { it.release() }
        }
    }

    companion object {
        private const val TAG = "AnalysisWorker"
        const val KEY_PROCESSED = "processed"
        const val KEY_MANUAL = "manual"
        private const val BATCH_SIZE = 24
        private const val STEP_BATCH_SIZE = 16
        private const val MAX_RUN_MS = 6 * 60 * 1000L
    }
}
