package app.focale.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.focale.core.database.AnalysisDao
import app.focale.core.image.SeedColorExtractor
import app.focale.core.model.MediaItem
import app.focale.core.settings.AppSettings
import app.focale.core.settings.SettingsRepository
import app.focale.data.FavoriteRepository
import app.focale.data.MediaDetails
import app.focale.data.MediaDetailsRepository
import app.focale.data.MediaRepository
import app.focale.data.OperationResult
import app.focale.data.PhotoRotator
import app.focale.edit.SaveMode
import app.focale.ml.PlaceName
import app.focale.ml.PlaceNames
import app.focale.people.PeopleRepository
import app.focale.people.Person
import app.focale.ui.actions.MediaActions
import app.focale.ui.navigation.RouteCodec
import app.focale.ui.navigation.ViewerRoute
import app.focale.video.MotionPhoto
import app.focale.video.MotionPhotoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Issue de l'enregistrement de la rotation d'un média. */
sealed interface RotationResult {
    val mediaId: Long

    /** [inPlace] : la photo elle-même a tourné ; sinon, une copie tournée a été créée. */
    data class Saved(override val mediaId: Long, val inPlace: Boolean) : RotationResult
    data class Failed(override val mediaId: Long) : RotationResult

    /** Consentement refusé : la rotation reste en attente. */
    data class Declined(override val mediaId: Long) : RotationResult
}

/**
 * Visionneuse : parcourt la même [app.focale.core.model.MediaQuery] que la grille
 * d'origine, à partir de l'index du média touché.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val favoriteRepository: FavoriteRepository,
    private val detailsRepository: MediaDetailsRepository,
    private val seedExtractor: SeedColorExtractor,
    settingsRepository: SettingsRepository,
    private val analysisDao: AnalysisDao,
    private val placeNames: PlaceNames,
    private val peopleRepository: PeopleRepository,
    private val motionPhotos: MotionPhotoRepository,
    private val rotator: PhotoRotator,
    val actions: MediaActions,
) : ViewModel() {

    init {
        actions.bind(viewModelScope)
    }

    private val route = savedStateHandle.toRoute<ViewerRoute>()
    val query = RouteCodec.decode(route.query)

    /** Média courant restauré après recréation (sinon celui touché dans la grille). */
    private val startId: Long = savedStateHandle[KEY_CURRENT] ?: route.mediaId

    private val _current = MutableStateFlow(ViewerHandoff.get(startId))
    val current: StateFlow<MediaItem?> = _current.asStateFlow()

    private val initialIndexDeferred = viewModelScope.async {
        mediaRepository.positionOf(query, startId) ?: 0
    }

    private val _initialIndex = MutableStateFlow<Int?>(null)
    val initialIndex: StateFlow<Int?> = _initialIndex.asStateFlow()

    val pages: Flow<PagingData<MediaItem>> = flow { emit(initialIndexDeferred.await()) }
        .flatMapLatest { index -> mediaRepository.paged(query, initialIndex = index) }
        .cachedIn(viewModelScope)

    val settings: StateFlow<AppSettings> = settingsRepository.current

    val isFavorite: StateFlow<Boolean> = _current
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { item -> item?.let { favoriteRepository.observeIsFavorite(it.id) } ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Couleur dominante du média courant (thème « couleur depuis la photo »). */
    val seedColor: StateFlow<Color?> = _current
        .filterNotNull()
        .distinctUntilChangedBy { it.id to it.dateModified }
        .mapLatest { seedExtractor.seedFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Lieu (géocodage hors ligne, noms traduits) et étiquettes ML Kit du média courant. */
    val place: StateFlow<Pair<PlaceName, String>?> = _current
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { item -> item?.let { analysisDao.observe(it.id) } ?: flowOf(null) }
        .mapLatest { analysis -> analysis?.place?.let { name -> placeNames.of(name, analysis.country) to analysis.country.orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val labels: StateFlow<List<String>> = _current
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { item -> item?.let { analysisDao.observeLabels(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Personnes reconnues sur le média courant. */
    val people: StateFlow<List<Person>> = _current
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { item -> item?.let { peopleRepository.observePeopleIn(it.id) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Vidéo intégrée du média courant, s'il s'agit d'une photo animée. */
    val motionPhoto: StateFlow<MotionPhoto?> = _current
        .distinctUntilChangedBy { it?.id to it?.dateModified }
        .mapLatest { item -> item?.let { motionPhotos.find(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _details = MutableStateFlow<MediaDetails?>(null)
    val details: StateFlow<MediaDetails?> = _details.asStateFlow()

    private var rotationJob: Job? = null
    private val rotationResults = Channel<RotationResult>(Channel.BUFFERED)
    val rotationEvents: Flow<RotationResult> = rotationResults.receiveAsFlow()

    init {
        viewModelScope.launch {
            _initialIndex.value = initialIndexDeferred.await()
            if (_current.value == null) _current.value = mediaRepository.get(startId)
        }
    }

    fun onPageChanged(item: MediaItem) {
        if (_current.value?.id == item.id && _current.value == item) return
        _current.value = item
        savedStateHandle[KEY_CURRENT] = item.id
        if (_details.value != null && _details.value?.item?.id != item.id) loadDetails()
    }

    fun toggleFavorite() {
        val item = _current.value ?: return
        viewModelScope.launch {
            favoriteRepository.setFavorite(listOf(item.id), !isFavorite.value)
        }
    }

    /** Applique une action de [MediaActions] au média courant. */
    fun onCurrent(action: MediaActions.(List<Long>) -> Unit) {
        val item = _current.value ?: return
        actions.action(listOf(item.id))
    }

    fun loadDetails() {
        val item = _current.value ?: return
        viewModelScope.launch { _details.value = detailsRepository.load(item) }
    }

    fun clearDetails() {
        _details.value = null
    }

    fun rotationOptions(item: MediaItem): PhotoRotator.Options = rotator.options(item)

    /**
     * Enregistre la rotation de [item] : le fichier n'est modifié qu'ici, à la demande de
     * l'utilisateur. Faux si un enregistrement est déjà en cours ; sinon la réponse arrive
     * dans [rotationEvents].
     */
    fun saveRotation(item: MediaItem, quarterTurns: Int, mode: SaveMode): Boolean {
        if (rotationJob?.isActive == true) return false
        val inPlace = rotator.options(item).lossless || mode == SaveMode.REPLACE
        rotationJob = viewModelScope.launch {
            val result = when (rotator.save(item, quarterTurns, mode)) {
                is OperationResult.Success -> RotationResult.Saved(item.id, inPlace)
                OperationResult.Cancelled -> RotationResult.Declined(item.id)
                else -> RotationResult.Failed(item.id)
            }
            rotationResults.send(result)
        }
        return true
    }

    private companion object {
        const val KEY_CURRENT = "current_media_id"
    }
}
