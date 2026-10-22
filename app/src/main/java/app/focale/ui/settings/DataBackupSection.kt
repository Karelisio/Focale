package app.focale.ui.settings

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.backup.AutoBackupError
import app.focale.backup.AutoBackupFrequency
import app.focale.backup.AutoBackupSettings
import app.focale.backup.BackupProgress
import app.focale.backup.DataBackupSummary
import app.focale.backup.DataCategory
import app.focale.backup.PasswordArchive
import app.focale.backup.dataMessage
import app.focale.core.util.Formatters
import app.focale.core.util.UiText
import app.focale.ui.backup.AutoBackupDialogs
import app.focale.ui.backup.AutoBackupIcon
import app.focale.ui.backup.BackupProgressDialog
import app.focale.ui.backup.BackupReportDialog
import app.focale.ui.backup.KeepScreenOn
import app.focale.ui.backup.NewPasswordDialog
import app.focale.ui.backup.RestorePasswordDialog
import java.time.LocalDate

/** Actions de la section « Sauvegarde des données », reliées aux sélecteurs système par [rememberDataBackup]. */
@Stable
class DataBackupActions(val onBackup: () -> Unit, val onRestore: () -> Unit, val onAutoBackup: () -> Unit)

/**
 * Sélecteurs système et dialogues de la sauvegarde des données, déclarés hors de la liste des
 * réglages : leur résultat arrive même si la section n'est plus composée. Renvoie les actions de
 * la section ; [onMessage] affiche les issues sans détail (snackbar).
 */
@Composable
fun rememberDataBackup(
    backupViewModel: DataBackupViewModel,
    autoBackupViewModel: DataAutoBackupViewModel,
    onMessage: (UiText) -> Unit,
): DataBackupActions {
    val resources = LocalResources.current
    val currentOnMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(backupViewModel) { backupViewModel.events.collect { currentOnMessage(it) } }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PasswordArchive.MIME_TYPE),
        backupViewModel::onTargetChosen,
    )
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), backupViewModel::onBackupChosen)
    val openFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), autoBackupViewModel::onFolderChosen)
    val pickTarget: () -> Unit = {
        try {
            // Nom proposé du jour de la sauvegarde, d'où la résolution au moment du clic.
            createBackup.launch(resources.getString(R.string.data_backup_file_name, LocalDate.now().toString()))
        } catch (error: ActivityNotFoundException) {
            backupViewModel.onPickerUnavailable()
        }
    }
    val pickFolder: (Uri?) -> Unit = { initial ->
        try {
            openFolder.launch(initial)
        } catch (error: ActivityNotFoundException) {
            autoBackupViewModel.onPickerUnavailable()
        }
    }

    DataBackupDialogs(viewModel = backupViewModel, onPickTarget = pickTarget)
    AutoBackupDialogs(
        viewModel = autoBackupViewModel,
        message = R.string.data_backup_auto_message,
        errorMessage = AutoBackupError::dataMessage,
        secure = false,
        onPickFolder = pickFolder,
    )
    val running by backupViewModel.running.collectAsStateWithLifecycle()
    if (running) KeepScreenOn()

    return remember(backupViewModel, autoBackupViewModel, openBackup) {
        DataBackupActions(
            onBackup = backupViewModel::requestBackup,
            onRestore = {
                try {
                    // Tous types : selon le fournisseur, une sauvegarde `.focale` n'a pas toujours le même.
                    openBackup.launch(arrayOf("*/*"))
                } catch (error: ActivityNotFoundException) {
                    backupViewModel.onPickerUnavailable()
                }
            },
            onAutoBackup = autoBackupViewModel::open,
        )
    }
}

/** Section des réglages : sauvegarder maintenant, restaurer, sauvegarde automatique (et son état). */
@Composable
fun DataBackupSection(autoBackup: AutoBackupSettings, actions: DataBackupActions) {
    SettingsGroup(stringResource(R.string.data_backup_section)) {
        item { shapes ->
            SettingsAction(
                title = stringResource(R.string.data_backup_now),
                subtitle = stringResource(R.string.data_backup_now_summary),
                icon = Icons.Outlined.Backup,
                shapes = shapes,
                onClick = actions.onBackup,
            )
        }
        item { shapes ->
            SettingsAction(
                title = stringResource(R.string.data_backup_restore),
                subtitle = stringResource(R.string.data_backup_restore_summary),
                icon = Icons.Outlined.SettingsBackupRestore,
                shapes = shapes,
                onClick = actions.onRestore,
            )
        }
        item { shapes ->
            SettingsAction(
                title = stringResource(R.string.auto_backup_title),
                subtitle = autoBackupSummary(autoBackup),
                icon = AutoBackupIcon,
                shapes = shapes,
                onClick = actions.onAutoBackup,
            )
        }
    }
}

/** État de la sauvegarde automatique : désactivée, fréquence et dossier, ou dernier échec. */
@Composable
private fun autoBackupSummary(settings: AutoBackupSettings): String {
    val context = LocalContext.current
    val errorAt = settings.errorAt
    return when {
        !settings.enabled -> stringResource(R.string.auto_backup_off)
        settings.error != null && errorAt != null -> stringResource(R.string.auto_backup_failed_on, Formatters.dayMonthYear(context, errorAt))
        else -> stringResource(
            R.string.data_backup_auto_summary,
            stringResource(if (settings.frequency == AutoBackupFrequency.WEEKLY) R.string.auto_backup_weekly else R.string.auto_backup_daily),
            settings.folderName ?: stringResource(R.string.auto_backup_folder_unknown),
        )
    }
}

/**
 * Dialogues de la sauvegarde et de la restauration des données, selon l'étape en cours.
 *
 * @param onPickTarget ouvre le sélecteur de l'emplacement, une fois le mot de passe choisi.
 */
@Composable
private fun DataBackupDialogs(viewModel: DataBackupViewModel, onPickTarget: () -> Unit) {
    val flow by viewModel.flow.collectAsStateWithLifecycle()
    when (val current = flow) {
        DataBackupFlow.Idle -> Unit
        DataBackupFlow.NewPassword -> NewPasswordDialog(
            onConfirm = { password ->
                viewModel.onPasswordChosen(password)
                onPickTarget()
            },
            onDismiss = viewModel::dismiss,
            title = R.string.data_backup_title,
            message = R.string.data_backup_password_message,
        )
        is DataBackupFlow.BackupPassword -> RestorePasswordDialog(
            wrong = current.wrong,
            checking = current.checking,
            title = R.string.data_restore_title,
            message = R.string.data_restore_password_message,
            onConfirm = viewModel::inspect,
            onDismiss = viewModel::dismiss,
            checkingText = R.string.data_restore_checking,
        )
        is DataBackupFlow.Summary -> BasicAlertDialog(onDismissRequest = viewModel::dismiss) {
            RestoreSummaryCard(
                summary = current.summary,
                chosen = current.chosen,
                onToggle = viewModel::toggle,
                onConfirm = viewModel::restore,
                onDismiss = viewModel::dismiss,
            )
        }
        is DataBackupFlow.Running -> {
            val progress = current.progress
            BackupProgressDialog(
                icon = if (current.restoring) Icons.Outlined.SettingsBackupRestore else Icons.Outlined.Backup,
                title = stringResource(if (current.restoring) R.string.data_restore_progress_title else R.string.data_backup_progress_title),
                status = when (progress.stage) {
                    BackupProgress.Stage.PREPARING -> stringResource(if (current.restoring) R.string.data_restore_preparing else R.string.backup_preparing)
                    BackupProgress.Stage.FINISHING -> stringResource(R.string.data_restore_finishing)
                    BackupProgress.Stage.TRANSFERRING ->
                        pluralStringResource(R.plurals.data_backup_progress_items, progress.total, progress.done, progress.total)
                },
                progress = progress,
                cancelling = current.cancelling,
                cancellable = progress.stage != BackupProgress.Stage.FINISHING,
                onCancel = viewModel::cancel,
            )
        }
        is DataBackupFlow.Report -> BackupReportDialog(current.success, current.title, current.lines, onDismiss = viewModel::dismiss)
    }
}

/**
 * Résumé d'une sauvegarde vérifiée, à confirmer avant de la restaurer : sa date, puis une case
 * par catégorie qu'elle contient ([DataBackupSummary.categories], toutes cochées d'abord), avec ce
 * que la restauration en fait (fusion ou remplacement) ; la note sur les photos à copier ne vient
 * que si une catégorie cochée désigne des médias. Carte du dialogue, séparée pour les captures de
 * référence ; mêmes forme, couleurs et marges qu'un `AlertDialog`.
 *
 * @param chosen catégories cochées ; [onToggle] coche ou décoche, « Restaurer » attend au moins une case.
 */
@Composable
internal fun RestoreSummaryCard(
    summary: DataBackupSummary,
    chosen: Set<DataCategory>,
    onToggle: (DataCategory) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
        modifier = modifier,
    ) {
        Column(Modifier.padding(24.dp)) {
            Icon(
                imageVector = Icons.Outlined.SettingsBackupRestore,
                contentDescription = null,
                tint = AlertDialogDefaults.iconContentColor,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.data_restore_summary_title, Formatters.dayMonthYear(context, summary.createdAt)),
                style = MaterialTheme.typography.headlineSmall,
                color = AlertDialogDefaults.titleContentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.data_restore_summary_version, summary.appVersion),
                style = MaterialTheme.typography.bodySmall,
                color = AlertDialogDefaults.textContentColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(top = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (category in DataCategory.entries) {
                    if (category !in summary.categories) continue
                    CategoryChoice(
                        label = categoryLabel(category, summary),
                        explanation = categoryExplanation(category, summary),
                        checked = category in chosen,
                        onToggle = { onToggle(category) },
                    )
                }
                if (chosen.any { it.needsMedia }) {
                    Text(
                        text = stringResource(R.string.data_restore_summary_media),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlertDialogDefaults.textContentColor,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = onConfirm, enabled = chosen.isNotEmpty()) { Text(stringResource(R.string.action_restore)) }
            }
        }
    }
}

/** Ce que contient la sauvegarde pour [category] (« 12 albums », « Réglages »). */
@Composable
private fun categoryLabel(category: DataCategory, summary: DataBackupSummary): String = when (category) {
    DataCategory.FAVORITES -> pluralStringResource(R.plurals.data_backup_favorites, summary.favorites, summary.favorites)
    DataCategory.ALBUMS -> pluralStringResource(R.plurals.data_backup_albums, summary.albums, summary.albums)
    DataCategory.PEOPLE -> if (summary.people > 0) {
        pluralStringResource(R.plurals.data_backup_people, summary.people, summary.people)
    } else {
        pluralStringResource(R.plurals.data_restore_faces, summary.faces, summary.faces)
    }
    DataCategory.HIDDEN_FOLDERS -> pluralStringResource(R.plurals.data_backup_hidden_folders, summary.hiddenFolders, summary.hiddenFolders)
    DataCategory.SETTINGS -> stringResource(R.string.data_restore_settings_label)
    DataCategory.SEARCH_HISTORY -> pluralStringResource(R.plurals.data_restore_searches, summary.searches, summary.searches)
    DataCategory.ORIGINAL_DATES -> pluralStringResource(R.plurals.data_restore_dates, summary.dates, summary.dates)
}

/** Ce que la restauration fait de [category] : ajout, réunion ou remplacement. */
@Composable
private fun categoryExplanation(category: DataCategory, summary: DataBackupSummary): String = when (category) {
    DataCategory.FAVORITES, DataCategory.HIDDEN_FOLDERS -> stringResource(R.string.data_restore_choice_added)
    DataCategory.ALBUMS -> stringResource(R.string.data_restore_choice_albums)
    DataCategory.PEOPLE -> if (summary.existingPeople > 0) {
        pluralStringResource(R.plurals.data_restore_choice_people_replace, summary.existingPeople, summary.existingPeople)
    } else {
        stringResource(R.string.data_restore_choice_people_new)
    }
    DataCategory.SETTINGS -> stringResource(R.string.data_restore_choice_settings)
    DataCategory.SEARCH_HISTORY -> stringResource(R.string.data_restore_choice_searches)
    DataCategory.ORIGINAL_DATES -> stringResource(R.string.data_restore_choice_dates)
}

/** Case d'une catégorie : toute la ligne la coche ou la décoche (TalkBack : case à cocher). */
@Composable
private fun CategoryChoice(label: String, explanation: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.padding(top = 12.dp, end = 4.dp, bottom = 10.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = AlertDialogDefaults.titleContentColor)
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = AlertDialogDefaults.textContentColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
