package app.focale.ui.vault

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.R
import app.focale.core.util.UiText
import app.focale.data.OperationResult
import app.focale.vault.PinCheck
import app.focale.vault.PinHasher
import app.focale.vault.VaultItem
import app.focale.vault.VaultPinStore
import app.focale.vault.VaultRepository
import app.focale.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** État d'accès au coffre. */
enum class VaultMode { LOADING, SETUP, LOCKED, UNLOCKED }

/** Étape de saisie du code : création, déverrouillage ou changement. */
enum class PinStep { CREATE, CONFIRM, UNLOCK, CURRENT, NEW, CONFIRM_NEW }

/** État du pavé numérique ; les chiffres saisis restent dans le ViewModel. */
@Immutable
data class PinState(
    val step: PinStep = PinStep.UNLOCK,
    val length: Int = 0,
    val error: UiText? = null,
    val errorTick: Int = 0,
    val checking: Boolean = false,
)

sealed interface VaultEvent {
    data class Message(val text: UiText) : VaultEvent

    /** Coffre tout juste créé : proposer le déverrouillage biométrique. */
    data object OfferBiometric : VaultEvent
}

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository,
    private val pinStore: VaultPinStore,
    private val session: VaultSession,
) : ViewModel() {

    /** Un coffre déverrouillé a forcément un code : l'ordre évite d'afficher la création. */
    val mode: StateFlow<VaultMode> = combine(pinStore.hasPin, session.unlocked) { hasPin, unlocked ->
        when {
            unlocked -> VaultMode.UNLOCKED
            !hasPin -> VaultMode.SETUP
            else -> VaultMode.LOCKED
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VaultMode.LOADING)

    val biometricEnabled: StateFlow<Boolean> = pinStore.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Fin du blocage après trop d'erreurs (0 si aucun). */
    val lockedUntil: StateFlow<Long> = pinStore.lockedUntil
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    /** Contenu du coffre, chargé uniquement une fois déverrouillé. */
    val items: StateFlow<List<VaultItem>?> = session.unlocked
        .flatMapLatest { unlocked -> if (unlocked) repository.observeItems() else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _pin = MutableStateFlow(PinState())
    val pin: StateFlow<PinState> = _pin.asStateFlow()

    private val _changingPin = MutableStateFlow(false)
    val changingPin: StateFlow<Boolean> = _changingPin.asStateFlow()

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val channel = Channel<VaultEvent>(Channel.BUFFERED)
    val events: Flow<VaultEvent> = channel.receiveAsFlow()

    private val digits = StringBuilder()
    private var firstEntry: String? = null

    init {
        viewModelScope.launch {
            mode.collect { current ->
                when (current) {
                    VaultMode.SETUP -> resetPin(PinStep.CREATE)
                    VaultMode.LOCKED -> {
                        _changingPin.value = false
                        _selection.value = emptySet()
                        resetPin(PinStep.UNLOCK)
                    }
                    else -> Unit
                }
            }
        }
    }

    // ── Pavé numérique ──────────────────────────────────────────────────────────────

    fun onDigit(digit: Char) {
        if (_pin.value.checking || digits.length >= PinHasher.MAX_LENGTH) return
        digits.append(digit)
        _pin.update { it.copy(length = digits.length, error = null) }
    }

    fun onBackspace() {
        if (_pin.value.checking || digits.isEmpty()) return
        digits.deleteCharAt(digits.lastIndex)
        _pin.update { it.copy(length = digits.length) }
    }

    fun onConfirm() {
        val entry = digits.toString()
        if (_pin.value.checking || !PinHasher.isValid(entry)) return
        when (_pin.value.step) {
            PinStep.CREATE -> {
                firstEntry = entry
                resetPin(PinStep.CONFIRM)
            }
            PinStep.CONFIRM -> if (entry == firstEntry) save(entry, initial = true) else mismatch(PinStep.CREATE)
            PinStep.UNLOCK -> verify(entry) { session.unlock() }
            PinStep.CURRENT -> verify(entry) { resetPin(PinStep.NEW) }
            PinStep.NEW -> {
                firstEntry = entry
                resetPin(PinStep.CONFIRM_NEW)
            }
            PinStep.CONFIRM_NEW -> if (entry == firstEntry) save(entry, initial = false) else mismatch(PinStep.NEW)
        }
    }

    fun onBiometricSuccess() {
        viewModelScope.launch {
            pinStore.onBiometricSuccess()
            clearDigits()
            session.unlock()
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch { pinStore.setBiometricEnabled(enabled) }
    }

    fun startChangePin() {
        _changingPin.value = true
        resetPin(PinStep.CURRENT)
    }

    fun cancelChangePin() {
        _changingPin.value = false
        resetPin(PinStep.UNLOCK)
    }

    fun lock() = session.lock()

    private fun verify(entry: String, onSuccess: () -> Unit) {
        _pin.update { it.copy(checking = true) }
        viewModelScope.launch {
            when (val result = pinStore.check(entry)) {
                PinCheck.Success -> {
                    clearDigits()
                    _pin.update { it.copy(checking = false, length = 0, error = null) }
                    onSuccess()
                }
                is PinCheck.Wrong -> fail(UiText.Plural(R.plurals.vault_pin_wrong, result.remainingAttempts))
                is PinCheck.LockedOut -> fail(null)
            }
        }
    }

    private fun save(entry: String, initial: Boolean) {
        _pin.update { it.copy(checking = true) }
        viewModelScope.launch {
            pinStore.setPin(entry)
            firstEntry = null
            if (initial) {
                session.unlock()
                channel.send(VaultEvent.OfferBiometric)
            } else {
                _changingPin.value = false
                channel.send(VaultEvent.Message(UiText.Res(R.string.vault_pin_changed)))
            }
            resetPin(PinStep.UNLOCK)
        }
    }

    private fun mismatch(restartAt: PinStep) {
        firstEntry = null
        clearDigits()
        _pin.update {
            PinState(step = restartAt, error = UiText.Res(R.string.vault_pin_mismatch), errorTick = it.errorTick + 1)
        }
    }

    /** Erreur de saisie ; sans [message], le blocage temporaire est affiché par l'écran. */
    private fun fail(message: UiText?) {
        clearDigits()
        _pin.update { it.copy(length = 0, checking = false, error = message, errorTick = it.errorTick + 1) }
    }

    private fun resetPin(step: PinStep) {
        clearDigits()
        _pin.update { PinState(step = step, errorTick = it.errorTick) }
    }

    private fun clearDigits() {
        digits.setLength(0)
    }

    // ── Contenu ─────────────────────────────────────────────────────────────────────

    fun toggle(id: Long) {
        _selection.update { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        _selection.value = items.value.orEmpty().map { it.id }.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun restoreSelection() = run(R.plurals.message_restored) { repository.restore(it) }

    fun deleteSelection() = run(R.plurals.message_deleted) { repository.delete(it) }

    private fun run(plural: Int, action: suspend (List<VaultItem>) -> OperationResult) {
        val targets = items.value.orEmpty().filter { it.id in _selection.value }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _busy.value = true
            val message = when (val result = action(targets)) {
                is OperationResult.Success -> UiText.Plural(plural, result.count)
                is OperationResult.Partial -> UiText.Res(R.string.message_partial, listOf(result.succeeded, targets.size))
                is OperationResult.Failure -> UiText.Res(R.string.message_operation_failed)
                OperationResult.Cancelled -> null
            }
            _selection.value = emptySet()
            _busy.value = false
            message?.let { channel.send(VaultEvent.Message(it)) }
        }
    }

    override fun onCleared() {
        clearDigits()
        firstEntry = null
    }
}
