package app.focale.ui.more

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.data.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Compteurs affichés sur les tuiles de l'onglet Plus. */
@Immutable
data class MoreCounts(
    val favorites: Int = 0,
    val trash: Int = 0,
    val vault: Int = 0,
)

@HiltViewModel
class MoreViewModel @Inject constructor(
    favoriteRepository: FavoriteRepository,
) : ViewModel() {
    val counts: StateFlow<MoreCounts> = favoriteRepository.observeCount()
        .map { MoreCounts(favorites = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoreCounts())
}
