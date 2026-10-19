package app.focale.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.R
import app.focale.core.util.UiText
import app.focale.data.OperationResult
import app.focale.data.TrashEntry
import app.focale.data.TrashRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: TrashRepository,
) : ViewModel() {

    val entries: StateFlow<List<TrashEntry>?> = repository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val messages = Channel<UiText>(Channel.BUFFERED)
    val events: Flow<UiText> = messages.receiveAsFlow()

    fun toggle(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selection.value = entries.value.orEmpty().map { it.id }.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun restoreSelection() = run(selected(), R.plurals.message_restored) { repository.restore(it) }

    fun deleteSelection() = run(selected(), R.plurals.message_deleted) { repository.deleteForever(it) }

    fun emptyTrash() = run(entries.value.orEmpty(), R.plurals.message_deleted) { repository.deleteForever(it) }

    private fun selected(): List<TrashEntry> = entries.value.orEmpty().filter { it.id in _selection.value }

    private fun run(targets: List<TrashEntry>, plural: Int, action: suspend (List<TrashEntry>) -> OperationResult) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            when (val result = action(targets)) {
                is OperationResult.Success -> {
                    messages.send(UiText.Plural(plural, result.count))
                    _selection.value = emptySet()
                }
                is OperationResult.Partial -> {
                    messages.send(UiText.Res(R.string.message_partial, listOf(result.succeeded, targets.size)))
                    _selection.value = emptySet()
                }
                is OperationResult.Failure -> messages.send(UiText.Res(R.string.message_operation_failed))
                OperationResult.Cancelled -> Unit
            }
            _busy.value = false
        }
    }
}
