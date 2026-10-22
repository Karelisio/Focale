package app.focale.backup

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
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
 * Sauvegardes automatiques confiées à WorkManager : une tâche périodique [worker], quotidienne ou
 * hebdomadaire, et au besoin une tâche ponctuelle, sous des noms propres à chaque sauvegarde
 * ([periodic], [now]). Batterie et stockage de l'appareil ne doivent pas être faibles.
 */
internal class WorkAutoBackupSchedule(
    private val workManager: dagger.Lazy<WorkManager>,
    private val periodic: String,
    private val now: String,
    private val worker: Class<out ListenableWorker>,
) : AutoBackupScheduler {

    override fun schedule(frequency: AutoBackupFrequency, restart: Boolean) {
        val request = PeriodicWorkRequest.Builder(worker, frequency.days, TimeUnit.DAYS)
            .setConstraints(CONSTRAINTS)
            .build()
        // Nouvelle fréquence : l'échéance suivante en tient compte, sans sauvegarde de plus.
        val policy = if (restart) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.UPDATE
        workManager.get().enqueueUniquePeriodicWork(periodic, policy, request)
    }

    override fun runSoon() {
        val request = OneTimeWorkRequest.Builder(worker).setConstraints(CONSTRAINTS).build()
        workManager.get().enqueueUniqueWork(now, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel() {
        workManager.get().cancelUniqueWork(periodic)
        workManager.get().cancelUniqueWork(now)
    }

    companion object {
        val CONSTRAINTS: Constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}
