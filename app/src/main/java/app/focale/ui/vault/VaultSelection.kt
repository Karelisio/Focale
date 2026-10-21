package app.focale.ui.vault

import app.focale.R
import app.focale.core.util.UiText
import app.focale.data.OperationResult
import app.focale.vault.VaultAlbum
import app.focale.vault.VaultDestination
import app.focale.vault.VaultItem
import app.focale.vault.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sélection d'une grille du coffre et ce qui s'y applique : restaurer, mettre à la corbeille du
 * coffre, ranger dans un album. Propre à chaque ViewModel, qui lui fournit sa portée et le
 * contenu affiché.
 */
class VaultSelection(
    private val scope: CoroutineScope,
    private val repository: VaultRepository,
    private val items: () -> List<VaultItem>,
) {
    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val channel = Channel<UiText>(Channel.BUFFERED)

    /** Issue des actions, à afficher. */
    val messages: Flow<UiText> = channel.receiveAsFlow()

    fun toggle(id: Long) {
        _selected.update { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selected.value = items().map { it.id }.toSet()
    }

    fun clear() {
        _selected.value = emptySet()
    }

    fun restore() = run(R.plurals.message_restored) { repository.restore(it) }

    /** Met à la corbeille du coffre : effacée définitivement après 30 jours, d'ici là récupérable. */
    fun delete() = run(R.plurals.vault_trashed) { repository.trash(it) }

    /** Range la sélection dans [destination], l'un des [albums] ou un nouveau. Rien n'est déchiffré. */
    fun moveTo(destination: VaultDestination, albums: List<VaultAlbum>) {
        val targets = selectedItems()
        if (targets.isEmpty()) return
        val name = when (destination) {
            VaultDestination.None -> null
            is VaultDestination.Album -> albums.firstOrNull { it.id == destination.id }?.name
            is VaultDestination.NewAlbum -> destination.name.trim()
        }
        scope.launch {
            repository.moveToAlbum(targets, destination)
            _selected.value = emptySet()
            channel.send(
                if (name == null) {
                    UiText.Plural(R.plurals.vault_removed_from_album, targets.size)
                } else {
                    UiText.Plural(R.plurals.vault_moved_to_album, targets.size, listOf(targets.size, name))
                },
            )
        }
    }

    private fun selectedItems(): List<VaultItem> = items().filter { it.id in _selected.value }

    private fun run(plural: Int, action: suspend (List<VaultItem>) -> OperationResult) {
        val targets = selectedItems()
        if (targets.isEmpty()) return
        scope.launch {
            _busy.value = true
            val message = when (val result = action(targets)) {
                is OperationResult.Success -> UiText.Plural(plural, result.count)
                is OperationResult.Partial -> UiText.Res(R.string.message_partial, listOf(result.succeeded, targets.size))
                is OperationResult.Failure -> UiText.Res(R.string.message_operation_failed)
                OperationResult.Cancelled -> null
            }
            _selected.value = emptySet()
            _busy.value = false
            message?.let { channel.send(it) }
        }
    }
}
