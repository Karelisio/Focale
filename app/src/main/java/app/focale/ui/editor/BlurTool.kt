package app.focale.ui.editor

import androidx.compose.runtime.Immutable
import app.focale.edit.BlurArea
import app.focale.edit.BlurRegion
import app.focale.edit.BlurShape
import app.focale.edit.BlurStyle
import app.focale.edit.EditState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Réglages de l'outil Flouter, hors historique : forme des prochaines zones, et style et
 * intensité des prochaines (et de la zone sélectionnée, quand on les change).
 *
 * @param brushSize épaisseur à l'écran (dp) du pinceau au moment du tracé ; la zone la convertit
 *   en fraction de l'image, comme un trait de dessin.
 */
@Immutable
data class BlurOptions(
    val shape: BlurShape = BlurShape.RECTANGLE,
    val style: BlurStyle = BlurStyle.BLUR,
    val intensity: Float = DEFAULT_INTENSITY,
    val brushSize: Float = DEFAULT_BRUSH,
) {
    companion object {
        const val DEFAULT_INTENSITY = 0.5f
        const val MIN_BRUSH = 12f
        const val MAX_BRUSH = 64f
        const val DEFAULT_BRUSH = 28f
    }
}

/**
 * Outil Flouter : ajout, sélection, déplacement et suppression des zones floutées, style et
 * intensité. Chaque action forme une étape de [history] ; un déplacement n'en forme qu'une
 * ([beginMove]).
 */
class BlurController(private val history: EditHistory) {

    private val _options = MutableStateFlow(BlurOptions())
    val options: StateFlow<BlurOptions> = _options.asStateFlow()

    private val _selected = MutableStateFlow<Long?>(null)
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    fun region(id: Long?): BlurRegion? = id?.let { wanted -> history.current.blurs.firstOrNull { it.id == wanted } }

    /** Nouvelle zone, au style et à l'intensité des réglages. */
    fun add(area: BlurArea) = addAll(listOf(area))

    /** Plusieurs zones en une seule étape d'historique (visages détectés). */
    fun addAll(areas: List<BlurArea>) {
        if (areas.isEmpty()) return
        val options = _options.value
        val state = history.current
        history.commit(state.copy(blurs = state.blurs + areas.map { BlurRegion(history.newId(), it, options.style, options.intensity) }))
    }

    fun select(id: Long?) {
        _selected.value = id?.takeIf { region(it) != null }
    }

    /** Début du déplacement de la zone [id] : une seule étape d'historique pour le geste. */
    fun beginMove(id: Long) {
        _selected.value = id
        history.remember()
    }

    /** Pendant un déplacement : centre de la zone gardé sur la photo. */
    fun move(id: Long, dx: Float, dy: Float) {
        history.update(
            history.current.withBlur(id) { region ->
                val area = region.area
                region.translate((area.anchorX + dx).coerceIn(0f, 1f) - area.anchorX, (area.anchorY + dy).coerceIn(0f, 1f) - area.anchorY)
            },
        )
    }

    fun deleteSelected() {
        _selected.value?.let(::delete)
    }

    fun delete(id: Long) {
        val state = history.current
        if (state.blurs.none { it.id == id }) return
        history.commit(state.copy(blurs = state.blurs.filterNot { it.id == id }))
        if (_selected.value == id) _selected.value = null
    }

    fun setShape(shape: BlurShape) = _options.update { it.copy(shape = shape) }

    fun setStyle(style: BlurStyle) {
        _options.update { it.copy(style = style) }
        val id = _selected.value ?: return
        history.commit(history.current.withBlur(id) { it.copy(style = style) })
    }

    /** Début d'un réglage d'intensité au curseur : une étape d'historique pour tout le geste. */
    fun beginIntensity() {
        if (region(_selected.value) != null) history.remember()
    }

    fun setIntensity(intensity: Float) {
        val value = intensity.coerceIn(0f, 1f)
        _options.update { it.copy(intensity = value) }
        val id = _selected.value ?: return
        history.update(history.current.withBlur(id) { it.copy(intensity = value) })
    }

    fun setBrushSize(size: Float) = _options.update { it.copy(brushSize = size.coerceIn(BlurOptions.MIN_BRUSH, BlurOptions.MAX_BRUSH)) }

    /** Après annuler ou rétablir : la sélection d'une zone disparue est oubliée. */
    fun sync() {
        if (region(_selected.value) == null) _selected.value = null
    }

    fun clear() {
        _selected.value = null
    }
}

private fun EditState.withBlur(id: Long, change: (BlurRegion) -> BlurRegion): EditState =
    copy(blurs = blurs.map { if (it.id == id) change(it) else it })
