package app.focale.ui.vault

import androidx.lifecycle.viewModelScope
import app.focale.backup.AutoBackupError
import app.focale.ui.backup.AutoBackupSettingsViewModel
import app.focale.vault.VaultAutoBackup
import app.focale.vault.VaultSession
import app.focale.vault.vaultMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Réglage de la sauvegarde automatique, depuis le menu du coffre déverrouillé ([AutoBackupSettingsViewModel]).
 * Le coffre ne se verrouille pas pendant le choix du dossier ; verrouillé, il abandonne les saisies
 * en cours, pas les enregistrements lancés, et une action finie ne rouvre pas la feuille.
 */
@HiltViewModel
class VaultAutoBackupViewModel @Inject constructor(
    autoBackup: VaultAutoBackup,
    private val session: VaultSession,
) : AutoBackupSettingsViewModel(autoBackup, AutoBackupError::vaultMessage) {

    init {
        viewModelScope.launch {
            session.unlocked.filter { !it }.collect { abandonInputs() }
        }
    }

    override fun beforeSystemPicker() = session.expectSystemPicker()

    override fun canShowSettings(): Boolean = session.unlocked.value
}
