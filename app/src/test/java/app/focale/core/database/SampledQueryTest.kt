package app.focale.core.database

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lecture échantillonnée d'une requête observée (compteurs d'avancement, étiquettes de l'onglet
 * Recherche), en temps virtuel : première valeur sans attendre, au plus une lecture par intervalle,
 * aucune perdue, fin de file lue aussitôt.
 */
class SampledQueryTest {

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private val milestones = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private var value = 0
    private var reads = 0
    private val seen = mutableListOf<Pair<Long, Int>>()

    /** Collecte la requête échantillonnée ; comme `createFlow`, un premier signal à l'abonnement. */
    private fun TestScope.observe() = launch {
        val signals = flow {
            emit(Unit)
            emitAll(changes)
        }
        sampledQuery(signals, 3.seconds, milestones, testScheduler.timeSource) {
            reads++
            value
        }.collect { seen += testScheduler.currentTime to it }
    }

    @Test
    fun `première valeur aussitôt, puis une lecture par intervalle au plus, qui couvre les changements de l'attente`() = runTest {
        val job = observe()
        runCurrent()
        assertEquals(listOf(0L to 0), seen)
        // Un lot toutes les 100 ms pendant 2 s.
        repeat(20) {
            value++
            changes.emit(Unit)
            advanceTimeBy(100)
        }
        advanceUntilIdle()
        assertEquals(listOf(0L to 0, 3_000L to 20), seen)
        assertEquals(2, reads)
        // Plus rien ne change : plus rien n'est lu.
        advanceTimeBy(10_000)
        assertEquals(2, reads)
        job.cancel()
    }

    @Test
    fun `la fin d'une file est lue aussitôt, sans attendre l'intervalle`() = runTest {
        val job = observe()
        runCurrent()
        value = 5
        changes.emit(Unit)
        advanceTimeBy(500)
        assertEquals(1, reads)
        milestones.emit(Unit)
        runCurrent()
        assertEquals(listOf(0L to 0, 500L to 5), seen)
        // Un changement juste après attend de nouveau l'intervalle, compté depuis la dernière lecture.
        value = 6
        changes.emit(Unit)
        advanceUntilIdle()
        assertEquals(listOf(0L to 0, 500L to 5, 3_500L to 6), seen)
        job.cancel()
    }
}
