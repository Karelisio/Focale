package app.focale.vault

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Sauvegarde automatique du coffre ([VaultAutoBackup.run]), confiée à WorkManager
 * ([app.focale.backup.WorkAutoBackupSchedule]) ; le coffre n'a pas à être déverrouillé. Son issue
 * est toujours un succès pour WorkManager : un échec est noté dans le réglage et signalé, et
 * l'échéance suivante réessaie.
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
