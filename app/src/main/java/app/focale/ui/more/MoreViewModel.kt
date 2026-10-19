package app.focale.ui.more

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.data.FavoriteRepository
import app.focale.data.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Compteurs affichés sur les tuiles de l'onglet Plus. */
@Immutable
data class MoreCounts(
    val favorites: Int = 0,
    val trash: Int = 0,
)

@HiltViewModel
class MoreViewModel @Inject constructor(
    favoriteRepository: FavoriteRepository,
    trashRepository: TrashRepository,
) : ViewModel() {
    val counts: StateFlow<MoreCounts> = combine(
        favoriteRepository.observeCount(),
        trashRepository.observeCount(),
    ) { favorites, trash -> MoreCounts(favorites = favorites, trash = trash) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MoreCounts())
}
