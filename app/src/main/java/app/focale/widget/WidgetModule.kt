package app.focale.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.focale.core.locale.LocaleChangeListener
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Branche le widget dans les changements de langue : légendes redessinées sans attendre sa période. */
@Module
@InstallIn(SingletonComponent::class)
object WidgetModule {

    @Provides
    @IntoSet
    fun widgetLanguage(@ApplicationContext context: Context): LocaleChangeListener =
        LocaleChangeListener { MemoryWidget().updateAll(context) }
}
