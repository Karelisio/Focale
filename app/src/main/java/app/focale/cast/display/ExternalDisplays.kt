package app.focale.cast.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Écran secondaire propre à une présentation (HDMI, USB-C, Miracast, « Caster l'écran »). */
@Immutable
data class ExternalDisplay(val id: Int, val name: String)

/**
 * Écrans de présentation branchés ou connectés, suivis par `DisplayManager` tant que l'interface
 * les observe (arrivée, départ, changement de nom ou d'état).
 */
@Singleton
class ExternalDisplays @Inject constructor(@ApplicationContext context: Context) {

    private val manager: DisplayManager? = context.getSystemService(DisplayManager::class.java)

    val displays: Flow<List<ExternalDisplay>> = callbackFlow {
        val manager = manager
        if (manager == null) {
            trySend(emptyList())
            awaitClose()
            return@callbackFlow
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                trySend(current())
            }

            override fun onDisplayRemoved(displayId: Int) {
                trySend(current())
            }

            override fun onDisplayChanged(displayId: Int) {
                trySend(current())
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        trySend(current())
        awaitClose { manager.unregisterDisplayListener(listener) }
    }.distinctUntilChanged()

    /** Écrans de présentation disponibles, dans l'ordre du système. */
    fun current(): List<ExternalDisplay> {
        val displays = manager?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).orEmpty()
        return PresentationDisplays.eligible(
            displays.map { display ->
                PresentationDisplays.Candidate(
                    id = display.displayId,
                    name = display.name.orEmpty(),
                    presentation = display.flags and Display.FLAG_PRESENTATION != 0,
                    private = display.flags and Display.FLAG_PRIVATE != 0,
                    on = display.state != Display.STATE_OFF,
                )
            },
        )
    }

    /** L'écran [id] s'il est encore là. */
    fun display(id: Int): Display? = manager?.getDisplay(id)
}

/** Choix de l'écran de présentation, logique pure. */
internal object PresentationDisplays {

    /** Écran vu par `DisplayManager`. */
    data class Candidate(val id: Int, val name: String, val presentation: Boolean, val private: Boolean, val on: Boolean)

    /**
     * Écrans propres à une présentation : ni l'écran de l'appareil, ni un écran privé (celui d'une
     * autre application), ni un écran éteint ; noms sans espaces de bord.
     */
    fun eligible(candidates: List<Candidate>): List<ExternalDisplay> = candidates
        .filter { it.id != Display.DEFAULT_DISPLAY && it.presentation && !it.private && it.on }
        .distinctBy { it.id }
        .map { ExternalDisplay(it.id, it.name.trim()) }

    /**
     * Écran où montrer la visionneuse : le premier des [available] que l'utilisateur n'a pas
     * arrêté ([declined]) ; aucun s'il les a tous arrêtés.
     */
    fun choose(available: List<ExternalDisplay>, declined: Set<Int>): ExternalDisplay? =
        available.firstOrNull { it.id !in declined }
}
