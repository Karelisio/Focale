package app.focale.ui.people

import androidx.annotation.PluralsRes
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.focale.R
import app.focale.core.image.SeedColorExtractor
import app.focale.core.util.UiText
import app.focale.data.MediaRepository
import app.focale.people.FaceTransfer
import app.focale.people.PeopleRepository
import app.focale.people.Person
import app.focale.people.ReviewFace
import app.focale.ui.navigation.PersonFacesRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Revue des visages d'une personne, les moins ressemblants d'abord : sélection multiple,
 * retrait ou déplacement vers une autre personne, annulables.
 */
@HiltViewModel
class FaceReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val people: PeopleRepository,
    mediaRepository: MediaRepository,
    seedExtractor: SeedColorExtractor,
) : ViewModel() {

    val personId: Long = savedStateHandle.toRoute<PersonFacesRoute>().personId

    val person: StateFlow<Person?> = people.observePerson(personId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())

    /** Visages sélectionnés (identifiants). */
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    /** Ordre affiché au début de la sélection en cours. */
    private var frozenOrder: List<Long>? = null

    /**
     * Visages, les moins ressemblants d'abord ; null tant qu'ils ne sont pas chargés. Pendant
     * une sélection, l'ordre est figé (l'analyse retouche la personne par lots, les cases ne
     * bougent pas sous le doigt) : les visages arrivés entre-temps se placent à la fin.
     */
    val faces: StateFlow<List<ReviewFace>?> = combine(
        people.observeReview(personId),
        _selection.map { it.isNotEmpty() }.distinctUntilChanged(),
    ) { faces, selecting ->
        if (!selecting) {
            frozenOrder = null
            return@combine faces
        }
        val order = frozenOrder ?: faces.map { it.face.faceId }.also { frozenOrder = it }
        val byId = faces.associateBy { it.face.faceId }
        val known = order.toHashSet()
        order.mapNotNull(byId::get) + faces.filter { it.face.faceId !in known }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Personnes vers lesquelles déplacer des visages, nommées d'abord. */
    val others: StateFlow<List<Person>> = people.observeOthers(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Couleur de la photo qui porte la vignette, comme l'écran de la personne. */
    val seedColor: StateFlow<Color?> = person
        .map { it?.cover?.mediaId }
        .distinctUntilChanged()
        .mapLatest { mediaId -> mediaId?.let { mediaRepository.get(it) }?.let { seedExtractor.seedFor(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val channel = Channel<PersonEvent.Message>(Channel.BUFFERED)
    val messages: Flow<PersonEvent.Message> = channel.receiveAsFlow()

    fun setSelection(ids: Set<Long>) {
        _selection.value = ids
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectAll() {
        _selection.value = faces.value.orEmpty().mapTo(HashSet()) { it.face.faceId }
    }

    /** Retire les visages sélectionnés de la personne (annulable). */
    fun removeSelection() = transferSelection({ people.removeFaces(personId, it) }) { count ->
        UiText.Plural(R.plurals.people_review_removed, count)
    }

    fun moveSelection(target: Person) = transferSelection({ people.moveFaces(personId, it, target.id) }) { count ->
        movedMessage(count, target.name, R.plurals.people_review_moved_other)
    }

    /** Déplace la sélection vers une nouvelle personne, nommée [name] s'il est renseigné. */
    fun moveSelectionToNewPerson(name: String) = transferSelection({ people.moveFacesToNewPerson(personId, it, name) }) { count ->
        movedMessage(count, name.trim().takeIf { it.isNotEmpty() }, R.plurals.people_review_moved_new)
    }

    /** « 3 visages déplacés vers Tom », ou [unnamed] pour une personne sans nom. */
    private fun movedMessage(count: Int, name: String?, @PluralsRes unnamed: Int): UiText =
        if (name != null) UiText.Plural(R.plurals.people_review_moved, count, listOf(count, name)) else UiText.Plural(unnamed, count)

    /** Applique [action] à la sélection, puis annonce le résultat avec de quoi l'annuler. */
    private fun transferSelection(action: suspend (Set<Long>) -> FaceTransfer?, message: (Int) -> UiText) {
        val ids = selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val transfer = action(ids) ?: return@launch
            clearSelection()
            channel.send(
                PersonEvent.Message(message(transfer.count)) {
                    viewModelScope.launch { people.restore(transfer) }
                },
            )
        }
    }
}
