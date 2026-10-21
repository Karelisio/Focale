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
import app.focale.core.model.MediaItem
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
        var labelingUnavailable = false
        // Chaque lot reprend après le précédent : coût de sélection constant au fil du passage.
        val queue = AnalysisQueue(dao)
        try {
            while (!isStopped) {
                val batch = queue.next(BATCH_SIZE)
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
                    labelingUnavailable = true
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
            // Étapes complémentaires, une fois l'analyse principale à jour ; comme pour celle-ci,
            // chaque lot reprend après le précédent.
            for (step in steps.sortedBy { it.order }) {
                var last: MediaItem? = null
                while (!isStopped) {
                    val items = last?.let { step.pendingAfter(it, STEP_BATCH_SIZE) } ?: step.pending(STEP_BATCH_SIZE)
                    if (items.isEmpty()) {
                        issues.clear(step.kind)
                        break
                    }
                    last = items.last()
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
            // Médias arrivés pendant les étapes : la planification « KEEP » d'une synchronisation
            // terminée entre-temps a été ignorée, un passage de plus les analyse.
            if (!isStopped && !labelingUnavailable && dao.pending(ImageAnalyzer.VERSION, 1).isNotEmpty()) {
                scheduler.continueLater(manual)
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

        /** Médias par lot de l'analyse principale (aussi la taille des lots des tests de charge). */
        const val BATCH_SIZE = 24

        /** Médias par lot des étapes complémentaires (texte, visages, empreintes). */
        const val STEP_BATCH_SIZE = 16
        private const val MAX_RUN_MS = 6 * 60 * 1000L
    }
}
