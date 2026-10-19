package app.focale.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.focale.core.model.GridItem
import app.focale.core.model.GroupMode
import app.focale.core.model.MediaQuery
import app.focale.core.settings.SettingsRepository
import app.focale.data.DateGrouping
import app.focale.data.MediaRepository
import app.focale.data.toGridItems
import app.focale.ui.actions.MediaActions
import app.focale.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/** Choix, dans toute la galerie, des médias à déplacer dans le coffre. */
@HiltViewModel
class VaultPickerViewModel @Inject constructor(
    mediaRepository: MediaRepository,
    settingsRepository: SettingsRepository,
    session: VaultSession,
    val actions: MediaActions,
) : ViewModel() {

    init {
        actions.bind(viewModelScope)
    }

    val unlocked: StateFlow<Boolean> = session.unlocked

    val columns: Int = settingsRepository.current.value.gridColumns

    private val groupLevel = DateGrouping.resolve(GroupMode.AUTO, columns)

    val items: Flow<PagingData<GridItem>> = mediaRepository.paged(MediaQuery())
        .map { it.toGridItems(groupLevel) }
        .cachedIn(viewModelScope)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    fun setSelection(ids: Set<Long>) {
        _selection.value = ids
    }

    fun moveToVault() {
        val ids = _selection.value.toList()
        if (ids.isNotEmpty()) actions.moveToVault(ids)
    }
}
