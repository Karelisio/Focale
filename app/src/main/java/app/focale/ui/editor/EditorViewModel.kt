package app.focale.ui.editor

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.focale.core.di.DefaultDispatcher
import app.focale.core.image.SeedColorExtractor
import app.focale.core.model.MediaItem
import app.focale.data.ImageEditor
import app.focale.data.MediaRepository
import app.focale.data.OperationResult
import app.focale.edit.Adjustment
import app.focale.edit.CropAspect
import app.focale.edit.CropMath
import app.focale.edit.DrawTool
import app.focale.edit.DrawingStroke
import app.focale.edit.EditRenderer
import app.focale.edit.EditState
import app.focale.edit.FilterPreset
import app.focale.edit.NormRect
import app.focale.edit.SaveMode
import app.focale.edit.StraightenMath
import app.focale.ui.navigation.EditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EditorTool { CROP, FILTERS, ADJUST, DRAW }

/** Aperçu géométrique et l'orientation pour laquelle il a été rendu. */
data class EditorPreview(val image: ImageBitmap, val quarterTurns: Int, val flipHorizontal: Boolean) {
    /** Rendu en arrière-plan, l'aperçu suit l'état avec un léger retard après une rotation. */
    fun matches(state: EditState): Boolean = quarterTurns == state.quarterTurns && flipHorizontal == state.flipHorizontal
}

/** Résultat d'un enregistrement, consommé par l'écran. */
sealed interface EditorEvent {
    data class Saved(val mode: SaveMode) : EditorEvent
    data object Failed : EditorEvent
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val editor: ImageEditor,
    private val seedExtractor: SeedColorExtractor,
    @DefaultDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val mediaId = savedStateHandle.toRoute<EditorRoute>().mediaId

    private val _item = MutableStateFlow<MediaItem?>(null)
    val item: StateFlow<MediaItem?> = _item.asStateFlow()

    /**
     * Aperçu géométrique : rotation et miroir appliqués, sans redressement (appliqué à
     * l'affichage, instantané), recadrage, couleur ni dessin.
     */
    private val _preview = MutableStateFlow<EditorPreview?>(null)
    val preview: StateFlow<EditorPreview?> = _preview.asStateFlow()
    private var previewJob: Job? = null

    /** Aperçu de l'original (comparaison avant / après). */
    private var base: Bitmap? = null

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    private val _tool = MutableStateFlow(EditorTool.CROP)
    val tool: StateFlow<EditorTool> = _tool.asStateFlow()

    private val _adjustment = MutableStateFlow(Adjustment.BRIGHTNESS)
    val adjustment: StateFlow<Adjustment> = _adjustment.asStateFlow()

    private val _drawing = MutableStateFlow(DrawingOptions())
    val drawing: StateFlow<DrawingOptions> = _drawing.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Redressement en cours (règle ou deux doigts) : grille fine sur l'aperçu. */
    private val _straightening = MutableStateFlow(false)
    val straightening: StateFlow<Boolean> = _straightening.asStateFlow()

    /**
     * Recadrage voulu, d'où se déduit l'affiché à chaque angle ([StraightenMath.fit]) : le
     * cadre rapetisse en redressant, puis retrouve sa taille en revenant vers 0°. Oublié dès
     * que le recadrage change autrement (geste, annulation…), puis retrouvé d'après l'état.
     */
    private var straightenReference: NormRect? = null

    private val _seed = MutableStateFlow<Color?>(null)
    val seed: StateFlow<Color?> = _seed.asStateFlow()

    private val past = ArrayDeque<EditState>()
    private val future = ArrayDeque<EditState>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val events = Channel<EditorEvent>(Channel.BUFFERED)
    val eventFlow: Flow<EditorEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val media = mediaRepository.get(mediaId) ?: return@launch events.send(EditorEvent.Failed)
            _item.value = media
            _seed.value = seedExtractor.seedFor(media)
            val loaded = runCatching { editor.loadPreview(media) }.getOrNull()
                ?: return@launch events.send(EditorEvent.Failed)
            base = loaded
            refreshPreview()
        }
    }

    val canReplace: Boolean get() = _item.value?.let(editor::canReplace) == true

    val hasChanges: Boolean get() = !_state.value.isIdentity

    fun selectTool(tool: EditorTool) {
        _tool.value = tool
    }

    fun selectAdjustment(adjustment: Adjustment) {
        _adjustment.value = adjustment
    }

    fun rotate() {
        val current = _state.value
        // Rotation et miroir ne commutent pas : avec miroir, un quart de tour horaire de
        // l'image affichée correspond à un quart de tour antihoraire de l'original.
        val turns = if (current.flipHorizontal) current.quarterTurns - 1 else current.quarterTurns + 1
        // Recadrage et traits, exprimés dans le repère affiché, tournent avec lui ; le
        // redressement, rotation lui aussi, ne change pas.
        val rotated = current.copy(
            quarterTurns = Math.floorMod(turns, 4),
            crop = CropMath.rotateClockwise(current.crop),
            strokes = current.strokes.map(DrawingStroke::rotateClockwise),
        )
        straightenReference = straightenReference?.let(CropMath::rotateClockwise)
        commit(if (current.aspect == CropAspect.FREE) rotated else refit(rotated))
        refreshPreview()
    }

    fun flip() {
        val current = _state.value
        // Dans un miroir, le redressement tourne dans l'autre sens.
        straightenReference = straightenReference?.let(CropMath::flipHorizontal)
        commit(
            current.copy(
                flipHorizontal = !current.flipHorizontal,
                straighten = StraightenMath.clamp(-current.straighten),
                crop = CropMath.flipHorizontal(current.crop),
                strokes = current.strokes.map(DrawingStroke::flipHorizontal),
            ),
        )
        refreshPreview()
    }

    fun setAspect(aspect: CropAspect) {
        commit(refit(_state.value.copy(aspect = aspect)))
    }

    /** Pendant un geste de recadrage : pas d'entrée d'historique à chaque mouvement. */
    fun dragCrop(rect: NormRect) {
        straightenReference = null
        _state.value = _state.value.copy(crop = rect)
    }

    /** Début d'un redressement à la règle ou à deux doigts : une seule étape d'historique. */
    fun beginStraighten() {
        remember()
        _straightening.value = true
    }

    /** Pendant un redressement : angle et recadrage suivent, sans entrée d'historique. */
    fun straighten(angle: Float) {
        _state.value = straightened(_state.value, angle)
    }

    fun endStraighten() {
        _straightening.value = false
    }

    fun resetStraighten() = commit(straightened(_state.value, 0f))

    fun beginGesture() = remember()

    fun setFilter(filter: FilterPreset) = commit(_state.value.copy(filter = filter))

    fun setAdjustmentValue(value: Float) {
        _state.value = _state.value.with(_adjustment.value, value)
    }

    fun resetAdjustment(adjustment: Adjustment) = commit(_state.value.with(adjustment, 0f))

    fun selectDrawTool(tool: DrawTool) = _drawing.update { it.copy(tool = tool) }

    fun selectInk(ink: InkColor) = _drawing.update { it.copy(ink = ink) }

    fun setDrawSize(size: Float) = _drawing.update { it.copy(size = size.coerceIn(DrawingOptions.MIN_SIZE, DrawingOptions.MAX_SIZE)) }

    /** Chaque trait terminé forme une étape d'historique. */
    fun addStroke(stroke: DrawingStroke) = commit(_state.value.copy(strokes = _state.value.strokes + stroke))

    /**
     * Gomme : comme pour un recadrage, l'étape d'historique est créée au début du geste
     * ([beginGesture], au premier trait touché), qui peut en effacer plusieurs.
     */
    fun eraseStroke(stroke: DrawingStroke) {
        _state.value = _state.value.copy(strokes = _state.value.strokes.filterNot { it === stroke })
    }

    fun undo() {
        val previous = past.removeLastOrNull() ?: return
        future.addLast(_state.value)
        applyHistory(previous)
    }

    fun redo() {
        val next = future.removeLastOrNull() ?: return
        past.addLast(_state.value)
        applyHistory(next)
    }

    fun resetAll() {
        straightenReference = null
        commit(EditState())
    }

    fun save(mode: SaveMode) {
        val media = _item.value ?: return
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            val result = editor.save(media, _state.value, mode)
            _saving.value = false
            events.send(
                when (result) {
                    is OperationResult.Success -> EditorEvent.Saved(mode)
                    OperationResult.Cancelled -> return@launch
                    else -> EditorEvent.Failed
                },
            )
        }
    }

    /** Rapport largeur / hauteur (pixels) de l'image après rotation. */
    fun previewRatio(): Float = _preview.value?.image?.let { it.width.toFloat() / it.height } ?: 1f

    /** Rapport normalisé imposé par le format courant, null si libre. */
    fun cropRatio(): Float? {
        val preview = _preview.value?.image ?: return null
        return CropMath.normalizedRatio(_state.value.aspect, preview.width, preview.height, preview.width.toFloat() / preview.height)
    }

    /** Cadre automatique du format de [state], ramené dans l'image redressée, et retenu comme recadrage voulu. */
    private fun refit(state: EditState): EditState {
        val (width, height) = frameSize(state) ?: return state
        val format = formatFit(state, width, height)
        straightenReference = format
        return state.copy(crop = StraightenMath.fit(format, state.straighten, width.toFloat(), height.toFloat()))
    }

    /** [state] redressé de [angle] degrés, recadrage déduit du recadrage voulu. */
    private fun straightened(state: EditState, angle: Float): EditState {
        val (width, height) = frameSize(state) ?: return state
        val reference = straightenReference ?: referenceFor(state, width, height).also { straightenReference = it }
        val clamped = StraightenMath.clamp(angle)
        return state.copy(straighten = clamped, crop = StraightenMath.fit(reference, clamped, width.toFloat(), height.toFloat()))
    }

    /**
     * Recadrage voulu retrouvé d'après [state] : le cadre automatique du format (l'image
     * entière en format libre) si le recadrage affiché en découle encore, sinon l'affiché.
     */
    private fun referenceFor(state: EditState, width: Int, height: Int): NormRect {
        val automatic = formatFit(state, width, height)
        val shown = StraightenMath.fit(automatic, state.straighten, width.toFloat(), height.toFloat())
        val crop = state.crop
        val same = abs(crop.left - shown.left) < EPSILON && abs(crop.top - shown.top) < EPSILON &&
            abs(crop.right - shown.right) < EPSILON && abs(crop.bottom - shown.bottom) < EPSILON
        return if (same) automatic else crop
    }

    /** Plus grand cadre centré du format de [state], sans redressement. */
    private fun formatFit(state: EditState, width: Int, height: Int): NormRect =
        CropMath.fit(CropMath.normalizedRatio(state.aspect, width, height, width.toFloat() / height))

    /** Dimensions (px de l'aperçu) de l'image tournée de [state], celles de son cadre ; null avant le chargement. */
    private fun frameSize(state: EditState): Pair<Int, Int>? {
        val source = base ?: return null
        return if (state.quarterTurns % 2 == 1) source.height to source.width else source.width to source.height
    }

    private fun commit(newState: EditState) {
        if (newState == _state.value) return
        remember()
        _state.value = newState
    }

    private fun remember() {
        past.addLast(_state.value)
        if (past.size > HISTORY) past.removeFirst()
        future.clear()
        updateHistoryFlags()
    }

    private fun applyHistory(state: EditState) {
        val geometryChanged = state.quarterTurns != _state.value.quarterTurns || state.flipHorizontal != _state.value.flipHorizontal
        straightenReference = null
        _state.value = state
        updateHistoryFlags()
        if (geometryChanged) refreshPreview()
    }

    private fun updateHistoryFlags() {
        _canUndo.value = past.isNotEmpty()
        _canRedo.value = future.isNotEmpty()
    }

    private fun refreshPreview() {
        val source = base ?: return
        val state = _state.value
        // Seul le dernier rendu lancé est publié, même si des rotations rapprochées se croisent.
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val image = withContext(dispatcher) {
                EditRenderer.render(source, EditState(quarterTurns = state.quarterTurns, flipHorizontal = state.flipHorizontal)).asImageBitmap()
            }
            _preview.value = EditorPreview(image, state.quarterTurns, state.flipHorizontal)
        }
    }

    private companion object {
        const val HISTORY = 50

        /** Écart toléré entre deux recadrages normalisés tenus pour identiques. */
        const val EPSILON = 1e-4f
    }
}
