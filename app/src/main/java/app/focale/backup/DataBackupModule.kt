package app.focale.backup

import android.content.Context
import app.focale.core.di.AppInitializer
import app.focale.core.di.ApplicationScope
import app.focale.core.di.IoDispatcher
import app.focale.core.locale.LocaleChangeListener
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Branche la sauvegarde des données dans les changements de langue (canal de notification) et le
 * démarrage : copies locales laissées par une restauration que le processus n'a pas finie, supprimées.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataBackupModule {

    @Provides
    @IntoSet
    fun dataBackupChannel(notifications: Lazy<DataBackupNotifications>): LocaleChangeListener =
        LocaleChangeListener { notifications.get().refreshChannel() }

    @Provides
    @IntoSet
    fun restoreCopies(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher io: CoroutineDispatcher,
    ): AppInitializer = AppInitializer {
        scope.launch(io) { BackupCopies.clear(BackupCopies.directory(context)) }
    }
}
