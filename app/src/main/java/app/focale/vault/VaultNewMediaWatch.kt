package app.focale.vault

import android.content.Context
import android.provider.MediaStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.VaultDao
import app.focale.core.di.StateStore
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Surveillance des dossiers liés à un album du coffre : quand des photos y arrivent, une
 * notification propose de les y mettre d'un geste. Déclenchée par les changements de MediaStore
 * (images et vidéos, regroupés), puis réarmée ; sans album lié ou si l'utilisateur l'a coupée,
 * elle ne se réarme pas.
 */
object VaultNewMediaWatch {
    private const val WATCH = "vault-new-media-watch"
    private const val CHECK = "vault-new-media-check"

    /** Étiquette des tâches de surveillance, qui se réarment elles-mêmes. */
    internal const val WATCH_TAG = "vault-new-media-watch"

    /** Changements regroupés : une rafale de photos ne réveille l'application qu'une fois. */
    private const val UPDATE_DELAY_S = 30L
    private const val MAX_DELAY_S = 120L

    /**
     * Arme la surveillance. Depuis la tâche elle-même ([fromWatch]), la suivante prend sa suite ;
     * sinon, une surveillance déjà armée est gardée.
     */
    fun arm(workManager: WorkManager, fromWatch: Boolean = false) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(UPDATE_DELAY_S, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(MAX_DELAY_S, TimeUnit.SECONDS)
            .build()
        val request = OneTimeWorkRequestBuilder<VaultNewMediaWorker>().setConstraints(constraints).addTag(WATCH_TAG).build()
        workManager.enqueueUniqueWork(WATCH, if (fromWatch) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** Recompte tout de suite (lancement, import terminé, réglage modifié), après [delaySeconds]. */
    fun checkNow(workManager: WorkManager, delaySeconds: Long = 0) {
        val request = OneTimeWorkRequestBuilder<VaultNewMediaWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).build()
        workManager.enqueueUniqueWork(CHECK, ExistingWorkPolicy.REPLACE, request)
    }
}

/**
 * Compte les nouveaux médias des dossiers liés au coffre et tient leurs notifications à jour :
 * une par album, qui ne sonne que si le nombre augmente, retirée quand il n'y a plus rien à
 * ajouter. Les nombres déjà signalés sont gardés dans l'état de l'application.
 */
@Singleton
class VaultNewMediaAlerts @Inject constructor(
    private val dao: VaultDao,
    private val sync: MediaSyncManager,
    private val settings: SettingsRepository,
    private val notifications: VaultNotifications,
    @StateStore private val state: DataStore<Preferences>,
) {
    /** Met à jour les notifications ; faux s'il n'y a plus rien à surveiller. */
    suspend fun check(): Boolean {
        val albums = dao.folderAlbums()
        val notified = state.data.first()
        if (!settings.settings.first().vaultNewMediaAlerts || albums.isEmpty()) {
            notified.albums().forEach(notifications::cancelNewMedia)
            state.edit { prefs -> prefs.albums().forEach { prefs.remove(countKey(it)) } }
            return false
        }
        sync.sync()
        val counts = HashMap<Long, Int>()
        for (album in albums) {
            val media = dao.newInSource(album.id)
            val before = notified[countKey(album.id)] ?: 0
            if (media.isEmpty()) {
                notifications.cancelNewMedia(album.id)
            } else if (media.size != before) {
                val folder = album.sourcePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: album.name
                val videos = media.count { it.mediaType == MediaQueryBuilder.MEDIA_TYPE_VIDEO }
                notifications.newMedia(album.id, folder, photos = media.size - videos, videos = videos, alert = media.size > before)
            }
            counts[album.id] = media.size
        }
        // Albums supprimés depuis : leur notification part avec eux.
        notified.albums().filter { it !in counts }.forEach(notifications::cancelNewMedia)
        state.edit { prefs ->
            prefs.albums().forEach { prefs.remove(countKey(it)) }
            counts.filterValues { it > 0 }.forEach { (id, count) -> prefs[countKey(id)] = count }
        }
        return true
    }

    /** Albums dont un nombre de nouveaux médias a été signalé. */
    private fun Preferences.albums(): List<Long> =
        asMap().keys.filter { it.name.startsWith(KEY_PREFIX) }.mapNotNull { it.name.removePrefix(KEY_PREFIX).toLongOrNull() }

    private companion object {
        const val KEY_PREFIX = "vault_new_media_"

        fun countKey(albumId: Long) = intPreferencesKey("$KEY_PREFIX$albumId")
    }
}

/** Tâche de [VaultNewMediaWatch] : vérifie, puis réarme la surveillance s'il reste à surveiller. */
@HiltWorker
class VaultNewMediaWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alerts: VaultNewMediaAlerts,
    private val workManager: WorkManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (alerts.check()) VaultNewMediaWatch.arm(workManager, fromWatch = VaultNewMediaWatch.WATCH_TAG in tags)
        return Result.success()
    }
}
