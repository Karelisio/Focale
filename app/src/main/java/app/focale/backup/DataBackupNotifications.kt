package app.focale.backup

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
import app.focale.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notifications de la sauvegarde automatique des données, sur leur propre canal : sauvegarde en
 * cours (service au premier plan) et échec, avec sa cause seulement ; un toucher ouvre Focale.
 */
@Singleton
class DataBackupNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) : AutoBackupNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun autoBackupForeground(): ForegroundInfo {
        val notification = builder()
            .setContentTitle(context.getString(R.string.data_auto_backup_running))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return ForegroundInfo(AUTO_BACKUP_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun autoBackupFailed(error: AutoBackupError) {
        if (!allowed()) return
        val text = context.getString(error.dataMessage)
        val notification = builder()
            .setContentTitle(context.getString(R.string.data_auto_backup_failed_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        @Suppress("MissingPermission") // Vérifiée par allowed().
        manager.notify(AUTO_BACKUP_FAILED_ID, notification)
    }

    override fun cancelAutoBackupFailed() = manager.cancel(AUTO_BACKUP_FAILED_ID)

    /** Vrai si les notifications peuvent s'afficher (réglages, et permission depuis Android 13). */
    private fun allowed(): Boolean = manager.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    /** Notification commune : icône, ouverture de Focale au toucher, contenu masqué écran verrouillé. */
    private fun builder(): NotificationCompat.Builder {
        ensureChannel()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openApp())
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.app_name))
                    .build(),
            )
    }

    private fun openApp(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        return PendingIntent.getActivity(context, OPEN_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannel() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL) != null) return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.data_auto_backup_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.data_auto_backup_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    companion object {
        const val CHANNEL = "data_backup"
        const val AUTO_BACKUP_ID = 4_001
        const val AUTO_BACKUP_FAILED_ID = 4_002
        private const val OPEN_REQUEST = 4_003
    }
}
