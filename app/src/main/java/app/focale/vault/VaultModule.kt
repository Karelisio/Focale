package app.focale.vault

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import app.focale.core.di.AppInitializer
import app.focale.core.image.ImageComponentContributor
import app.focale.core.locale.LocaleChangeListener
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Branche le coffre dans Coil (images chiffrées), le cycle de vie (verrouillage auto), le
 * démarrage (contrôle des imports interrompus, purge de la corbeille, nouveaux médias) et les
 * changements de langue (canaux de notification).
 */
@Module
@InstallIn(SingletonComponent::class)
object VaultModule {

    @Provides
    @IntoSet
    fun vaultImages(storage: VaultStorage, crypto: VaultCrypto): ImageComponentContributor =
        ImageComponentContributor { registry ->
            registry.add(VaultImageFetcher.Factory(storage, crypto))
            registry.add(VaultImageKeyer())
        }

    @Provides
    @IntoSet
    fun vaultAutoLock(session: Lazy<VaultSession>): AppInitializer = AppInitializer {
        ProcessLifecycleOwner.get().lifecycle.addObserver(session.get())
    }

    /** Contrôle au lancement : ce qu'un import interrompu a pu laisser (voir [VaultRepository.repair]). */
    @Provides
    @IntoSet
    fun vaultRepair(workManager: Lazy<WorkManager>): AppInitializer = AppInitializer {
        VaultRepairWorker.schedule(workManager.get())
        VaultTrashPurgeWorker.schedule(workManager.get())
        // Nouveaux médias des dossiers liés : compte à jour peu après le démarrage, puis surveillance.
        VaultNewMediaWatch.checkNow(workManager.get(), delaySeconds = STARTUP_CHECK_DELAY_S)
    }

    /** Langue changée : canaux de notification du coffre renommés. */
    @Provides
    @IntoSet
    fun vaultChannels(notifications: Lazy<VaultNotifications>): LocaleChangeListener =
        LocaleChangeListener { notifications.get().refreshChannels() }

    private const val STARTUP_CHECK_DELAY_S = 15L
}
