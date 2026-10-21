package app.focale.ui.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.focale.R
import app.focale.core.settings.VaultSort
import app.focale.core.util.UiText
import app.focale.data.OperationResult
import app.focale.ui.actions.MediaActions
import app.focale.ui.navigation.VaultAlbumRoute
import app.focale.vault.VaultAlbum
import app.focale.vault.VaultContent
import app.focale.vault.VaultDestination
import app.focale.vault.VaultItem
import app.focale.vault.VaultRepository
import app.focale.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface VaultAlbumEvent {
    data class Message(val text: UiText) : VaultAlbumEvent

    /** Album supprimé ou sorti du coffre : l'écran se ferme, sur [text] s'il y en a un. */
    data class Closed(val text: UiText?) : VaultAlbumEvent
}

/**
 * Album du coffre : son contenu, ses actions et, pour un album-dossier, les médias arrivés
 * depuis dans son dossier d'origine. Les y ajouter ne chiffre qu'eux : le reste de l'album
 * n'est ni déchiffré ni rechiffré. Rien n'est lu tant que le coffre est verrouillé.
 */
@HiltViewModel
class VaultAlbumViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: VaultRepository,
    private val content: VaultContent,
    private val session: VaultSession,
    /** Import des nouveaux médias : progression, consentement de suppression et bilan. */
    val actions: MediaActions,
) : ViewModel() {

    init {
        actions.bind(viewModelScope)
    }

    val albumId: Long = savedStateHandle.toRoute<VaultAlbumRoute>().albumId

    val unlocked: StateFlow<Boolean> = session.unlocked

    /** Album affiché, null tant qu'il n'est pas lu. */
    val album: StateFlow<VaultAlbum?> = session.unlocked
        .flatMapLatest { unlocked -> if (unlocked) repository.observeAlbum(albumId) else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val items: StateFlow<List<VaultItem>?> = session.unlocked
        .flatMapLatest { unlocked -> if (unlocked) content.items(albumId) else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Autres albums du coffre, où déplacer une sélection. */
    val otherAlbums: StateFlow<List<VaultAlbum>> = session.unlocked
        .flatMapLatest { unlocked -> if (unlocked) content.albums() else flowOf(emptyList()) }
        .map { albums -> albums.filter { it.id != albumId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Médias arrivés dans le dossier d'origine depuis le dernier import ; null s'il n'y en a pas. */
    val newMedia: StateFlow<NewMedia?> = album
        .map { it?.sourceName }
        .distinctUntilChanged()
        .flatMapLatest { folder ->
            if (folder == null) {
                flowOf(null)
            } else {
                repository.observeNewInSource(albumId).map { media ->
                    val videos = media.count { it.isVideo }
                    NewMedia(photos = media.size - videos, videos = videos, folderName = folder).takeIf { media.isNotEmpty() }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Tri des éléments, commun à tout le coffre et mémorisé. */
    val sort: StateFlow<VaultSort> = content.itemSort
        .stateIn(viewModelScope, SharingStarted.Eagerly, VaultSort())

    private val selectionState = VaultSelection(viewModelScope, repository) { items.value.orEmpty() }
    val selection: StateFlow<Set<Long>> = selectionState.selected

    private val restoring = MutableStateFlow(false)

    /** Une opération est en cours : sélection, sortie de l'album ou import des nouveaux médias. */
    val busy: StateFlow<Boolean> = combine(selectionState.busy, restoring, actions.busy) { selecting, leaving, importing ->
        selecting || leaving || importing
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val channel = Channel<VaultAlbumEvent>(Channel.BUFFERED)
    val events: Flow<VaultAlbumEvent> = merge(channel.receiveAsFlow(), selectionState.messages.map { VaultAlbumEvent.Message(it) })

    fun toggle(id: Long) = selectionState.toggle(id)

    fun selectAll() = selectionState.selectAll()

    fun clearSelection() = selectionState.clear()

    fun restoreSelection() = selectionState.restore()

    fun deleteSelection() = selectionState.delete()

    fun moveSelection(destination: VaultDestination) = selectionState.moveTo(destination, otherAlbums.value)

    fun setSort(sort: VaultSort) {
        viewModelScope.launch { content.setItemSort(sort) }
    }

    fun lock() = session.lock()

    /** Chiffre dans l'album les nouveaux médias de son dossier d'origine, et eux seuls. */
    fun addNewMedia() {
        val count = newMedia.value?.count ?: return
        if (!busy.value) actions.moveNewToVault(albumId, count)
    }

    fun rename(name: String) {
        viewModelScope.launch { repository.renameAlbum(albumId, name) }
    }

    /** Supprime l'album ; ses éléments restent dans le coffre. */
    fun deleteAlbum() {
        viewModelScope.launch {
            repository.deleteAlbum(albumId)
            channel.send(VaultAlbumEvent.Closed(null))
        }
    }

    /** Sort tout l'album du coffre : ses éléments retournent dans leurs dossiers d'origine. */
    fun restoreAlbum() {
        if (busy.value) return
        viewModelScope.launch {
            restoring.value = true
            val result = repository.restoreAlbum(albumId)
            restoring.value = false
            val event = when (result) {
                is OperationResult.Success -> VaultAlbumEvent.Closed(UiText.Plural(R.plurals.message_restored, result.count))
                is OperationResult.Partial -> VaultAlbumEvent.Message(
                    UiText.Res(R.string.message_partial, listOf(result.succeeded, result.succeeded + result.failed)),
                )
                is OperationResult.Failure -> VaultAlbumEvent.Message(UiText.Res(R.string.message_operation_failed))
                OperationResult.Cancelled -> null
            }
            event?.let { channel.send(it) }
        }
    }
}
