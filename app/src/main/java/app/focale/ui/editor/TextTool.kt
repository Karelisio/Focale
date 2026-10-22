package app.focale.ui.editor

import androidx.compose.runtime.Immutable
import app.focale.edit.EditState
import app.focale.edit.TextBackground
import app.focale.edit.TextItem
import app.focale.edit.TextMath
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Réglages de l'outil Texte, hors historique : ceux du prochain texte, et les derniers choisis
 * (un réglage changé pendant qu'un texte est sélectionné s'applique aussi à lui).
 *
 * @param size corps en fraction du petit côté de l'image ([TextItem.size]).
 */
@Immutable
data class TextOptions(
    val ink: InkColor = InkColor.WHITE,
    val size: Float = TextItem.DEFAULT_SIZE,
    val background: TextBackground = TextBackground.OUTLINE,
    val bold: Boolean = false,
) {
    companion object {
        /**
         * Position (0..1) du curseur de taille : échelle en racine carrée, pour que les petits
         * corps, les plus courants, ne se tassent pas au début du curseur.
         */
        fun sliderPosition(size: Float): Float {
            val low = sqrt(TextItem.MIN_SIZE)
            val high = sqrt(TextItem.MAX_SIZE)
            return ((sqrt(size.coerceIn(TextItem.MIN_SIZE, TextItem.MAX_SIZE)) - low) / (high - low)).coerceIn(0f, 1f)
        }

        fun sizeAt(position: Float): Float {
            val low = sqrt(TextItem.MIN_SIZE)
            val high = sqrt(TextItem.MAX_SIZE)
            val root = low + (high - low) * position.coerceIn(0f, 1f)
            return (root * root).coerceIn(TextItem.MIN_SIZE, TextItem.MAX_SIZE)
        }
    }
}

/** Saisie ouverte : nouveau texte à poser en ([x], [y]) (si [id] est null), ou texte [id] à modifier. */
@Immutable
data class TextDraft(val id: Long?, val x: Float, val y: Float, val initial: String)

/**
 * Outil Texte : ajout, modification, déplacement, pincement et suppression des textes de
 * l'édition, avec leur sélection et la saisie ouverte. Chaque action forme une étape de
 * [history] ; un geste n'en forme qu'une ([beginGesture]).
 */
class TextController(private val history: EditHistory) {

    private val _options = MutableStateFlow(TextOptions())
    val options: StateFlow<TextOptions> = _options.asStateFlow()

    private val _selected = MutableStateFlow<Long?>(null)
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    private val _draft = MutableStateFlow<TextDraft?>(null)
    val draft: StateFlow<TextDraft?> = _draft.asStateFlow()

    fun item(id: Long?): TextItem? = id?.let { wanted -> history.current.texts.firstOrNull { it.id == wanted } }

    /** Appui sur la photo hors des textes : saisie d'un nouveau texte à cet endroit. */
    fun startNew(x: Float, y: Float) {
        _selected.value = null
        _draft.value = TextDraft(id = null, x = x, y = y, initial = "")
    }

    /** Nouveau texte au centre de la zone visible (bouton « Ajouter », clavier, TalkBack). */
    fun startNewCentered() {
        val crop = history.current.crop
        startNew((crop.left + crop.right) / 2f, (crop.top + crop.bottom) / 2f)
    }

    /** Appui sur un texte : sélection et saisie de son contenu. */
    fun startEdit(id: Long) {
        val item = item(id) ?: return
        _selected.value = id
        _draft.value = TextDraft(id = id, x = item.x, y = item.y, initial = item.text)
    }

    /**
     * Fin de la saisie : un nouveau texte vide n'est pas ajouté, un texte existant vidé est
     * supprimé. [color] : couleur résolue de [TextOptions.ink] pour un nouveau texte.
     */
    fun confirm(value: String, color: Int) {
        val draft = _draft.value ?: return
        _draft.value = null
        val text = value.trim()
        val state = history.current
        when {
            draft.id == null -> {
                if (text.isEmpty()) return
                val options = _options.value
                val item = TextItem(
                    id = history.newId(),
                    text = text,
                    x = draft.x,
                    y = draft.y,
                    color = color,
                    size = options.size,
                    background = options.background,
                    bold = options.bold,
                )
                history.commit(state.copy(texts = state.texts + item))
                _selected.value = item.id
            }
            text.isEmpty() -> delete(draft.id)
            else -> history.commit(state.withText(draft.id) { it.copy(text = text) })
        }
    }

    fun cancel() {
        _draft.value = null
    }

    fun select(id: Long?) {
        _selected.value = id?.takeIf { item(it) != null }
    }

    /** Début d'un glisser ou d'un pincement sur le texte [id] : il est sélectionné, une seule étape d'historique. */
    fun beginGesture(id: Long) {
        _selected.value = id
        history.remember()
    }

    /** Pendant un glisser : déplacement normalisé, centre gardé dans le recadrage. */
    fun move(id: Long, dx: Float, dy: Float) = transform(id, dx, dy, zoom = 1f, rotation = 0f)

    /** Pendant un pincement : déplacement, agrandissement et rotation (degrés, sens horaire). */
    fun transform(id: Long, dx: Float, dy: Float, zoom: Float, rotation: Float) {
        val state = history.current
        history.update(state.withText(id) { TextMath.transform(it, dx, dy, zoom, rotation, state.crop) })
    }

    fun deleteSelected() {
        _selected.value?.let(::delete)
    }

    fun delete(id: Long) {
        val state = history.current
        if (state.texts.none { it.id == id }) return
        history.commit(state.copy(texts = state.texts.filterNot { it.id == id }))
        if (_selected.value == id) _selected.value = null
    }

    /** [color] : [ink] résolue dans le thème de l'éditeur. */
    fun setInk(ink: InkColor, color: Int) {
        _options.update { it.copy(ink = ink) }
        commitSelected { it.copy(color = color) }
    }

    fun setBackground(background: TextBackground) {
        _options.update { it.copy(background = background) }
        commitSelected { it.copy(background = background) }
    }

    fun setBold(bold: Boolean) {
        _options.update { it.copy(bold = bold) }
        commitSelected { it.copy(bold = bold) }
    }

    /** Début d'un réglage de taille au curseur : une étape d'historique pour tout le geste. */
    fun beginSize() {
        if (item(_selected.value) != null) history.remember()
    }

    fun setSize(size: Float) {
        val value = size.coerceIn(TextItem.MIN_SIZE, TextItem.MAX_SIZE)
        _options.update { it.copy(size = value) }
        val id = _selected.value ?: return
        history.update(history.current.withText(id) { it.copy(size = value) })
    }

    /** Après annuler ou rétablir : la sélection et la saisie d'un texte disparu sont oubliées. */
    fun sync() {
        if (item(_selected.value) == null) _selected.value = null
        _draft.value?.id?.let { if (item(it) == null) _draft.value = null }
    }

    fun clear() {
        _selected.value = null
        _draft.value = null
    }

    private fun commitSelected(change: (TextItem) -> TextItem) {
        val id = _selected.value ?: return
        history.commit(history.current.withText(id, change))
    }
}

private fun EditState.withText(id: Long, change: (TextItem) -> TextItem): EditState =
    copy(texts = texts.map { if (it.id == id) change(it) else it })
