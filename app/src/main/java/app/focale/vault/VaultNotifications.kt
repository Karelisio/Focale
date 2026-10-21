package app.focale.vault

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import app.focale.R
import app.focale.data.OperationResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications du coffre-fort. Elles ne nomment ni les albums ni les médias du coffre et ne
 * montrent aucune image : seulement ce qui se passe et des nombres. Sur l'écran verrouillé,
 * leur contenu reste masqué.
 */
@Singleton
class VaultNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = NotificationManagerCompat.from(context)

    /** Notification de l'import en cours, qui fait de la tâche un service au premier plan. */
    fun importForeground(progress: VaultImportState.Running): ForegroundInfo {
        ensureChannels()
        val text = when {
            progress.total == 0 -> context.getString(R.string.vault_import_preparing)
            progress.confirming -> context.getString(R.string.vault_import_confirm)
            else -> context.getString(R.string.vault_progress, progress.done, progress.total)
        }
        val notification = builder(CHANNEL_IMPORT)
            .setContentTitle(context.getString(R.string.vault_import_title))
            .setContentText(text)
            .setProgress(progress.total, progress.done, progress.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return ForegroundInfo(IMPORT_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Bilan d'un import terminé alors que Focale n'était pas à l'écran. */
    fun importFinished(result: OperationResult) {
        val text = when (result) {
            is OperationResult.Success -> context.resources.getQuantityString(R.plurals.message_moved_to_vault, result.count, result.count)
            is OperationResult.Partial ->
                context.getString(R.string.message_partial, result.succeeded, result.succeeded + result.failed)
            OperationResult.Cancelled -> return
            is OperationResult.Failure -> context.getString(R.string.message_operation_failed)
        }
        notify(
            RESULT_ID,
            builder(CHANNEL_IMPORT)
                .setContentTitle(context.getString(R.string.vault_import_title))
                .setContentText(text)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Vrai si les notifications peuvent s'afficher (permission d'Android 13+ et réglages). */
    fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(id: Int, notification: Notification) {
        if (!allowed()) return
        ensureChannels()
        @Suppress("MissingPermission") // Vérifiée par allowed().
        manager.notify(id, notification)
    }

    internal fun cancel(id: Int) = manager.cancel(id)

    /** Notification commune : icône, couleur et ouverture de Focale au toucher, contenu masqué verrouillé. */
    internal fun builder(channel: String): NotificationCompat.Builder {
        ensureChannels()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.app_name))
                    .build(),
            )
    }

    private fun openApp(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL_IMPORT) != null) return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_IMPORT, context.getString(R.string.vault_import_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    companion object {
        const val CHANNEL_IMPORT = "vault_import"
        const val IMPORT_ID = 3_001
        const val RESULT_ID = 3_002
    }
}
