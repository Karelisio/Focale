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
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import app.focale.MainActivity
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

    /**
     * Nouveaux médias dans le dossier [folder] lié à l'album du coffre [albumId] : un toucher ouvre
     * le coffre, « Mettre au coffre » les y ajoute d'un geste. Le nom du dossier n'apparaît pas
     * sur l'écran verrouillé ; [alert] : le nombre a augmenté, la notification sonne.
     */
    fun newMedia(albumId: Long, folder: String, photos: Int, videos: Int, alert: Boolean) {
        val count = photos + videos
        val text = when {
            videos == 0 -> context.resources.getQuantityString(R.plurals.vault_notify_new_photos, count, count, folder)
            photos == 0 -> context.resources.getQuantityString(R.plurals.vault_notify_new_videos, count, count, folder)
            else -> context.resources.getQuantityString(R.plurals.vault_notify_new_items, count, count, folder)
        }
        val add = PendingIntent.getActivity(
            context,
            albumId.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_VAULT_ADD_NEW)
                .putExtra(MainActivity.EXTRA_VAULT_ALBUM_ID, albumId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notify(
            newMediaId(albumId),
            builder(CHANNEL_NEW_MEDIA)
                .setContentTitle(context.getString(R.string.vault_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openVault())
                .setAutoCancel(true)
                .setOnlyAlertOnce(!alert)
                .setGroup(CHANNEL_NEW_MEDIA)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_NEW_MEDIA)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(context.getString(R.string.vault_title))
                        .setContentText(context.getString(R.string.vault_notify_public))
                        .build(),
                )
                .addAction(0, context.getString(R.string.vault_notify_add), add)
                .build(),
        )
    }

    fun cancelNewMedia(albumId: Long) = cancel(newMediaId(albumId))

    private fun newMediaId(albumId: Long): Int = NEW_MEDIA_ID + albumId.toInt()

    /** Sauvegarde automatique en cours, quand elle peut passer au premier plan : discrète, sans détail. */
    fun autoBackupForeground(): ForegroundInfo {
        val notification = builder(CHANNEL_BACKUP)
            .setContentTitle(context.getString(R.string.vault_auto_backup_running))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return ForegroundInfo(AUTO_BACKUP_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Échec d'une sauvegarde automatique : sa cause seulement ; un toucher ouvre le coffre. */
    fun autoBackupFailed(error: AutoBackupError) {
        val text = context.getString(error.message)
        notify(
            AUTO_BACKUP_FAILED_ID,
            builder(CHANNEL_BACKUP)
                .setContentTitle(context.getString(R.string.vault_auto_backup_failed_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openVault())
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .build(),
        )
    }

    fun cancelAutoBackupFailed() = cancel(AUTO_BACKUP_FAILED_ID)

    /** Ouvre le coffre (verrouillé s'il l'est). */
    private fun openVault(): PendingIntent = PendingIntent.getActivity(
        context,
        OPEN_VAULT_REQUEST,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_VAULT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Vrai si les notifications peuvent s'afficher (réglages, et permission depuis Android 13). */
    fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

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
        // Le dernier canal ajouté manque : les autres sont recréés à l'identique, sans effet.
        if (system.getNotificationChannel(CHANNEL_BACKUP) != null) return
        system.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_IMPORT, context.getString(R.string.vault_import_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(
                    CHANNEL_NEW_MEDIA,
                    context.getString(R.string.vault_notify_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.vault_notify_channel_description)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(
                    CHANNEL_BACKUP,
                    context.getString(R.string.vault_auto_backup_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.vault_auto_backup_channel_description)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            ),
        )
    }

    companion object {
        const val CHANNEL_IMPORT = "vault_import"
        const val CHANNEL_NEW_MEDIA = "vault_new_media"
        const val CHANNEL_BACKUP = "vault_backup"
        const val IMPORT_ID = 3_001
        const val RESULT_ID = 3_002
        private const val OPEN_VAULT_REQUEST = 3_003
        const val AUTO_BACKUP_ID = 3_004
        const val AUTO_BACKUP_FAILED_ID = 3_005
        /** Notifications des nouveaux médias : une par album lié, à partir de cet identifiant. */
        private const val NEW_MEDIA_ID = 10_000
    }
}
