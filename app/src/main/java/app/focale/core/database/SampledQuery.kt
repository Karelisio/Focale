package app.focale.core.database

import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Résultat de [query], relu à chaque signal de [changes] (changement d'une table qu'elle lit,
 * `InvalidationTracker.createFlow`) mais au plus une fois par [interval] : pendant l'analyse, un lot
 * réécrit ses tables toutes les quelques secondes, et une requête qui parcourt toute la
 * bibliothèque (compteurs d'avancement, étiquettes de l'onglet Recherche) n'a pas à suivre chacun.
 *
 * La première valeur est lue sans attendre ; un changement survenu pendant l'attente est couvert
 * par la lecture qui la termine (une seule). Un signal de [immediately] (fin d'une file de
 * l'analyse, `AnalysisMilestones`) relit aussitôt, attente comprise : la fin s'affiche sans délai.
 */
fun <T> sampledQuery(
    changes: Flow<*>,
    interval: Duration,
    immediately: Flow<*> = emptyFlow<Unit>(),
    timeSource: TimeSource = TimeSource.Monotonic,
    query: suspend () -> T,
): Flow<T> = channelFlow {
    val changed = Channel<Unit>(Channel.CONFLATED)
    val urgent = Channel<Unit>(Channel.CONFLATED)
    launch { changes.collect { changed.send(Unit) } }
    launch {
        immediately.collect {
            urgent.send(Unit)
            changed.send(Unit)
        }
    }
    var last: TimeMark? = null
    while (true) {
        changed.receive()
        val remaining = last?.let { interval - it.elapsedNow() }
        if (remaining != null && remaining.isPositive()) withTimeoutOrNull(remaining) { urgent.receive() }
        // Lu par cette requête : ce qui a changé pendant l'attente.
        changed.tryReceive()
        urgent.tryReceive()
        last = timeSource.markNow()
        send(query())
    }
}
