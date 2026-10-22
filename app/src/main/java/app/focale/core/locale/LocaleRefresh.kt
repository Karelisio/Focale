package app.focale.core.locale

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.focale.core.di.ApplicationScope
import dagger.Lazy
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ce qui suit la langue de Focale hors des activités, recréées seules : canaux de notification
 * (noms affichés dans les réglages d'Android), widget. Fourni par multiliaison (`@IntoSet`).
 */
fun interface LocaleChangeListener {
    suspend fun onLocaleChanged()
}

/** Prévient les [LocaleChangeListener] d'un changement de langue, de Focale ou du système. */
@Singleton
class LocaleRefresh @Inject constructor(
    private val locales: AppLocales,
    private val listeners: Lazy<Set<@JvmSuppressWildcards LocaleChangeListener>>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** Langue déjà appliquée dans ce processus : un choix dans Focale est aussi annoncé par le système. */
    private var applied: String? = null

    /**
     * Applique la langue courante, en arrière-plan ; rien si elle l'est déjà. Appelé après un choix
     * dans les réglages de Focale et à l'annonce du système ([LocaleChangeReceiver]).
     */
    fun refresh(): Job = scope.launch {
        mutex.withLock {
            val tag = locales.locale.toLanguageTag()
            if (applied == tag) return@withLock
            applied = tag
            listeners.get().forEach { listener ->
                try {
                    listener.onLocaleChanged()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Changement de langue non appliqué", error)
                }
            }
        }
    }

    private companion object {
        const val TAG = "LocaleRefresh"
    }
}

/**
 * Langue du système changée, ou celle de Focale depuis les réglages d'Android 13+ (le système
 * l'annonce alors à l'application seule) : reçu même Focale fermée.
 */
class LocaleChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return
        val refresh = EntryPointAccessors.fromApplication(context, LocaleEntryPoint::class.java).localeRefresh()
        val pending = goAsync()
        refresh.refresh().invokeOnCompletion { pending.finish() }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface LocaleEntryPoint {
        fun localeRefresh(): LocaleRefresh
    }
}
