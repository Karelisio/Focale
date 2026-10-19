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
import app.focale.ml.ImageAnalyzer
import app.focale.ml.LabelDictionary
import app.focale.ml.OfflineGeocoder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * Analyse en arrière-plan de la bibliothèque, par lots, les médias récents d'abord.
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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.current.value.analysisEnabled) return Result.success()
        val deadline = SystemClock.elapsedRealtime() + MAX_RUN_MS
        var processed = 0
        try {
            while (!isStopped) {
                val batch = dao.pending(ImageAnalyzer.VERSION, BATCH_SIZE)
                if (batch.isEmpty()) return Result.success()

                val analyses = ArrayList<MediaAnalysisEntity>(batch.size)
                val labels = ArrayList<MediaLabelEntity>()
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
                dao.save(analyses, labels)
                processed += analyses.size
                setProgress(workDataOf(KEY_PROCESSED to processed))

                if (SystemClock.elapsedRealtime() > deadline) {
                    scheduler.continueLater()
                    return Result.success()
                }
            }
            return Result.success()
        } finally {
            geocoder.release()
        }
    }

    companion object {
        private const val TAG = "AnalysisWorker"
        const val KEY_PROCESSED = "processed"
        private const val BATCH_SIZE = 24
        private const val MAX_RUN_MS = 6 * 60 * 1000L
    }
}
