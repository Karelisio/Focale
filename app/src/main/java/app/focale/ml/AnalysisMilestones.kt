package app.focale.ml

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Étapes marquantes d'un passage de l'analyse : une file vidée (analyse principale, texte,
 * visages, empreintes) ou le passage terminé. Les compteurs d'avancement et les étiquettes de
 * l'onglet Recherche, relus au plus toutes les quelques secondes (`sampledQuery`), le sont
 * aussitôt : la fin s'affiche sans attendre.
 */
@Singleton
class AnalysisMilestones @Inject constructor() {
    private val reached = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val events: Flow<Unit> = reached.asSharedFlow()

    fun reach() {
        reached.tryEmit(Unit)
    }
}
