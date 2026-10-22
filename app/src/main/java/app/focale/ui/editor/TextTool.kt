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

/**
 * Saisie ouverte : nouveau texte (si [id] est null) ou texte [id] en cours de modification.
 * [preview] est le texte tel qu'il s'affiche sur la photo pendant la saisie, contenu tapé
 * ([text], vide au départ pour un nouveau texte), position et réglages compris ; rien n'entre
 * dans l'historique avant la validation, qui forme une seule étape.
 */
@Immutable
data class TextDraft(val id: Long?, val initial: String, val preview: TextItem) {
    val x: Float get() = preview.x
    val y: Float get() = preview.y
    val text: String get() = preview.text
}

/**
 * Outil Texte : ajout, modification, déplacement, pincement et suppression des textes de
 * l'édition, avec leur sélection et la saisie ouverte. Chaque action forme une étape de
 * [history] ; un geste n'en forme qu'une ([beginGesture]), une saisie aussi, réglages changés
 * en cours de route compris ([confirm]).
 */
class TextController(private val history: EditHistory) {

    private val _options = MutableStateFlow(TextOptions())
    val options: StateFlow<TextOptions> = _options.asStateFlow()

    private val _selected = MutableStateFlow<Long?>(null)
    val selected: StateFlow<Long?> = _selected.asStateFlow()

    private val _draft = MutableStateFlow<TextDraft?>(null)
    val draft: StateFlow<TextDraft?> = _draft.asStateFlow()

    /** Couleur résolue de [TextOptions.ink] (le contrôleur ne connaît pas le thème : [setInk] la donne). */
    private var inkColor: Int = WHITE

    fun item(id: Long?): TextItem? = id?.let { wanted -> history.current.texts.firstOrNull { it.id == wanted } }

    /**
     * Textes à afficher : ceux de [texts], le texte en cours de saisie remplacé par son aperçu,
     * ou l'aperçu d'un nouveau texte ajouté au-dessus.
     */
    fun displayed(texts: List<TextItem>, draft: TextDraft? = _draft.value): List<TextItem> = when {
        draft == null -> texts
        draft.id == null -> texts + draft.preview
        else -> texts.map { if (it.id == draft.id) draft.preview else it }
    }

    /** Appui sur la photo hors des textes : saisie d'un nouveau texte à cet endroit ; pendant une saisie, la valide. */
    fun startNew(x: Float, y: Float) {
        if (_draft.value != null) {
            confirm()
            return
        }
        open(x, y)
    }

    /** Nouveau texte au centre de la zone visible (bouton « Ajouter », clavier, TalkBack), après la saisie en cours. */
    fun startNewCentered() {
        confirm()
        val crop = history.current.crop
        open((crop.left + crop.right) / 2f, (crop.top + crop.bottom) / 2f)
    }

    /** Appui sur un texte : sélection et saisie de son contenu ; pendant une autre saisie, la valide. */
    fun startEdit(id: Long) {
        val current = _draft.value
        if (current != null) {
            if (current.preview.id != id) confirm()
            return
        }
        val item = item(id) ?: return
        _selected.value = id
        _draft.value = TextDraft(id = id, initial = item.text, preview = item)
    }

    /** Contenu tapé : l'aperçu suit, sans étape d'historique. */
    fun type(value: String) {
        _draft.update { it?.copy(preview = it.preview.copy(text = value)) }
    }

    /**
     * Fin de la saisie, en une étape : un nouveau texte vide n'est pas ajouté, un texte existant
     * vidé est supprimé. [value] remplace le contenu tapé s'il est donné. Vrai si l'édition a
     * changé.
     */
    fun confirm(value: String? = null): Boolean {
        val draft = _draft.value ?: return false
        _draft.value = null
        val text = (value ?: draft.text).trim()
        val state = history.current
        when {
            draft.id == null -> {
                if (text.isEmpty()) return false
                history.commit(state.copy(texts = state.texts + draft.preview.copy(text = text)))
                _selected.value = draft.preview.id
            }
            text.isEmpty() -> delete(draft.id)
            else -> history.commit(state.withText(draft.id) { draft.preview.copy(text = text) })
        }
        return history.current != state
    }

    /** Abandon de la saisie : l'édition reste telle qu'avant. */
    fun cancel() {
        _draft.value = null
    }

    fun select(id: Long?) {
        _selected.value = id?.takeIf { item(it) != null }
    }

    /**
     * Début d'un glisser ou d'un pincement sur le texte [id] : il est sélectionné, une seule étape
     * d'historique. Le texte en cours de saisie, lui, bouge sans étape : il en formera une à la validation.
     */
    fun beginGesture(id: Long) {
        if (_draft.value?.preview?.id == id) return
        _selected.value = id
        history.remember()
    }

    /** Pendant un glisser : déplacement normalisé, centre gardé dans le recadrage. */
    fun move(id: Long, dx: Float, dy: Float) = transform(id, dx, dy, zoom = 1f, rotation = 0f)

    /** Pendant un pincement : déplacement, agrandissement et rotation (degrés, sens horaire). */
    fun transform(id: Long, dx: Float, dy: Float, zoom: Float, rotation: Float) {
        val state = history.current
        if (updateDraft(id) { TextMath.transform(it, dx, dy, zoom, rotation, state.crop) }) return
        history.update(state.withText(id) { TextMath.transform(it, dx, dy, zoom, rotation, state.crop) })
    }

    /** Corbeille : le texte sélectionné, ou celui en cours de saisie (un nouveau texte est abandonné). */
    fun deleteSelected() {
        val current = _draft.value
        if (current != null) {
            cancel()
            current.id?.let(::delete)
            return
        }
        _selected.value?.let(::delete)
    }

    fun delete(id: Long) {
        if (_draft.value?.preview?.id == id) cancel()
        val state = history.current
        if (state.texts.none { it.id == id }) return
        history.commit(state.copy(texts = state.texts.filterNot { it.id == id }))
        if (_selected.value == id) _selected.value = null
    }

    /** [color] : [ink] résolue dans le thème de l'éditeur. */
    fun setInk(ink: InkColor, color: Int) {
        _options.update { it.copy(ink = ink) }
        inkColor = color
        change { it.copy(color = color) }
    }

    fun setBackground(background: TextBackground) {
        _options.update { it.copy(background = background) }
        change { it.copy(background = background) }
    }

    fun setBold(bold: Boolean) {
        _options.update { it.copy(bold = bold) }
        change { it.copy(bold = bold) }
    }

    /** Début d'un réglage de taille au curseur : une étape d'historique pour tout le geste (aucune pendant une saisie). */
    fun beginSize() {
        if (_draft.value == null && item(_selected.value) != null) history.remember()
    }

    fun setSize(size: Float) {
        val value = size.coerceIn(TextItem.MIN_SIZE, TextItem.MAX_SIZE)
        _options.update { it.copy(size = value) }
        if (updateDraft { it.copy(size = value) }) return
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

    /** Nouvelle saisie en ([x], [y]), aux réglages courants ; son identité est réservée dès maintenant. */
    private fun open(x: Float, y: Float) {
        _selected.value = null
        val options = _options.value
        val preview = TextItem(
            id = history.newId(),
            text = "",
            x = x,
            y = y,
            color = inkColor,
            size = options.size,
            background = options.background,
            bold = options.bold,
        )
        _draft.value = TextDraft(id = null, initial = "", preview = preview)
    }

    /** Réglage appliqué au texte en cours de saisie, sinon au texte sélectionné (une étape). */
    private fun change(change: (TextItem) -> TextItem) {
        if (updateDraft(change = change)) return
        val id = _selected.value ?: return
        history.commit(history.current.withText(id, change))
    }

    /** Change l'aperçu de la saisie ouverte (du texte [id] s'il est donné) ; faux s'il n'y en a pas. */
    private fun updateDraft(id: Long? = null, change: (TextItem) -> TextItem): Boolean {
        val current = _draft.value ?: return false
        if (id != null && current.preview.id != id) return false
        _draft.value = current.copy(preview = change(current.preview))
        return true
    }

    private companion object {
        /** Couleur de [InkColor.WHITE], encre par défaut des textes. */
        const val WHITE = -0x1
    }
}

private fun EditState.withText(id: Long, change: (TextItem) -> TextItem): EditState =
    copy(texts = texts.map { if (it.id == id) change(it) else it })
