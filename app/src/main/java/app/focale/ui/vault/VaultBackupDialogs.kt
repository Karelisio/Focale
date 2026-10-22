package app.focale.ui.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.backup.BackupProgress
import app.focale.core.util.Formatters
import app.focale.ui.backup.BackupProgressDialog
import app.focale.ui.backup.BackupReportDialog
import app.focale.ui.backup.NewPasswordDialog
import app.focale.ui.backup.RestorePasswordDialog

/**
 * Dialogues de la sauvegarde et de la restauration du coffre, selon l'étape en cours.
 *
 * @param onPickTarget ouvre le sélecteur de l'emplacement, une fois le mot de passe choisi.
 */
@Composable
fun VaultBackupDialogs(viewModel: VaultBackupViewModel, onPickTarget: () -> Unit) {
    val flow by viewModel.flow.collectAsStateWithLifecycle()
    when (val current = flow) {
        BackupFlow.Idle -> Unit
        BackupFlow.NewPassword -> NewPasswordDialog(
            onConfirm = { password ->
                viewModel.onPasswordChosen(password)
                onPickTarget()
            },
            onDismiss = viewModel::dismiss,
            title = R.string.vault_backup_title,
            message = R.string.vault_backup_password_message,
        )
        is BackupFlow.ArchivePassword -> RestorePasswordDialog(
            wrong = current.wrong,
            checking = current.checking,
            title = R.string.vault_restore_title,
            message = R.string.vault_restore_password_message,
            onConfirm = viewModel::restore,
            onDismiss = viewModel::dismiss,
        )
        is BackupFlow.Running -> {
            val progress = current.progress
            BackupProgressDialog(
                icon = if (current.restoring) Icons.Outlined.SettingsBackupRestore else Icons.Outlined.Backup,
                title = stringResource(if (current.restoring) R.string.vault_restore_progress_title else R.string.vault_backup_progress_title),
                status = when (progress.stage) {
                    BackupProgress.Stage.PREPARING -> stringResource(R.string.backup_preparing)
                    BackupProgress.Stage.FINISHING -> stringResource(R.string.vault_restore_finishing)
                    BackupProgress.Stage.TRANSFERRING ->
                        pluralStringResource(R.plurals.vault_backup_progress_items, progress.total, progress.done, progress.total)
                },
                progress = progress,
                cancelling = current.cancelling,
                cancellable = progress.stage != BackupProgress.Stage.FINISHING,
                onCancel = viewModel::cancel,
            )
        }
        is BackupFlow.Report -> BackupReportDialog(current.success, current.title, current.lines, onDismiss = viewModel::dismiss)
    }
}

/**
 * Rappel discret en tête du coffre : invitation à sauvegarder tant qu'aucune sauvegarde
 * n'existe, puis simple date de la dernière.
 */
@Composable
fun BackupStatus(lastBackupAt: Long, onBackup: () -> Unit, modifier: Modifier = Modifier) {
    if (lastBackupAt == VaultBackupViewModel.NEVER) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = MaterialTheme.shapes.large,
            modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            ) {
                Icon(Icons.Outlined.Backup, contentDescription = null)
                Text(
                    text = stringResource(R.string.vault_backup_reminder),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBackup) { Text(stringResource(R.string.vault_backup_reminder_action)) }
            }
        }
    } else {
        val context = LocalContext.current
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.TaskAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.vault_backup_last, Formatters.dayMonthYear(context, lastBackupAt)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
