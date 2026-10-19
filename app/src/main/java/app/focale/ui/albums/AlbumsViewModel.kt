package app.focale.ui.albums

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.core.model.Album
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.permissions.MediaAccess
import app.focale.core.permissions.MediaAccessManager
import app.focale.core.settings.SettingsRepository
import app.focale.data.AlbumRepository
import app.focale.data.MediaRepository
import app.focale.data.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Compteurs des collections automatiques (favoris, vidéos…). */
@Immutable
data class CollectionCounts(
    val favorites: Int = 0,
    val videos: Int = 0,
    val screenshots: Int = 0,
    val gifs: Int = 0,
    val trash: Int = 0,
)

@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
    mediaRepository: MediaRepository,
    trashRepository: TrashRepository,
    accessManager: MediaAccessManager,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val access: StateFlow<MediaAccess> = accessManager.access

    val userAlbums: StateFlow<List<Album.User>> = albumRepository.observeUserAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders: StateFlow<List<Album.Folder>> = albumRepository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Dossiers masqués de la timeline, signalés sur leur carte. */
    val hiddenFolders: StateFlow<Set<Long>> = settingsRepository.settings.map { it.hiddenFolders }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.current.value.hiddenFolders)

    val counts: StateFlow<CollectionCounts> = combine(
        mediaRepository.observeCount(FAVORITES),
        mediaRepository.observeCount(VIDEOS),
        mediaRepository.observeCount(SCREENSHOTS),
        mediaRepository.observeCount(GIFS),
        trashRepository.observeCount(),
    ) { favorites, videos, screenshots, gifs, trash ->
        CollectionCounts(favorites, videos, screenshots, gifs, trash)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectionCounts())

    suspend fun createAlbum(name: String): Long = albumRepository.create(name)

    companion object {
        val FAVORITES = MediaQuery(favoritesOnly = true)
        val VIDEOS = MediaQuery(filter = MediaFilter.VIDEOS)
        val SCREENSHOTS = MediaQuery(filter = MediaFilter.SCREENSHOTS)
        val GIFS = MediaQuery(filter = MediaFilter.GIFS)
    }
}
