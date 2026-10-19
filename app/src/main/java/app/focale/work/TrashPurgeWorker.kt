package app.focale.work

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.focale.data.MediaOperations
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Android 10 uniquement : purge quotidienne de la corbeille applicative
 * (éléments de plus de 30 jours). Sur Android 11+, le système s'en charge.
 */
@HiltWorker
class TrashPurgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val operations: MediaOperations,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        operations.purgeLegacyTrash()
        return Result.success()
    }

    companion object {
        private const val NAME = "trash-purge"

        fun schedule(workManager: WorkManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                workManager.cancelUniqueWork(NAME)
                return
            }
            workManager.enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TrashPurgeWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
