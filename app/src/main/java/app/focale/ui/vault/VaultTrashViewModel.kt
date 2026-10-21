package app.focale.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.R
import app.focale.core.util.UiText
import app.focale.data.OperationResult
import app.focale.vault.VaultItem
import app.focale.vault.VaultRepository
import app.focale.vault.VaultSession
import app.focale.vault.VaultTrashEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Corbeille du coffre : les éléments supprimés y restent chiffrés 30 jours, d'où ils peuvent être
 * remis dans le coffre (et dans leur album) ou effacés définitivement. Rien n'est lu tant que le
 * coffre est verrouillé.
 */
@HiltViewModel
class VaultTrashViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val session: VaultSession,
) : ViewModel() {

    val unlocked: StateFlow<Boolean> = session.unlocked

    val entries: StateFlow<List<VaultTrashEntry>?> = session.unlocked
        .flatMapLatest { unlocked -> if (unlocked) repository.observeTrash() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val channel = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = channel.receiveAsFlow()

    fun toggle(id: Long) {
        _selection.update { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selection.value = entries.value.orEmpty().map { it.item.id }.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun restoreSelection() = run(selected(), R.plurals.vault_untrashed) { repository.untrash(it) }

    fun deleteSelection() = run(selected(), R.plurals.message_deleted) { repository.delete(it) }

    fun emptyTrash() = run(entries.value.orEmpty().map { it.item }, R.plurals.message_deleted) { repository.delete(it) }

    fun lock() = session.lock()

    private fun selected(): List<VaultItem> = entries.value.orEmpty().map { it.item }.filter { it.id in _selection.value }

    private fun run(items: List<VaultItem>, plural: Int, action: suspend (List<VaultItem>) -> OperationResult) {
        if (items.isEmpty() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            val message = when (val result = action(items)) {
                is OperationResult.Success -> UiText.Plural(plural, result.count)
                is OperationResult.Partial -> UiText.Res(R.string.message_partial, listOf(result.succeeded, items.size))
                is OperationResult.Failure -> UiText.Res(R.string.message_operation_failed)
                OperationResult.Cancelled -> null
            }
            _selection.value = emptySet()
            _busy.value = false
            message?.let { channel.send(it) }
        }
    }
}
