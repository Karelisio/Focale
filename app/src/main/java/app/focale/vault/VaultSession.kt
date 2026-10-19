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
 * coffre se reverrouille immédiatement ou après le délai choisi dans les réglages.
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
    private var pendingLock: Job? = null

    fun unlock() {
        _unlocked.value = true
    }

    fun lock() {
        pendingLock?.cancel()
        pendingLock = null
        _unlocked.value = false
        purgeDecryptedImages()
    }

    override fun onStop(owner: LifecycleOwner) {
        if (!_unlocked.value) return
        backgroundSince = SystemClock.elapsedRealtime()
        val delayMs = autoLockDelayMs()
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
        if (_unlocked.value && SystemClock.elapsedRealtime() - backgroundSince >= autoLockDelayMs()) lock()
    }

    private fun autoLockDelayMs(): Long = settings.current.value.vaultAutoLockSeconds * 1_000L

    private fun purgeDecryptedImages() {
        val cache = SingletonImageLoader.get(context).memoryCache ?: return
        cache.keys
            .filter { it.key.startsWith(VaultImage.KEY_PREFIX) }
            .forEach(cache::remove)
    }
}
