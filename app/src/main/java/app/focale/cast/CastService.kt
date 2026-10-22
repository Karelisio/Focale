package app.focale.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.focale.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Service au premier plan de la diffusion vers une TV (variante standard, type `connectedDevice`) :
 * tant que la TV lit un média servi par Focale, il garde le processus actif en arrière-plan et
 * affiche « Diffusion sur … » avec « Arrêter ». Il ne fait rien d'autre : la diffusion elle-même
 * est celle de [CastManager], qui le démarre et l'arrête. Fermer Focale depuis les applications
 * récentes arrête la diffusion.
 */
class CastService : Service() {

    private val manager: CastManager by lazy {
        EntryPointAccessors.fromApplication(applicationContext, CastEntryPoint::class.java).castManager()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        // Aussi pour « Arrêter » : le service relancé par la notification repasse au premier plan
        // avant de s'arrêter.
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(name), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        started.exceptionOrNull()?.let { Log.w(TAG, "Service de diffusion impossible", it) }
        when {
            intent?.action == ACTION_STOP -> {
                manager.stopTv()
                stopSelf()
            }
            started.isFailure || !manager.tvActive -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        manager.stopTv()
        stopSelf()
    }

    private fun notification(name: String): Notification {
        ensureChannel()
        val stop = PendingIntent.getService(
            this,
            STOP_REQUEST,
            Intent(this, CastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent().setPackage(packageName)
        val open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (name.isBlank()) getString(R.string.cast_notification_title_generic) else getString(R.string.cast_notification_title, name))
            .setContentText(getString(R.string.cast_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.cast_stop), stop)
            .build()
    }

    private fun ensureChannel() {
        val system = getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL) != null) return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.cast_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.cast_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CastEntryPoint {
        fun castManager(): CastManager
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL = "cast"
        private const val NOTIFICATION_ID = 5_001
        private const val STOP_REQUEST = 5_002
        private const val ACTION_STOP = "app.focale.action.CAST_STOP"
        private const val EXTRA_NAME = "name"

        /**
         * Démarré par la diffusion vers une TV, Focale à l'écran : un service ordinaire, qui passe
         * aussitôt au premier plan (permis tant que Focale est visible, Android 12+). Contrairement
         * à `startForegroundService`, un arrêt immédiat (TV refusée) ne peut pas faire échouer
         * l'application faute d'appel à `startForeground`.
         */
        fun start(context: Context, name: String) {
            runCatching {
                context.startService(Intent(context, CastService::class.java).putExtra(EXTRA_NAME, name))
            }.onFailure { Log.w(TAG, "Service de diffusion non démarré", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastService::class.java))
        }
    }
}
