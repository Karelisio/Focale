package app.focale.ui.whatsnew

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.whatsnew.Changelog
import app.focale.whatsnew.WhatsNewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class WhatsNewViewModel @Inject constructor(private val repository: WhatsNewRepository) : ViewModel() {

    private val _shown = MutableStateFlow<List<Changelog.Release>>(emptyList())

    /** Versions affichées dans la feuille « Nouveautés » ; vide quand elle est fermée. */
    val shown: StateFlow<List<Changelog.Release>> = _shown.asStateFlow()

    /** Après une mise à jour : présente les nouveautés une seule fois. */
    fun showPending() {
        viewModelScope.launch { _shown.value = repository.pending() }
    }

    /** Journal complet, à la demande (écran À propos). */
    fun showAll() {
        viewModelScope.launch { _shown.value = repository.all() }
    }

    fun dismiss() {
        _shown.value = emptyList()
        viewModelScope.launch { repository.markSeen() }
    }
}
