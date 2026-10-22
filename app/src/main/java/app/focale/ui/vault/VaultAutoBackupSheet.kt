package app.focale.ui.vault

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.focale.R
import app.focale.backup.AutoBackupError
import app.focale.backup.AutoBackupFrequency
import app.focale.backup.AutoBackupSettings
import app.focale.ui.backup.AutoBackupDialogs
import app.focale.ui.backup.AutoBackupSettingsContent
import app.focale.ui.backup.Notice
import app.focale.vault.vaultMessage

/**
 * Réglage de la sauvegarde automatique, ouvert depuis le menu du coffre : feuille du réglage,
 * puis saisie du mot de passe ; l'une et l'autre protégées des captures, comme le reste du coffre.
 *
 * @param onPickFolder ouvre le sélecteur de dossier du système, sur l'emplacement donné s'il y en a un.
 */
@Composable
fun VaultAutoBackupDialogs(viewModel: VaultAutoBackupViewModel, onPickFolder: (Uri?) -> Unit) {
    AutoBackupDialogs(
        viewModel = viewModel,
        message = R.string.vault_auto_backup_message,
        errorMessage = AutoBackupError::vaultMessage,
        secure = true,
        onPickFolder = onPickFolder,
    )
}

/** Contenu de la feuille du réglage du coffre, pour les captures de référence. */
@Composable
internal fun VaultAutoBackupSettingsContent(
    settings: AutoBackupSettings,
    busy: Boolean,
    notice: Notice?,
    onFrequencyChange: (AutoBackupFrequency) -> Unit,
    onChangeFolder: () -> Unit,
    onChangePassword: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AutoBackupSettingsContent(
        settings = settings,
        busy = busy,
        notice = notice,
        message = stringResource(R.string.vault_auto_backup_message),
        errorMessage = AutoBackupError::vaultMessage,
        onFrequencyChange = onFrequencyChange,
        onChangeFolder = onChangeFolder,
        onChangePassword = onChangePassword,
        modifier = modifier,
    )
}
