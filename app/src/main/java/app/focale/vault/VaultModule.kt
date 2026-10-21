package app.focale.vault

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.WorkManager
import app.focale.core.di.AppInitializer
import app.focale.core.image.ImageComponentContributor
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Branche le coffre dans Coil (images chiffrées), le cycle de vie (verrouillage auto) et le
 * démarrage (contrôle des imports interrompus).
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
    }
}
