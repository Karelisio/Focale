package app.focale.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.core.model.Album
import app.focale.core.model.GroupMode
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.settings.AppSettings
import app.focale.core.settings.SettingsRepository
import app.focale.core.settings.ThemeMode
import app.focale.data.AlbumRepository
import app.focale.data.MediaRepository
import app.focale.work.AnalysisScheduler
import app.focale.work.AnalysisStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    albumRepository: AlbumRepository,
    private val analysisScheduler: AnalysisScheduler,
    private val mediaRepository: MediaRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.current

    /** Avancement détaillé de l'analyse sur l'appareil. */
    val analysisStatus: StateFlow<AnalysisStatus?> = analysisScheduler.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun runAnalysisNow() = analysisScheduler.runNow()

    /** Dossiers de l'appareil, pour choisir ceux masqués de la timeline. */
    val folders: StateFlow<List<Album.Folder>> = albumRepository.observeFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Photo récente ouverte pour que le système propose Focale « Toujours » : un JPEG de préférence,
     * le type dont dépend l'état « application par défaut ».
     */
    suspend fun samplePhoto(): MediaItem? {
        val photos = mediaRepository.list(MediaQuery(filter = MediaFilter.PHOTOS), limit = SAMPLE_CANDIDATES)
        return photos.firstOrNull { it.mimeType.equals("image/jpeg", ignoreCase = true) } ?: photos.firstOrNull()
    }

    private fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repository.block() }
    }

    fun setThemeMode(mode: ThemeMode) = update { setThemeMode(mode) }
    fun setAmoled(enabled: Boolean) = update { setAmoled(enabled) }
    fun setDynamicColor(enabled: Boolean) = update { setDynamicColor(enabled) }
    fun setColorFromPhoto(enabled: Boolean) = update { setColorFromPhoto(enabled) }
    fun setGroupMode(mode: GroupMode) = update { setGroupMode(mode) }
    fun setShowMemories(enabled: Boolean) = update { setShowMemories(enabled) }
    fun setAutoplayVideos(enabled: Boolean) = update { setAutoplayVideos(enabled) }
    fun setMuteVideos(enabled: Boolean) = update { setMuteVideosByDefault(enabled) }
    fun setStripMetadata(enabled: Boolean) = update { setStripMetadataOnShare(enabled) }
    fun setAnalysisEnabled(enabled: Boolean) = update { setAnalysisEnabled(enabled) }
    fun setAnalysisOnlyWhileCharging(enabled: Boolean) = update { setAnalysisOnlyWhileCharging(enabled) }
    fun setOnlineMapTiles(enabled: Boolean) = update { setOnlineMapTiles(enabled) }
    fun setSlideshowInterval(seconds: Int) = update { setSlideshowInterval(seconds) }
    fun setVaultAutoLock(seconds: Int) = update { setVaultAutoLockSeconds(seconds) }
    fun setAutoCheckUpdates(enabled: Boolean) = update { setAutoCheckUpdates(enabled) }
    fun setTvDiscovery(enabled: Boolean) = update { setTvDiscovery(enabled) }
    fun setFolderHidden(bucketId: Long, hidden: Boolean) = update { setFolderHidden(bucketId, hidden) }

    private companion object {
        const val SAMPLE_CANDIDATES = 50
    }
}
