package app.focale.core.mediastore

import android.content.IntentSender
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pont entre le code métier et les boîtes de dialogue de consentement système
 * (suppression, mise à la corbeille, modification d'un média d'une autre application).
 *
 * Le code métier suspend sur [request] ; `MainActivity` collecte [requests], lance
 * l'`IntentSender` puis appelle [complete]. La demande en cours est conservée ici et
 * non dans l'activité, pour survivre à une recréation (rotation) pendant le dialogue.
 */
@Singleton
class ConsentRequester @Inject constructor() {

    private val mutex = Mutex()
    private val channel = Channel<IntentSender>(Channel.BUFFERED)
    private var pending: CompletableDeferred<Boolean>? = null

    val requests: Flow<IntentSender> = channel.receiveAsFlow()

    /** Demande le consentement ; retourne vrai si l'utilisateur a accepté. */
    suspend fun request(intentSender: IntentSender): Boolean = mutex.withLock {
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        channel.send(intentSender)
        try {
            deferred.await()
        } finally {
            pending = null
        }
    }

    /** Résultat du dialogue système. */
    fun complete(granted: Boolean) {
        pending?.complete(granted)
    }
}
