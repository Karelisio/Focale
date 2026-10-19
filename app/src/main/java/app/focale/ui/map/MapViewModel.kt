package app.focale.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.BuildConfig
import app.focale.core.database.AnalysisDao
import app.focale.core.database.GeoPhoto
import app.focale.core.model.MediaItem
import app.focale.core.settings.SettingsRepository
import app.focale.data.MediaRepository
import app.focale.map.Basemap
import app.focale.map.OfflineBasemap
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Position de la carte, conservée d'une recréation de l'écran à l'autre. */
data class MapCamera(val latitude: Double, val longitude: Double, val zoom: Double)

@HiltViewModel
class MapViewModel @Inject constructor(
    analysisDao: AnalysisDao,
    basemapSource: OfflineBasemap,
    settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    /** Photos géolocalisées, de la plus récente à la plus ancienne. */
    val photos: StateFlow<List<GeoPhoto>?> = analysisDao.observeGeoPhotos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Tuiles OpenStreetMap : seulement sur demande, et jamais dans la variante strict. */
    val onlineTiles: StateFlow<Boolean> = settingsRepository.settings
        .map { BuildConfig.ONLINE_MAP_AVAILABLE && it.onlineMapTiles }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Sans analyse, aucune position n'est extraite des photos. */
    val analysisEnabled: StateFlow<Boolean> = settingsRepository.settings
        .map { it.analysisEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _basemap = MutableStateFlow<Basemap?>(null)
    val basemap: StateFlow<Basemap?> = _basemap.asStateFlow()

    var camera: MapCamera? = null

    init {
        viewModelScope.launch { _basemap.value = runCatching { basemapSource.load() }.getOrNull() }
    }

    suspend fun item(id: Long): MediaItem? = mediaRepository.get(id)
}
