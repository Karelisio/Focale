package app.focale.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.core.model.GroupMode
import app.focale.core.settings.AppSettings
import app.focale.core.settings.SettingsRepository
import app.focale.core.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.current

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
}
