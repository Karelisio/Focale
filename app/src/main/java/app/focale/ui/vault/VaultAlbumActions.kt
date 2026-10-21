package app.focale.ui.vault

import app.focale.R
import app.focale.core.util.UiText
import app.focale.data.OperationResult
import app.focale.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface VaultAlbumEvent {
    data class Message(val text: UiText) : VaultAlbumEvent

    /**
     * Album supprimé ou sorti du coffre : son écran se ferme, sur [text] s'il y en a un ; l'écran
     * du coffre n'affiche que [text].
     */
    data class Closed(val text: UiText?) : VaultAlbumEvent
}

/**
 * Options d'un album du coffre, communes au menu de l'album ouvert et à l'appui long sur sa tuile
 * (écran du coffre) : le renommer, le supprimer (ses éléments restent dans le coffre), le sortir
 * du coffre. De la métadonnée seulement, sauf la sortie, qui déchiffre. Propre à chaque ViewModel,
 * qui lui fournit sa portée.
 */
class VaultAlbumActions(
    private val scope: CoroutineScope,
    private val repository: VaultRepository,
) {
    private val _busy = MutableStateFlow(false)

    /** Un album sort du coffre. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val channel = Channel<VaultAlbumEvent>(Channel.BUFFERED)

    /** Issue des actions, à afficher. */
    val events: Flow<VaultAlbumEvent> = channel.receiveAsFlow()

    fun rename(albumId: Long, name: String) {
        scope.launch { repository.renameAlbum(albumId, name) }
    }

    /** Supprime l'album ; ses éléments restent dans le coffre. */
    fun delete(albumId: Long) {
        scope.launch {
            repository.deleteAlbum(albumId)
            channel.send(VaultAlbumEvent.Closed(null))
        }
    }

    /** Sort tout l'album du coffre : ses éléments retournent dans leurs dossiers d'origine. */
    fun restore(albumId: Long) {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            val result = repository.restoreAlbum(albumId)
            _busy.value = false
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
