package app.focale.ui.components

import app.focale.core.model.MediaQuery
import app.focale.data.MediaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Sélection multiple d'une grille, conservée par le ViewModel (survit aux rotations). */
class GridSelection(
    private val scope: CoroutineScope,
    private val repository: MediaRepository,
) {
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    fun set(ids: Set<Long>) {
        _selected.value = ids
    }

    fun clear() {
        _selected.value = emptySet()
    }

    /** Sélectionne tous les médias de [query] (identifiants seuls, même pour 50 000 éléments). */
    fun selectAll(query: MediaQuery) {
        scope.launch { _selected.value = repository.ids(query).toSet() }
    }
}
