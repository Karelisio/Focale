package app.focale.ui.vault

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.R
import app.focale.core.util.UiText
import app.focale.vault.BackupProgress
import app.focale.vault.ExportResult
import app.focale.vault.RestoreResult
import app.focale.vault.VaultBackupPrefs
import app.focale.vault.VaultBackupRepository
import app.focale.vault.VaultSession
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Étape d'une sauvegarde ou d'une restauration ; chaque étape hors [Idle] a son dialogue. */
@Immutable
sealed interface BackupFlow {
    data object Idle : BackupFlow

    /** Choix du mot de passe d'une nouvelle sauvegarde. */
    data object NewPassword : BackupFlow

    /** Mot de passe de l'archive choisie : [wrong] après un refus, [checking] pendant la vérification. */
    data class ArchivePassword(val wrong: Boolean = false, val checking: Boolean = false) : BackupFlow

    data class Running(val restoring: Boolean, val progress: BackupProgress, val cancelling: Boolean = false) : BackupFlow

    /** Bilan à confirmer : résumé d'une restauration, issue partielle ou échec. */
    data class Report(val success: Boolean, val title: UiText, val lines: List<UiText>) : BackupFlow
}

/**
 * Sauvegarde et restauration depuis l'écran du coffre : mots de passe, sélecteurs système
 * (ouverts par l'écran), avancement et bilan.
 *
 * Une opération lancée coffre déverrouillé va à son terme même si le coffre se verrouille
 * en chemin (l'avancement réapparaît au déverrouillage) ; quitter l'écran l'annule.
 */
@HiltViewModel
class VaultBackupViewModel @Inject constructor(
    private val backups: VaultBackupRepository,
    private val session: VaultSession,
    prefs: VaultBackupPrefs,
) : ViewModel() {

    /** Dernière sauvegarde (ms) : null tant qu'elle n'est pas lue, [NEVER] s'il n'y en a aucune. */
    val lastBackupAt: StateFlow<Long?> = prefs.lastBackupAt
        .map { it ?: NEVER }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _flow = MutableStateFlow<BackupFlow>(BackupFlow.Idle)
    val flow: StateFlow<BackupFlow> = _flow.asStateFlow()

    /** Opération en cours, quel que soit l'avancement : l'écran reste allumé. */
    val running: StateFlow<Boolean> = _flow
        .map { it is BackupFlow.Running }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val messages = Channel<UiText>(Channel.BUFFERED)

    /** Issues sans détail à confirmer, affichées en snackbar. */
    val events: Flow<UiText> = messages.receiveAsFlow()

    /** Mot de passe choisi, en attente de l'emplacement du fichier. */
    private var pendingPassword: CharArray? = null

    /** Archive choisie, en attente de son mot de passe. */
    private var pendingArchive: Uri? = null

    @Volatile
    private var cancelRequested = false

    /** Numéro de l'opération en cours : une opération abandonnée ne touche plus à l'état. */
    @Volatile
    private var currentOperation = 0
    private var job: Job? = null

    init {
        // Verrouillage : les saisies en cours sont abandonnées, pas les opérations lancées.
        viewModelScope.launch {
            session.unlocked.filter { !it }.collect {
                val current = _flow.value
                if (current == BackupFlow.NewPassword || (current is BackupFlow.ArchivePassword && !current.checking)) {
                    pendingArchive = null
                    _flow.value = BackupFlow.Idle
                }
            }
        }
    }

    // ── Sauvegarde ──────────────────────────────────────────────────────────────────

    fun requestBackup() {
        if (_flow.value == BackupFlow.Idle) _flow.value = BackupFlow.NewPassword
    }

    /** Mot de passe confirmé : l'écran ouvre ensuite le sélecteur de l'emplacement. */
    fun onPasswordChosen(password: CharArray) {
        pendingPassword?.fill('\u0000')
        pendingPassword = password
        _flow.value = BackupFlow.Idle
        session.expectSystemPicker()
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
                initial = BackupFlow.Running(restoring = false, BackupProgress(BackupProgress.Stage.PREPARING)),
                operation = { onProgress -> backups.export(target, password, ::isCancelRequested, onProgress) },
                outcome = ::exportOutcome,
            )
        }
    }

    // ── Restauration ────────────────────────────────────────────────────────────────

    /** Juste avant d'ouvrir le sélecteur de l'archive à restaurer. */
    fun beforeRestorePicker() = session.expectSystemPicker()

    fun onArchiveChosen(source: Uri?) {
        if (source == null || _flow.value != BackupFlow.Idle) return
        pendingArchive = source
        _flow.value = BackupFlow.ArchivePassword()
    }

    fun restore(password: CharArray) {
        val source = pendingArchive
        val current = _flow.value
        if (source == null || current !is BackupFlow.ArchivePassword || current.checking) {
            password.fill('\u0000')
            return
        }
        launchOperation(
            initial = BackupFlow.ArchivePassword(checking = true),
            operation = { onProgress -> backups.restore(source, password, ::isCancelRequested, onProgress) },
            outcome = ::restoreOutcome,
        )
    }

    // ── Commun ──────────────────────────────────────────────────────────────────────

    /** Annulation demandée : prise en compte au morceau suivant. */
    fun cancel() {
        val running = _flow.value as? BackupFlow.Running ?: return
        if (running.progress.stage == BackupProgress.Stage.FINISHING) return
        cancelRequested = true
        _flow.update { (it as? BackupFlow.Running)?.copy(cancelling = true) ?: it }
    }

    /**
     * Ferme le dialogue affiché, sauf pendant une opération (seule [cancel] l'interrompt).
     * Une vérification d'archive en cours est abandonnée : lire un gros fichier chez un
     * fournisseur en ligne peut être long.
     */
    fun dismiss() {
        val current = _flow.value
        if (current is BackupFlow.Running) return
        if (current is BackupFlow.ArchivePassword && current.checking) {
            currentOperation++
            job?.cancel()
        }
        pendingArchive = null
        _flow.value = BackupFlow.Idle
    }

    /** Aucun sélecteur de documents sur l'appareil. */
    fun onPickerUnavailable() {
        pendingPassword?.fill('\u0000')
        pendingPassword = null
        messages.trySend(UiText.Res(R.string.vault_backup_no_picker))
    }

    /**
     * Lance [operation] avec un suivi d'avancement qui lui est propre, puis affiche son
     * [outcome] ; rien de tout cela pour une opération abandonnée entre-temps.
     */
    private fun <T> launchOperation(
        initial: BackupFlow,
        operation: suspend (onProgress: (BackupProgress) -> Unit) -> T,
        outcome: (T) -> BackupFlow,
    ) {
        val id = ++currentOperation
        cancelRequested = false
        _flow.value = initial
        job = viewModelScope.launch {
            val result = operation { progress -> if (currentOperation == id) onProgress(progress) }
            if (currentOperation == id) _flow.value = outcome(result)
        }
    }

    private fun isCancelRequested(): Boolean = cancelRequested

    /** Le premier avancement d'une restauration annonce un mot de passe accepté. */
    private fun onProgress(progress: BackupProgress) {
        if (progress.stage == BackupProgress.Stage.PREPARING) return
        _flow.update { current ->
            when (current) {
                is BackupFlow.Running -> current.copy(progress = progress)
                is BackupFlow.ArchivePassword -> BackupFlow.Running(restoring = true, progress = progress, cancelling = cancelRequested)
                else -> current
            }
        }
    }

    private fun exportOutcome(result: ExportResult): BackupFlow = when (result) {
        is ExportResult.Success -> if (result.unreadable == 0) {
            messages.trySend(UiText.Plural(R.plurals.vault_backup_done, result.saved))
            BackupFlow.Idle
        } else {
            BackupFlow.Report(
                success = true,
                title = UiText.Res(R.string.vault_backup_done_title),
                lines = listOf(
                    UiText.Plural(R.plurals.vault_backup_saved, result.saved),
                    UiText.Plural(R.plurals.vault_backup_unreadable, result.unreadable),
                ),
            )
        }
        is ExportResult.Cancelled -> if (result.partialFileLeft) {
            BackupFlow.Report(
                success = false,
                title = UiText.Res(R.string.vault_backup_cancelled),
                lines = listOf(UiText.Res(R.string.vault_backup_partial_left)),
            )
        } else {
            messages.trySend(UiText.Res(R.string.vault_backup_cancelled))
            BackupFlow.Idle
        }
        is ExportResult.Failed -> BackupFlow.Report(
            success = false,
            title = UiText.Res(R.string.vault_backup_failed_title),
            lines = listOfNotNull(
                UiText.Res(R.string.vault_backup_failed_message),
                UiText.Res(R.string.vault_backup_partial_left).takeIf { result.partialFileLeft },
            ),
        )
    }

    private fun restoreOutcome(result: RestoreResult): BackupFlow {
        // Mot de passe faux : l'archive reste choisie pour un nouvel essai.
        if (result != RestoreResult.WrongPassword) pendingArchive = null
        return when (result) {
            RestoreResult.WrongPassword -> BackupFlow.ArchivePassword(wrong = true)
            is RestoreResult.Success -> BackupFlow.Report(
                success = true,
                title = UiText.Res(R.string.vault_restore_done_title),
                lines = buildList {
                    add(UiText.Plural(R.plurals.vault_restore_added, result.added))
                    if (result.duplicates > 0) add(UiText.Plural(R.plurals.vault_restore_duplicates, result.duplicates))
                    if (result.unreadable > 0) add(UiText.Plural(R.plurals.vault_restore_unreadable, result.unreadable))
                },
            )
            RestoreResult.Cancelled -> {
                messages.trySend(UiText.Res(R.string.vault_restore_cancelled))
                BackupFlow.Idle
            }
            is RestoreResult.Failed -> BackupFlow.Report(
                success = false,
                title = UiText.Res(R.string.vault_restore_failed_title),
                lines = listOf(
                    UiText.Res(
                        when (result.reason) {
                            RestoreResult.Failed.Reason.NOT_AN_ARCHIVE -> R.string.vault_restore_not_archive
                            RestoreResult.Failed.Reason.UNSUPPORTED -> R.string.vault_restore_unsupported
                            RestoreResult.Failed.Reason.CORRUPTED -> R.string.vault_restore_corrupted
                            RestoreResult.Failed.Reason.NO_SPACE -> R.string.vault_restore_no_space
                            RestoreResult.Failed.Reason.UNREADABLE -> R.string.vault_restore_unreadable_file
                        },
                    ),
                ),
            )
        }
    }

    override fun onCleared() {
        pendingPassword?.fill('\u0000')
        pendingPassword = null
    }

    companion object {
        const val NEVER = 0L
    }
}
