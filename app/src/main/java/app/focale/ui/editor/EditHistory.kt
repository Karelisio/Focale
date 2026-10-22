package app.focale.ui.editor

import app.focale.edit.EditState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * État de l'édition et son historique (annuler / rétablir), sans dépendance Android : le
 * ViewModel et les outils Texte et Flouter s'en servent, les tests aussi.
 *
 * Une action ponctuelle passe par [commit] ; un geste (glisser, pincer, curseur) crée son étape
 * au début ([remember]), puis suit le doigt par [update] sans en créer d'autre.
 */
class EditHistory(private val limit: Int = HISTORY) {

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    val current: EditState get() = _state.value

    private val past = ArrayDeque<EditState>()
    private val future = ArrayDeque<EditState>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private var lastId = 0L

    /** Identité d'un nouveau texte ou d'une nouvelle zone, jamais réutilisée (même après annulation). */
    fun newId(): Long = ++lastId

    /** Nouvel état, précédé d'une étape d'historique s'il diffère de l'actuel. */
    fun commit(newState: EditState) {
        if (newState == current) return
        remember()
        _state.value = newState
    }

    /** Début d'un geste : l'état actuel devient une étape. */
    fun remember() {
        past.addLast(current)
        if (past.size > limit) past.removeFirst()
        future.clear()
        updateFlags()
    }

    /** Pendant un geste : nouvel état sans étape d'historique. */
    fun update(newState: EditState) {
        _state.value = newState
    }

    /** Revient à l'étape précédente ; faux s'il n'y en a pas. */
    fun undo(): Boolean {
        val previous = past.removeLastOrNull() ?: return false
        future.addLast(current)
        _state.value = previous
        updateFlags()
        return true
    }

    /** Rétablit l'étape annulée ; faux s'il n'y en a pas. */
    fun redo(): Boolean {
        val next = future.removeLastOrNull() ?: return false
        past.addLast(current)
        _state.value = next
        updateFlags()
        return true
    }

    private fun updateFlags() {
        _canUndo.value = past.isNotEmpty()
        _canRedo.value = future.isNotEmpty()
    }

    private companion object {
        const val HISTORY = 50
    }
}
