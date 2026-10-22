package app.focale.ui.collage

import app.focale.collage.CellFraming
import app.focale.collage.CollageAspect
import app.focale.collage.CollageBackground
import app.focale.collage.CollageLayout
import app.focale.collage.CollageLayouts
import app.focale.collage.CollageState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Collage en cours de réglage et son historique (annuler / rétablir), sans dépendance Android :
 * le ViewModel s'en sert, les tests aussi. Comme dans l'éditeur ([app.focale.ui.editor.EditHistory]),
 * une action ponctuelle (disposition, format, fond, échange) forme une étape ; un geste (cadrage,
 * curseur) une seule, créée à son début, puis suivie sans en créer d'autre.
 */
class CollageEditor(photos: List<Long>, private val limit: Int = HISTORY) {

    /** Dispositions proposées pour ce nombre de photos. */
    val layouts: List<CollageLayout> = CollageLayouts.forCount(photos.size)

    private val _state = MutableStateFlow(CollageState.initial(photos))
    val state: StateFlow<CollageState> = _state.asStateFlow()

    val current: CollageState get() = _state.value

    private val past = ArrayDeque<CollageState>()
    private val future = ArrayDeque<CollageState>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    fun selectLayout(id: String) {
        if (layouts.any { it.id == id }) commit(current.copy(layoutId = id))
    }

    fun setAspect(aspect: CollageAspect) = commit(current.copy(aspect = aspect))

    /** [color] : couleur résolue dans le thème au moment du choix (ARGB), celle de l'export. */
    fun setBackground(background: CollageBackground, color: Int) = commit(current.copy(background = background, backgroundColor = color))

    /** Échange les photos de deux cases ; chacune garde son cadrage. */
    fun swap(first: Int, second: Int) = commit(current.swapped(first, second))

    /** Début d'un geste (curseur, cadrage) : l'état actuel devient une étape. */
    fun beginGesture() {
        past.addLast(current)
        if (past.size > limit) past.removeFirst()
        future.clear()
        updateFlags()
    }

    fun setSpacing(spacing: Float) {
        _state.value = current.copy(spacing = spacing.coerceIn(0f, CollageState.MAX_SPACING))
    }

    fun setCorners(corners: Float) {
        _state.value = current.copy(corners = corners.coerceIn(0f, CollageState.MAX_CORNERS))
    }

    /**
     * Pendant un geste dans la case [cell] ([cellWidth] × [cellHeight], photo de
     * [imageWidth] × [imageHeight]) : glisser de ([panX], [panY]) puis agrandissement de [zoom]
     * autour de ([focusX], [focusY]), en pixels de la case.
     */
    fun frame(
        cell: Int,
        panX: Float,
        panY: Float,
        zoom: Float,
        focusX: Float,
        focusY: Float,
        cellWidth: Float,
        cellHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ) {
        val photo = current.photos.getOrNull(cell) ?: return
        val panned = CellFraming.pan(current.frame(photo), panX, panY, cellWidth, cellHeight, imageWidth, imageHeight)
        val framed = if (zoom == 1f) {
            panned
        } else {
            CellFraming.zoom(panned, zoom, focusX, focusY, cellWidth, cellHeight, imageWidth, imageHeight)
        }
        _state.value = current.copy(frames = current.frames + (photo to framed))
    }

    /** Agrandit ou réduit la photo de [cell] autour de son centre, en une étape (TalkBack, clavier). */
    fun zoomStep(cell: Int, factor: Float, cellWidth: Float, cellHeight: Float, imageWidth: Float, imageHeight: Float) {
        val photo = current.photos.getOrNull(cell) ?: return
        val zoomed = CellFraming.zoom(current.frame(photo), factor, cellWidth / 2f, cellHeight / 2f, cellWidth, cellHeight, imageWidth, imageHeight)
        commit(current.copy(frames = current.frames + (photo to zoomed)))
    }

    /** Photo de [cell] de nouveau centrée, sans agrandissement. */
    fun resetFrame(cell: Int) {
        val photo = current.photos.getOrNull(cell) ?: return
        commit(current.copy(frames = current.frames - photo))
    }

    fun undo(): Boolean {
        val previous = past.removeLastOrNull() ?: return false
        future.addLast(current)
        _state.value = previous
        updateFlags()
        return true
    }

    fun redo(): Boolean {
        val next = future.removeLastOrNull() ?: return false
        past.addLast(current)
        _state.value = next
        updateFlags()
        return true
    }

    private fun commit(newState: CollageState) {
        if (newState == current) return
        beginGesture()
        _state.value = newState
    }

    private fun updateFlags() {
        _canUndo.value = past.isNotEmpty()
        _canRedo.value = future.isNotEmpty()
    }

    private companion object {
        const val HISTORY = 50
    }
}
