package app.focale.vault

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.focale.core.di.ApplicationScope
import app.focale.core.settings.SettingsRepository
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * État de déverrouillage du coffre, en mémoire uniquement : un redémarrage de
 * l'application le reverrouille toujours.
 *
 * Observe le cycle de vie du processus : une fois l'application en arrière-plan, le
 * coffre se reverrouille immédiatement ou après le délai choisi dans les réglages, sauf
 * pendant un sélecteur de documents ouvert depuis le coffre ([expectSystemPicker]).
 * Au verrouillage, les images déchiffrées sont retirées du cache mémoire de Coil.
 */
@Singleton
class VaultSession @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : DefaultLifecycleObserver {

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private var backgroundSince = 0L
    /** Délai appliqué au dernier passage en arrière-plan. */
    private var lockDelayMs = 0L
    private var pickerRequestedAt: Long? = null
    private var pendingLock: Job? = null

    fun unlock() {
        _unlocked.value = true
    }

    /**
     * À appeler juste avant d'ouvrir un sélecteur de documents système (sauvegarde,
     * restauration) : l'application passe en arrière-plan sans que l'utilisateur la quitte.
     * Le verrouillage automatique qui suit est alors différé d'au moins [PICKER_GRACE_MS],
     * une seule fois.
     */
    fun expectSystemPicker() {
        pickerRequestedAt = SystemClock.elapsedRealtime()
    }

    fun lock() {
        pendingLock?.cancel()
        pendingLock = null
        _unlocked.value = false
        purgeDecryptedImages()
    }

    override fun onStop(owner: LifecycleOwner) {
        val pickerAt = pickerRequestedAt
        pickerRequestedAt = null
        if (!_unlocked.value) return
        backgroundSince = SystemClock.elapsedRealtime()
        // L'arrêt du processus est signalé peu après celui de l'activité : un sélecteur
        // demandé à l'instant explique ce passage en arrière-plan.
        val forPicker = pickerAt != null && backgroundSince - pickerAt <= PICKER_LAUNCH_WINDOW_MS
        val delayMs = if (forPicker) maxOf(autoLockDelayMs(), PICKER_GRACE_MS) else autoLockDelayMs()
        lockDelayMs = delayMs
        if (delayMs <= 0) {
            lock()
        } else {
            pendingLock = scope.launch(Dispatchers.Main) {
                delay(delayMs)
                lock()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        pendingLock?.cancel()
        pendingLock = null
        // Processus gelé en arrière-plan : le délai a pu expirer sans que la tâche s'exécute.
        if (_unlocked.value && SystemClock.elapsedRealtime() - backgroundSince >= lockDelayMs) lock()
    }

    private fun autoLockDelayMs(): Long = settings.current.value.vaultAutoLockSeconds * 1_000L

    private fun purgeDecryptedImages() {
        val cache = SingletonImageLoader.get(context).memoryCache ?: return
        cache.keys
            .filter { it.key.startsWith(VaultImage.KEY_PREFIX) }
            .forEach(cache::remove)
    }

    private companion object {
        /** Temps laissé pour choisir un fichier dans le sélecteur système. */
        const val PICKER_GRACE_MS = 3 * 60_000L
        const val PICKER_LAUNCH_WINDOW_MS = 10_000L
    }
}
