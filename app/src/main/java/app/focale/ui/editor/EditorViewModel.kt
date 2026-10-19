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
import app.focale.edit.EditRenderer
import app.focale.edit.EditState
import app.focale.edit.FilterPreset
import app.focale.edit.NormRect
import app.focale.edit.SaveMode
import app.focale.ui.navigation.EditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EditorTool { CROP, FILTERS, ADJUST }

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

    /** Aperçu géométrique : rotation et miroir appliqués, sans recadrage ni couleur. */
    private val _preview = MutableStateFlow<ImageBitmap?>(null)
    val preview: StateFlow<ImageBitmap?> = _preview.asStateFlow()

    /** Aperçu de l'original (comparaison avant / après). */
    private var base: Bitmap? = null

    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()

    private val _tool = MutableStateFlow(EditorTool.CROP)
    val tool: StateFlow<EditorTool> = _tool.asStateFlow()

    private val _adjustment = MutableStateFlow(Adjustment.BRIGHTNESS)
    val adjustment: StateFlow<Adjustment> = _adjustment.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

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
        val rotated = current.copy(quarterTurns = Math.floorMod(turns, 4), crop = CropMath.rotateClockwise(current.crop))
        commit(if (current.aspect == CropAspect.FREE) rotated else refit(rotated))
        refreshPreview()
    }

    fun flip() {
        val current = _state.value
        commit(current.copy(flipHorizontal = !current.flipHorizontal, crop = CropMath.flipHorizontal(current.crop)))
        refreshPreview()
    }

    fun setAspect(aspect: CropAspect) {
        commit(refit(_state.value.copy(aspect = aspect)))
    }

    /** Pendant un geste de recadrage : pas d'entrée d'historique à chaque mouvement. */
    fun dragCrop(rect: NormRect) {
        _state.value = _state.value.copy(crop = rect)
    }

    fun beginGesture() = remember()

    fun setFilter(filter: FilterPreset) = commit(_state.value.copy(filter = filter))

    fun setAdjustmentValue(value: Float) {
        _state.value = _state.value.with(_adjustment.value, value)
    }

    fun resetAdjustment(adjustment: Adjustment) = commit(_state.value.with(adjustment, 0f))

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

    fun resetAll() = commit(EditState())

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
    fun previewRatio(): Float = _preview.value?.let { it.width.toFloat() / it.height } ?: 1f

    /** Rapport normalisé imposé par le format courant, null si libre. */
    fun cropRatio(): Float? {
        val preview = _preview.value ?: return null
        return CropMath.normalizedRatio(_state.value.aspect, preview.width, preview.height, preview.width.toFloat() / preview.height)
    }

    private fun refit(state: EditState): EditState {
        val source = base ?: return state
        val turned = state.quarterTurns % 2 == 1
        val width = if (turned) source.height else source.width
        val height = if (turned) source.width else source.height
        val original = width.toFloat() / height
        return state.copy(crop = CropMath.fit(CropMath.normalizedRatio(state.aspect, width, height, original)))
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
        viewModelScope.launch {
            _preview.value = withContext(dispatcher) {
                EditRenderer.render(source, state, includeCrop = false, includeColor = false).asImageBitmap()
            }
        }
    }

    private companion object {
        const val HISTORY = 50
    }
}
