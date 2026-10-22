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
import app.focale.edit.BlurMath
import app.focale.edit.BlurPatch
import app.focale.edit.BlurRegion
import app.focale.edit.BlurRenderer
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
import app.focale.people.FaceLocator
import app.focale.ui.navigation.EditorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EditorTool { CROP, FILTERS, ADJUST, DRAW, TEXT, BLUR }

/** Aperçu géométrique et l'orientation pour laquelle il a été rendu. */
data class EditorPreview(val image: ImageBitmap, val quarterTurns: Int, val flipHorizontal: Boolean) {
    /** Rendu en arrière-plan, l'aperçu suit l'état avec un léger retard après une rotation. */
    fun matches(state: EditState): Boolean = quarterTurns == state.quarterTurns && flipHorizontal == state.flipHorizontal
}

/** Résultat d'un enregistrement ou d'une détection de visages, consommé par l'écran. */
sealed interface EditorEvent {
    data class Saved(val mode: SaveMode) : EditorEvent
    data object Failed : EditorEvent

    /** Bouton « Visages » : [count] zones ajoutées (0 : aucun visage nouveau trouvé). */
    data class FacesBlurred(val count: Int) : EditorEvent
}

/** Ce dont dépendent les zones floutées calculées de l'aperçu. */
private data class BlurInputs(val blurs: List<BlurRegion>, val quarterTurns: Int, val flipHorizontal: Boolean)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val editor: ImageEditor,
    private val seedExtractor: SeedColorExtractor,
    private val faceLocator: FaceLocator,
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

    /** État de l'édition et historique, partagés avec les outils Texte et Flouter. */
    private val history = EditHistory()
    val state: StateFlow<EditState> = history.state

    /** Outil Texte : textes, sélection, saisie ouverte et réglages. */
    val textTool = TextController(history)

    /** Outil Flouter : zones, sélection et réglages. */
    val blurTool = BlurController(history)

    /**
     * Zones floutées de l'aperçu, calculées en arrière-plan sur l'image réduite à chaque
     * changement de zone ou d'orientation ; une zone en cours de déplacement garde la sienne
     * jusqu'au calcul suivant, qui la suit ([BlurRenderer.draw]).
     */
    private val _blurPatches = MutableStateFlow<Map<Long, BlurPatch>>(emptyMap())
    val blurPatches: StateFlow<Map<Long, BlurPatch>> = _blurPatches.asStateFlow()

    private val _detectingFaces = MutableStateFlow(false)
    val detectingFaces: StateFlow<Boolean> = _detectingFaces.asStateFlow()

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

    val canUndo: StateFlow<Boolean> = history.canUndo
    val canRedo: StateFlow<Boolean> = history.canRedo

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
            observeBlurs(loaded)
        }
    }

    val canReplace: Boolean get() = _item.value?.let(editor::canReplace) == true

    val hasChanges: Boolean get() = !history.current.isIdentity

    fun selectTool(tool: EditorTool) {
        if (tool == _tool.value) return
        // Une sélection n'a de sens que dans son outil : elle ne réapparaît pas au retour.
        textTool.clear()
        blurTool.clear()
        _tool.value = tool
    }

    fun selectAdjustment(adjustment: Adjustment) {
        _adjustment.value = adjustment
    }

    fun rotate() {
        val current = history.current
        // Recadrage, traits, textes et zones tournent avec l'image ([EditState.rotated]).
        val rotated = current.rotated()
        straightenReference = straightenReference?.let(CropMath::rotateClockwise)
        commit(if (current.aspect == CropAspect.FREE) rotated else refit(rotated))
        refreshPreview()
    }

    fun flip() {
        // Le redressement s'inverse, les lettres des textes ne sont pas retournées ([EditState.mirrored]).
        straightenReference = straightenReference?.let(CropMath::flipHorizontal)
        commit(history.current.mirrored())
        refreshPreview()
    }

    fun setAspect(aspect: CropAspect) {
        commit(refit(history.current.copy(aspect = aspect)))
    }

    /** Pendant un geste de recadrage : pas d'entrée d'historique à chaque mouvement. */
    fun dragCrop(rect: NormRect) {
        straightenReference = null
        history.update(history.current.copy(crop = rect))
    }

    /** Début d'un redressement à la règle ou à deux doigts : une seule étape d'historique. */
    fun beginStraighten() {
        remember()
        _straightening.value = true
    }

    /** Pendant un redressement : angle et recadrage suivent, sans entrée d'historique. */
    fun straighten(angle: Float) {
        history.update(straightened(history.current, angle))
    }

    fun endStraighten() {
        _straightening.value = false
    }

    fun resetStraighten() = commit(straightened(history.current, 0f))

    fun beginGesture() = remember()

    fun setFilter(filter: FilterPreset) = commit(history.current.copy(filter = filter))

    fun setAdjustmentValue(value: Float) {
        history.update(history.current.with(_adjustment.value, value))
    }

    fun resetAdjustment(adjustment: Adjustment) = commit(history.current.with(adjustment, 0f))

    fun selectDrawTool(tool: DrawTool) = _drawing.update { it.copy(tool = tool) }

    fun selectInk(ink: InkColor) = _drawing.update { it.copy(ink = ink) }

    fun setDrawSize(size: Float) = _drawing.update { it.copy(size = size.coerceIn(DrawingOptions.MIN_SIZE, DrawingOptions.MAX_SIZE)) }

    /** Chaque trait terminé forme une étape d'historique. */
    fun addStroke(stroke: DrawingStroke) = commit(history.current.copy(strokes = history.current.strokes + stroke))

    /**
     * Gomme : comme pour un recadrage, l'étape d'historique est créée au début du geste
     * ([beginGesture], au premier trait touché), qui peut en effacer plusieurs.
     */
    fun eraseStroke(stroke: DrawingStroke) {
        history.update(history.current.copy(strokes = history.current.strokes.filterNot { it === stroke }))
    }

    /**
     * Bouton « Visages » : une zone ellipse, au style et à l'intensité courants, sur chaque
     * visage détecté (ML Kit, sur l'aperçu de l'original) que ne couvre pas déjà une zone, le
     * tout en une seule étape d'historique.
     */
    fun blurFaces() {
        val source = base ?: return
        if (_detectingFaces.value) return
        viewModelScope.launch {
            _detectingFaces.value = true
            val faces = try {
                faceLocator.locate(source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            } finally {
                _detectingFaces.value = false
            }
            val state = history.current
            val (width, height) = frameSize(state) ?: return@launch
            val w = width.toFloat()
            val h = height.toFloat()
            val areas = faces
                .map { BlurMath.faceArea(NormRect(it.left, it.top, it.right, it.bottom), state.quarterTurns, state.flipHorizontal, w, h) }
                .filter { face -> state.blurs.none { BlurMath.contains(it.area, face.x * w, face.y * h, w, h) } }
            blurTool.addAll(areas)
            events.send(EditorEvent.FacesBlurred(areas.size))
        }
    }

    fun undo() {
        val before = history.current
        if (history.undo()) afterHistory(before)
    }

    fun redo() {
        val before = history.current
        if (history.redo()) afterHistory(before)
    }

    /** « Annuler les modifications » : tout revient à l'original, textes et zones compris, en une étape annulable. */
    fun resetAll() {
        val before = history.current
        straightenReference = null
        textTool.clear()
        blurTool.clear()
        commit(EditState())
        if (before.quarterTurns % 4 != 0 || before.flipHorizontal) refreshPreview()
    }

    fun save(mode: SaveMode) {
        val media = _item.value ?: return
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            val result = editor.save(media, history.current, mode)
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
        return CropMath.normalizedRatio(history.current.aspect, preview.width, preview.height, preview.width.toFloat() / preview.height)
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

    private fun commit(newState: EditState) = history.commit(newState)

    private fun remember() = history.remember()

    /** Après annuler ou rétablir : aperçu pivoté si besoin, sélections d'éléments disparus oubliées. */
    private fun afterHistory(before: EditState) {
        val state = history.current
        val geometryChanged = state.quarterTurns != before.quarterTurns || state.flipHorizontal != before.flipHorizontal
        straightenReference = null
        textTool.sync()
        blurTool.sync()
        if (geometryChanged) refreshPreview()
    }

    /**
     * Calcule en arrière-plan les zones floutées de l'aperçu sur [source] (l'original réduit) :
     * seules les zones nouvelles ou modifiées le sont, et un calcul dépassé est abandonné.
     */
    private fun observeBlurs(source: Bitmap) {
        viewModelScope.launch {
            history.state
                .map { BlurInputs(it.blurs, it.quarterTurns, it.flipHorizontal) }
                .distinctUntilChanged()
                .collectLatest { inputs ->
                    val previous = _blurPatches.value
                    _blurPatches.value = withContext(dispatcher) {
                        buildMap {
                            for (region in inputs.blurs) {
                                ensureActive()
                                val cached = previous[region.id]?.takeIf {
                                    it.region == region && it.quarterTurns == inputs.quarterTurns && it.flipHorizontal == inputs.flipHorizontal
                                }
                                val patch = cached ?: BlurRenderer.patch(source, inputs.quarterTurns, inputs.flipHorizontal, region)
                                if (patch != null) put(region.id, patch)
                            }
                        }
                    }
                }
        }
    }

    private fun refreshPreview() {
        val source = base ?: return
        val state = history.current
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
        /** Écart toléré entre deux recadrages normalisés tenus pour identiques. */
        const val EPSILON = 1e-4f
    }
}
