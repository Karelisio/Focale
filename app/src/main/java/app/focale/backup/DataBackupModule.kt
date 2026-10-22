package app.focale.backup

import app.focale.core.locale.LocaleChangeListener
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Branche la sauvegarde des données dans les changements de langue (canal de notification). */
@Module
@InstallIn(SingletonComponent::class)
object DataBackupModule {

    @Provides
    @IntoSet
    fun dataBackupChannel(notifications: Lazy<DataBackupNotifications>): LocaleChangeListener =
        LocaleChangeListener { notifications.get().refreshChannel() }
}
