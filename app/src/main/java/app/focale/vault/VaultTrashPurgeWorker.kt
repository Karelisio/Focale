package app.focale.vault

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Purge quotidienne de la corbeille du coffre : éléments mis à la corbeille il y a plus de 30 jours. */
@HiltWorker
class VaultTrashPurgeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val vault: VaultRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        vault.purgeTrash()
        return Result.success()
    }

    companion object {
        private const val NAME = "vault-trash-purge"

        fun schedule(workManager: WorkManager) {
            workManager.enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<VaultTrashPurgeWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
