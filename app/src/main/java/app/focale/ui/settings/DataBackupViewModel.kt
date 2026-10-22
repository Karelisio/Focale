package app.focale.ui.settings

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.R
import app.focale.backup.AutoBackupError
import app.focale.backup.BackupProgress
import app.focale.backup.DataAutoBackup
import app.focale.backup.DataBackupFailure
import app.focale.backup.DataBackupRepository
import app.focale.backup.DataBackupSummary
import app.focale.backup.DataExportResult
import app.focale.backup.DataInspectResult
import app.focale.backup.DataRestoreReport
import app.focale.backup.DataRestoreResult
import app.focale.backup.InspectedBackup
import app.focale.backup.dataMessage
import app.focale.core.util.UiText
import app.focale.ui.backup.AutoBackupSettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Étape d'une sauvegarde ou d'une restauration des données ; chaque étape hors [Idle] a son dialogue. */
@Immutable
sealed interface DataBackupFlow {
    data object Idle : DataBackupFlow

    /** Choix du mot de passe d'une nouvelle sauvegarde. */
    data object NewPassword : DataBackupFlow

    /** Mot de passe de la sauvegarde choisie : [wrong] après un refus, [checking] pendant la vérification. */
    data class BackupPassword(val wrong: Boolean = false, val checking: Boolean = false) : DataBackupFlow

    /** Sauvegarde vérifiée : son résumé et ce que la restauration fusionne ou remplace, à confirmer. */
    data class Summary(val summary: DataBackupSummary) : DataBackupFlow

    data class Running(val restoring: Boolean, val progress: BackupProgress, val cancelling: Boolean = false) : DataBackupFlow

    /** Bilan à confirmer : résultat d'une restauration, issue partielle ou échec. */
    data class Report(val success: Boolean, val title: UiText, val lines: List<UiText>) : DataBackupFlow
}

/**
 * Sauvegarde et restauration des données depuis les réglages : mots de passe, sélecteurs système
 * (ouverts par l'écran), vérification et résumé, avancement et bilan. Quitter l'écran annule une
 * opération en cours (une restauration annulée ne modifie rien).
 */
@HiltViewModel
class DataBackupViewModel @Inject constructor(
    private val backups: DataBackupRepository,
) : ViewModel() {

    private val _flow = MutableStateFlow<DataBackupFlow>(DataBackupFlow.Idle)
    val flow: StateFlow<DataBackupFlow> = _flow.asStateFlow()

    /** Opération en cours : l'écran reste allumé. */
    val running: StateFlow<Boolean> = _flow
        .map { it is DataBackupFlow.Running }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val messages = Channel<UiText>(Channel.BUFFERED)

    /** Issues sans détail à confirmer, affichées en snackbar. */
    val events: Flow<UiText> = messages.receiveAsFlow()

    /** Mot de passe choisi, en attente de l'emplacement du fichier. */
    private var pendingPassword: CharArray? = null

    /** Sauvegarde choisie, en attente de son mot de passe. */
    private var pendingSource: Uri? = null

    /** Sauvegarde vérifiée, en attente de la confirmation de sa restauration. */
    private var inspected: InspectedBackup? = null

    @Volatile
    private var cancelRequested = false

    /** Numéro de l'opération en cours : une opération abandonnée ne touche plus à l'état. */
    @Volatile
    private var currentOperation = 0
    private var job: Job? = null

    // ── Sauvegarde ──────────────────────────────────────────────────────────────────

    fun requestBackup() {
        if (_flow.value == DataBackupFlow.Idle) _flow.value = DataBackupFlow.NewPassword
    }

    /** Mot de passe confirmé : l'écran ouvre ensuite le sélecteur de l'emplacement. */
    fun onPasswordChosen(password: CharArray) {
        pendingPassword?.fill('\u0000')
        pendingPassword = password
        _flow.value = DataBackupFlow.Idle
    }

    /** Document créé par le sélecteur, ou null s'il a été fermé sans choix. */
    fun onTargetChosen(target: Uri?) {
        val password = pendingPassword
        pendingPassword = null
        when {
            target == null -> password?.fill('\u0000')
            // Écran recréé entre-temps (processus arrêté) : mot de passe perdu, document vide retiré.
            password == null -> viewModelScope.launch { backups.discard(target) }
            else -> launchOperation(
                initial = DataBackupFlow.Running(restoring = false, BackupProgress(BackupProgress.Stage.PREPARING)),
                operation = { onProgress -> backups.export(target, password, ::isCancelRequested, onProgress) },
                outcome = ::exportOutcome,
            )
        }
    }

    // ── Restauration ────────────────────────────────────────────────────────────────

    fun onBackupChosen(source: Uri?) {
        if (source == null || _flow.value != DataBackupFlow.Idle) return
        pendingSource = source
        _flow.value = DataBackupFlow.BackupPassword()
    }

    /** Mot de passe saisi : la sauvegarde est vérifiée de bout en bout, puis résumée. */
    fun inspect(password: CharArray) {
        val source = pendingSource
        val current = _flow.value
        if (source == null || current !is DataBackupFlow.BackupPassword || current.checking) {
            password.fill('\u0000')
            return
        }
        launchOperation(
            initial = DataBackupFlow.BackupPassword(checking = true),
            operation = { _ -> backups.inspect(source, password, ::isCancelRequested) },
            outcome = ::inspectOutcome,
        )
    }

    /** Restauration confirmée depuis le résumé. */
    fun restore() {
        val backup = inspected
        if (backup == null || _flow.value !is DataBackupFlow.Summary) return
        launchOperation(
            initial = DataBackupFlow.Running(restoring = true, BackupProgress(BackupProgress.Stage.PREPARING)),
            operation = { onProgress -> backups.restore(backup, ::isCancelRequested, onProgress) },
            outcome = ::restoreOutcome,
        )
    }

    // ── Commun ──────────────────────────────────────────────────────────────────────

    /** Annulation demandée : prise en compte à l'enregistrement suivant. */
    fun cancel() {
        val running = _flow.value as? DataBackupFlow.Running ?: return
        if (running.progress.stage == BackupProgress.Stage.FINISHING) return
        cancelRequested = true
        _flow.update { (it as? DataBackupFlow.Running)?.copy(cancelling = true) ?: it }
    }

    /**
     * Ferme le dialogue affiché, sauf pendant une opération (seule [cancel] l'interrompt). Une
     * vérification en cours est abandonnée : lire un gros fichier chez un fournisseur en ligne peut
     * être long.
     */
    fun dismiss() {
        val current = _flow.value
        if (current is DataBackupFlow.Running) return
        if (current is DataBackupFlow.BackupPassword && current.checking) {
            currentOperation++
            job?.cancel()
        }
        forgetBackup()
        _flow.value = DataBackupFlow.Idle
    }

    /** Aucun sélecteur de documents sur l'appareil. */
    fun onPickerUnavailable() {
        pendingPassword?.fill('\u0000')
        pendingPassword = null
        messages.trySend(UiText.Res(R.string.backup_no_picker))
    }

    private fun <T> launchOperation(
        initial: DataBackupFlow,
        operation: suspend (onProgress: (BackupProgress) -> Unit) -> T,
        outcome: (T) -> DataBackupFlow,
    ) {
        val id = ++currentOperation
        cancelRequested = false
        _flow.value = initial
        job = viewModelScope.launch {
            val result = operation { progress -> if (currentOperation == id) onProgress(progress) }
            if (currentOperation == id) _flow.value = outcome(result) else (result as? DataInspectResult.Ready)?.backup?.close()
        }
    }

    private fun isCancelRequested(): Boolean = cancelRequested

    private fun onProgress(progress: BackupProgress) {
        _flow.update { current -> (current as? DataBackupFlow.Running)?.copy(progress = progress) ?: current }
    }

    private fun forgetBackup() {
        pendingSource = null
        inspected?.close()
        inspected = null
    }

    private fun exportOutcome(result: DataExportResult): DataBackupFlow = when (result) {
        is DataExportResult.Success -> {
            val contents = contentsOf(result.favorites, result.albums, result.people, result.hiddenFolders)
            messages.trySend(
                if (contents.isEmpty()) UiText.Res(R.string.data_backup_done_empty) else UiText.Joined(R.string.data_backup_done, contents),
            )
            DataBackupFlow.Idle
        }
        is DataExportResult.Cancelled -> if (result.partialFileLeft) {
            DataBackupFlow.Report(false, UiText.Res(R.string.backup_cancelled), listOf(UiText.Res(R.string.backup_partial_left)))
        } else {
            messages.trySend(UiText.Res(R.string.backup_cancelled))
            DataBackupFlow.Idle
        }
        is DataExportResult.Failed -> DataBackupFlow.Report(
            success = false,
            title = UiText.Res(R.string.backup_failed_title),
            lines = listOfNotNull(
                UiText.Res(R.string.backup_failed_message),
                UiText.Res(R.string.backup_partial_left).takeIf { result.partialFileLeft },
            ),
        )
    }

    private fun inspectOutcome(result: DataInspectResult): DataBackupFlow {
        // Mot de passe faux : la sauvegarde reste choisie pour un nouvel essai.
        if (result != DataInspectResult.WrongPassword) pendingSource = null
        return when (result) {
            DataInspectResult.WrongPassword -> DataBackupFlow.BackupPassword(wrong = true)
            is DataInspectResult.Ready -> {
                inspected?.close()
                inspected = result.backup
                DataBackupFlow.Summary(result.backup.summary)
            }
            DataInspectResult.Cancelled -> DataBackupFlow.Idle
            is DataInspectResult.Failed -> failure(result.reason)
        }
    }

    private fun restoreOutcome(result: DataRestoreResult): DataBackupFlow {
        forgetBackup()
        return when (result) {
            is DataRestoreResult.Success -> DataBackupFlow.Report(
                success = true,
                title = UiText.Res(R.string.restore_done_title),
                lines = reportLines(result.report),
            )
            DataRestoreResult.Cancelled -> {
                messages.trySend(UiText.Res(R.string.data_restore_cancelled))
                DataBackupFlow.Idle
            }
            is DataRestoreResult.Failed -> failure(result.reason)
        }
    }

    private fun failure(reason: DataBackupFailure) = DataBackupFlow.Report(
        success = false,
        title = UiText.Res(R.string.restore_failed_title),
        lines = listOf(
            UiText.Res(
                when (reason) {
                    DataBackupFailure.NOT_AN_ARCHIVE -> R.string.data_restore_not_archive
                    DataBackupFailure.VAULT_BACKUP -> R.string.data_restore_vault_backup
                    DataBackupFailure.UNSUPPORTED -> R.string.restore_unsupported
                    DataBackupFailure.CORRUPTED -> R.string.data_restore_corrupted
                    DataBackupFailure.NO_SPACE -> R.string.data_restore_no_space
                    DataBackupFailure.UNREADABLE -> R.string.data_restore_unreadable
                },
            ),
        ),
    )

    override fun onCleared() {
        pendingPassword?.fill('\u0000')
        pendingPassword = null
        forgetBackup()
    }

    companion object {
        /** Éléments non nuls d'un contenu : favoris, albums, personnes, dossiers masqués. */
        internal fun contentsOf(favorites: Int, albums: Int, people: Int, hiddenFolders: Int): List<UiText> = listOfNotNull(
            UiText.Plural(R.plurals.data_backup_favorites, favorites).takeIf { favorites > 0 },
            UiText.Plural(R.plurals.data_backup_albums, albums).takeIf { albums > 0 },
            UiText.Plural(R.plurals.data_backup_people, people).takeIf { people > 0 },
            UiText.Plural(R.plurals.data_backup_hidden_folders, hiddenFolders).takeIf { hiddenFolders > 0 },
        )

        /** Bilan d'une restauration : médias retrouvés, éléments restaurés, médias introuvables. */
        internal fun reportLines(report: DataRestoreReport): List<UiText> = buildList {
            if (report.media > 0) add(UiText.Plural(R.plurals.data_restore_found, report.media, listOf(report.matched, report.media)))
            val contents = contentsOf(report.favorites, report.albums, report.people, report.hiddenFolders)
            add(if (contents.isEmpty()) UiText.Res(R.string.data_restore_restored_settings) else UiText.Joined(R.string.data_restore_restored, contents))
            if (report.missing > 0) add(UiText.Plural(R.plurals.data_restore_missing, report.missing))
            if (!report.settings) add(UiText.Res(R.string.data_restore_settings_failed))
        }
    }
}

/** Réglage de la sauvegarde automatique des données, depuis les réglages ([AutoBackupSettingsViewModel]). */
@HiltViewModel
class DataAutoBackupViewModel @Inject constructor(
    autoBackup: DataAutoBackup,
) : AutoBackupSettingsViewModel(autoBackup, AutoBackupError::dataMessage)
