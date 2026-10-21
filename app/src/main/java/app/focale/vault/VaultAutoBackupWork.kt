package app.focale.vault

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Planification des sauvegardes automatiques ; remplacée dans les tests. */
internal interface AutoBackupScheduler {
    /** (Re)planifie la sauvegarde périodique ; [restart] : la première a lieu dès que possible. */
    fun schedule(frequency: AutoBackupFrequency, restart: Boolean)

    /** Une sauvegarde dès que possible, hors de l'échéance (mot de passe ou dossier changé). */
    fun runSoon()

    fun cancel()
}

/**
 * Sauvegardes automatiques confiées à WorkManager : une tâche périodique, quotidienne ou
 * hebdomadaire, et au besoin une tâche ponctuelle. Batterie et stockage de l'appareil ne doivent
 * pas être faibles ; le coffre n'a pas à être déverrouillé.
 */
internal class VaultAutoBackupSchedule(private val workManager: dagger.Lazy<WorkManager>) : AutoBackupScheduler {

    override fun schedule(frequency: AutoBackupFrequency, restart: Boolean) {
        val request = PeriodicWorkRequestBuilder<VaultAutoBackupWorker>(frequency.days, TimeUnit.DAYS)
            .setConstraints(CONSTRAINTS)
            .build()
        // Nouvelle fréquence : l'échéance suivante en tient compte, sans sauvegarde de plus.
        val policy = if (restart) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE
        workManager.get().enqueueUniquePeriodicWork(PERIODIC, policy, request)
    }

    override fun runSoon() {
        val request = OneTimeWorkRequestBuilder<VaultAutoBackupWorker>().setConstraints(CONSTRAINTS).build()
        workManager.get().enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        workManager.get().cancelUniqueWork(PERIODIC)
        workManager.get().cancelUniqueWork(NOW)
    }

    private companion object {
        const val PERIODIC = "vault-auto-backup"
        const val NOW = "vault-auto-backup-now"

        val CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}

/**
 * Sauvegarde automatique du coffre ([VaultAutoBackup.run]). Son issue est toujours un succès pour
 * WorkManager : un échec est noté dans le réglage et signalé, et l'échéance suivante réessaie.
 */
@HiltWorker
class VaultAutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val autoBackup: VaultAutoBackup,
    private val notifications: VaultNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.autoBackupForeground()

    override suspend fun doWork(): Result {
        autoBackup.run(
            cancelled = { isStopped },
            beforeWriting = {
                // Focale à l'écran (première sauvegarde, juste après le réglage) : au premier plan, la
                // tâche n'est plus limitée à 10 minutes. En arrière-plan, Android 12+ le refuse : elle
                // continue comme tâche ordinaire.
                runCatching { setForeground(getForegroundInfo()) }.onFailure { Log.w(TAG, "Service au premier plan refusé", it) }
            },
        )
        return Result.success()
    }

    private companion object {
        const val TAG = "VaultAutoBackupWorker"
    }
}
