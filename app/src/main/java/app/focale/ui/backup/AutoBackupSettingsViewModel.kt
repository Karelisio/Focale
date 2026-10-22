package app.focale.ui.backup

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.R
import app.focale.backup.ArchiveKeys
import app.focale.backup.AutoBackupEngine
import app.focale.backup.AutoBackupError
import app.focale.backup.AutoBackupFrequency
import app.focale.backup.AutoBackupSettings
import app.focale.core.util.UiText
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Étape du réglage d'une sauvegarde automatique ; chacune hors [Idle] a sa fenêtre. */
@Immutable
sealed interface AutoBackupFlow {
    data object Idle : AutoBackupFlow

    /**
     * Feuille du réglage. [busy] : clés en cours de dérivation, sélecteur de dossier ouvert ou
     * réglage en cours d'enregistrement ; [notice] : issue d'une action, affichée dans la feuille.
     */
    data class Settings(val busy: Boolean = false, val notice: Notice? = null) : AutoBackupFlow

    /** Mot de passe, pour activer les sauvegardes à la fréquence [frequency], ou le changer (null). */
    data class NewPassword(val frequency: AutoBackupFrequency?) : AutoBackupFlow
}

/** Message affiché dans la feuille du réglage. */
@Immutable
data class Notice(val text: UiText, val error: Boolean)

/**
 * Réglage d'une sauvegarde automatique (coffre-fort ou données) : fréquence, dossier (sélecteur
 * système, ouvert par l'écran), mot de passe, état.
 *
 * Le mot de passe ne fait que passer : ses clés en sont tirées dès sa saisie, pendant le choix du
 * dossier, puis il est effacé ; elles le sont aussi si l'activation n'aboutit pas.
 *
 * @param errorMessage explication d'un échec, propre à chaque sauvegarde.
 */
abstract class AutoBackupSettingsViewModel(
    private val autoBackup: AutoBackupEngine<*>,
    private val errorMessage: (AutoBackupError) -> Int,
) : ViewModel() {

    val settings: StateFlow<AutoBackupSettings> = autoBackup.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoBackupSettings())

    private val _flow = MutableStateFlow<AutoBackupFlow>(AutoBackupFlow.Idle)
    val flow: StateFlow<AutoBackupFlow> = _flow.asStateFlow()

    private val activations = Channel<Unit>(Channel.CONFLATED)

    /** Sauvegardes tout juste activées : l'écran demande de pouvoir signaler leurs échecs. */
    val activated: Flow<Unit> = activations.receiveAsFlow()

    /** Activation en attente du dossier : clés en cours de dérivation et fréquence choisie. */
    private var pending: Pending? = null

    /** Sélecteur ouvert pour changer de dossier (et non pour activer). */
    private var changingFolder = false

    private class Pending(val keys: Deferred<ArchiveKeys>, val frequency: AutoBackupFrequency)

    /** Juste avant l'ouverture d'un sélecteur du système. */
    protected open fun beforeSystemPicker() {}

    /** Faux quand le réglage ne peut plus s'afficher (coffre verrouillé) : une action finie le referme. */
    protected open fun canShowSettings(): Boolean = true

    /** Abandonne les saisies en cours, pas les enregistrements lancés (verrouillage du coffre). */
    protected fun abandonInputs() {
        val current = _flow.value
        if (current is AutoBackupFlow.NewPassword || (current is AutoBackupFlow.Settings && !current.busy)) {
            _flow.value = AutoBackupFlow.Idle
        }
    }

    /** Ouvre la feuille du réglage. */
    fun open() {
        if (_flow.value == AutoBackupFlow.Idle) _flow.value = AutoBackupFlow.Settings()
    }

    /** Ferme la feuille, sauf pendant un enregistrement. */
    fun dismiss() {
        val current = _flow.value
        if (current is AutoBackupFlow.Settings && current.busy) return
        _flow.value = AutoBackupFlow.Idle
    }

    /**
     * Fréquence choisie : appliquée si les sauvegardes sont actives (« Désactivée » les arrête) ;
     * sinon, une fréquence active demande d'abord le mot de passe, puis le dossier.
     */
    fun setFrequency(frequency: AutoBackupFrequency) {
        if (!isIdleSettings()) return
        when {
            settings.value.enabled -> {
                _flow.value = AutoBackupFlow.Settings()
                viewModelScope.launch { autoBackup.setFrequency(frequency) }
            }
            frequency != AutoBackupFrequency.OFF -> _flow.value = AutoBackupFlow.NewPassword(frequency)
        }
    }

    fun changePassword() {
        if (isIdleSettings() && settings.value.enabled) _flow.value = AutoBackupFlow.NewPassword(frequency = null)
    }

    /** Saisie du mot de passe abandonnée : retour à la feuille. */
    fun cancelPassword() {
        if (_flow.value is AutoBackupFlow.NewPassword) _flow.value = AutoBackupFlow.Settings()
    }

    /**
     * Mot de passe confirmé, effacé dès ses clés tirées. Pour activer, l'écran ouvre ensuite le
     * sélecteur de dossier pendant la dérivation ; sinon, il remplace le précédent.
     */
    fun onPasswordChosen(password: CharArray) {
        val step = _flow.value
        if (step !is AutoBackupFlow.NewPassword) {
            password.fill('\u0000')
            return
        }
        val frequency = step.frequency
        _flow.value = AutoBackupFlow.Settings(busy = true)
        if (frequency != null) {
            discardPending()
            changingFolder = false
            pending = Pending(viewModelScope.async { autoBackup.deriveKeys(password) }, frequency)
            beforeSystemPicker()
        } else {
            viewModelScope.launch {
                val changed = autoBackup.changePassword(autoBackup.deriveKeys(password))
                settle(
                    if (changed) {
                        Notice(UiText.Res(R.string.auto_backup_password_changed), error = false)
                    } else {
                        Notice(UiText.Res(errorMessage(AutoBackupError.KEYS_UNAVAILABLE)), error = true)
                    },
                )
            }
        }
    }

    /** Juste avant d'ouvrir le sélecteur pour changer de dossier. */
    fun beforeFolderChange() {
        changingFolder = true
        beforeSystemPicker()
    }

    /** Dossier choisi par le sélecteur, ou null s'il a été fermé sans choix. */
    fun onFolderChosen(folder: Uri?) {
        val activation = pending
        pending = null
        val changing = changingFolder
        changingFolder = false
        when {
            changing -> if (folder != null) {
                _flow.value = AutoBackupFlow.Settings(busy = true)
                viewModelScope.launch { settle(if (autoBackup.changeFolder(folder)) null else folderRefused()) }
            }
            activation != null -> if (folder == null) {
                activation.keys.discard()
                settle(null)
            } else {
                viewModelScope.launch {
                    when (val error = autoBackup.enable(folder, activation.keys.await(), activation.frequency)) {
                        null -> {
                            settle(null)
                            activations.trySend(Unit)
                        }
                        AutoBackupError.FOLDER_UNAVAILABLE -> settle(folderRefused())
                        else -> settle(Notice(UiText.Res(errorMessage(error)), error = true))
                    }
                }
            }
            // Écran recréé entre-temps (processus arrêté) : mot de passe perdu, rien n'est activé.
            else -> Unit
        }
    }

    /** Aucun sélecteur de dossier sur l'appareil. */
    fun onPickerUnavailable() {
        changingFolder = false
        discardPending()
        settle(Notice(UiText.Res(R.string.auto_backup_no_picker), error = true))
    }

    /** Fin d'une action : la feuille la montre, si elle peut encore s'afficher. */
    private fun settle(notice: Notice?) {
        _flow.value = if (canShowSettings()) AutoBackupFlow.Settings(notice = notice) else AutoBackupFlow.Idle
    }

    private fun isIdleSettings(): Boolean = (_flow.value as? AutoBackupFlow.Settings)?.busy == false

    private fun folderRefused() = Notice(UiText.Res(R.string.auto_backup_folder_refused), error = true)

    private fun discardPending() {
        pending?.keys?.discard()
        pending = null
    }

    /** Abandonne une dérivation : ses clés, si elles arrivent, sont effacées. */
    private fun Deferred<ArchiveKeys>.discard() {
        invokeOnCompletion { error -> if (error == null) getCompleted().close() }
        cancel()
    }

    override fun onCleared() {
        discardPending()
    }
}
