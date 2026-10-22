package app.focale.ui.backup

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.backup.AutoBackupError
import app.focale.backup.AutoBackupFrequency
import app.focale.backup.AutoBackupSettings
import app.focale.backup.BackupFolder
import app.focale.core.util.Formatters
import app.focale.ui.components.rememberNotificationPermission

/**
 * Réglage d'une sauvegarde automatique (coffre-fort ou données) : feuille du réglage, puis saisie
 * du mot de passe, protégée des captures.
 *
 * @param message présentation de cette sauvegarde, en tête de la feuille.
 * @param errorMessage explication d'un échec, propre à cette sauvegarde.
 * @param secure feuille protégée des captures, comme le reste du coffre.
 * @param onPickFolder ouvre le sélecteur de dossier du système, sur l'emplacement donné s'il y en a un.
 */
@Composable
fun AutoBackupDialogs(
    viewModel: AutoBackupSettingsViewModel,
    @StringRes message: Int,
    errorMessage: (AutoBackupError) -> Int,
    secure: Boolean,
    onPickFolder: (Uri?) -> Unit,
) {
    val flow by viewModel.flow.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Un échec de sauvegarde est signalé par une notification : permission demandée à l'activation.
    val requestNotifications = rememberNotificationPermission()
    LaunchedEffect(viewModel) { viewModel.activated.collect { requestNotifications() } }
    when (val current = flow) {
        AutoBackupFlow.Idle -> Unit
        is AutoBackupFlow.Settings -> AutoBackupSheet(
            settings = settings,
            busy = current.busy,
            notice = current.notice,
            message = message,
            errorMessage = errorMessage,
            secure = secure,
            onFrequencyChange = viewModel::setFrequency,
            onChangeFolder = {
                viewModel.beforeFolderChange()
                onPickFolder(settings.folder?.let(BackupFolder::folderDocument))
            },
            onChangePassword = viewModel::changePassword,
            onDismiss = viewModel::dismiss,
        )
        is AutoBackupFlow.NewPassword -> NewPasswordDialog(
            onConfirm = { password ->
                viewModel.onPasswordChosen(password)
                if (current.frequency != null) onPickFolder(null)
            },
            onDismiss = viewModel::cancelPassword,
            icon = Icons.Outlined.EventRepeat,
            title = R.string.auto_backup_title,
            message = R.string.auto_backup_password_message,
            confirmLabel = if (current.frequency != null) R.string.auto_backup_choose_folder else R.string.action_save,
        )
    }
}

/** Feuille du réglage ; elle ne se referme pas pendant un enregistrement. */
@Composable
private fun AutoBackupSheet(
    settings: AutoBackupSettings,
    busy: Boolean,
    notice: Notice?,
    @StringRes message: Int,
    errorMessage: (AutoBackupError) -> Int,
    secure: Boolean,
    onFrequencyChange: (AutoBackupFrequency) -> Unit,
    onChangeFolder: () -> Unit,
    onChangePassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentBusy by rememberUpdatedState(busy)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        confirmValueChange = { it != SheetValue.Hidden || !currentBusy },
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
    ) {
        AutoBackupSettingsContent(
            settings = settings,
            busy = busy,
            notice = notice,
            message = stringResource(message),
            errorMessage = errorMessage,
            onFrequencyChange = onFrequencyChange,
            onChangeFolder = onChangeFolder,
            onChangePassword = onChangePassword,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}

/**
 * Contenu de la feuille : état de la dernière sauvegarde, fréquence, dossier et mot de passe.
 * Séparé de la feuille pour les captures de référence.
 */
@Composable
fun AutoBackupSettingsContent(
    settings: AutoBackupSettings,
    busy: Boolean,
    notice: Notice?,
    message: String,
    errorMessage: (AutoBackupError) -> Int,
    onFrequencyChange: (AutoBackupFrequency) -> Unit,
    onChangeFolder: () -> Unit,
    onChangePassword: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.auto_backup_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(start = 8.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp),
        )
        if (settings.enabled) AutoBackupStatus(settings, errorMessage)
        notice?.let { NoticeLine(it) }
        if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp))
        SectionTitle(R.string.auto_backup_frequency)
        FREQUENCIES.forEachIndexed { index, (frequency, label, icon) ->
            val selected = settings.frequency == frequency
            SegmentedListItem(
                selected = selected,
                onClick = { if (!selected) onFrequencyChange(frequency) },
                enabled = !busy,
                shapes = ListItemDefaults.segmentedShapes(index, FREQUENCIES.size),
                leadingContent = { Icon(icon, contentDescription = null) },
                trailingContent = { RadioButton(selected = selected, onClick = null, enabled = !busy) },
            ) {
                Text(stringResource(label))
            }
        }
        if (settings.enabled) {
            SectionTitle(R.string.auto_backup_destination)
            SegmentedListItem(
                onClick = onChangeFolder,
                enabled = !busy,
                shapes = ListItemDefaults.segmentedShapes(0, 2),
                leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                supportingContent = { Text(settings.folderName ?: stringResource(R.string.auto_backup_folder_unknown)) },
            ) {
                Text(stringResource(R.string.auto_backup_folder))
            }
            SegmentedListItem(
                onClick = onChangePassword,
                enabled = !busy,
                shapes = ListItemDefaults.segmentedShapes(1, 2),
                leadingContent = { Icon(Icons.Outlined.Key, contentDescription = null) },
                supportingContent = { Text(stringResource(R.string.auto_backup_password_summary)) },
            ) {
                Text(stringResource(R.string.auto_backup_password))
            }
        }
    }
}

/** Dernière sauvegarde automatique, ou dernier échec et sa cause. */
@Composable
private fun AutoBackupStatus(settings: AutoBackupSettings, errorMessage: (AutoBackupError) -> Int) {
    val context = LocalContext.current
    val error = settings.error
    val failed = error != null
    Surface(
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(
                imageVector = when {
                    failed -> Icons.Outlined.ErrorOutline
                    settings.lastBackupAt != null -> Icons.Outlined.TaskAlt
                    else -> Icons.Outlined.Schedule
                },
                contentDescription = null,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (error != null) {
                    settings.errorAt?.let { at ->
                        Text(
                            text = stringResource(R.string.auto_backup_failed_on, Formatters.dayMonthYear(context, at)),
                            style = MaterialTheme.typography.titleSmall,
                        )
                    }
                    Text(stringResource(errorMessage(error)), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(
                        text = settings.lastBackupAt?.let { stringResource(R.string.auto_backup_last, Formatters.dayMonthYear(context, it)) }
                            ?: stringResource(R.string.auto_backup_pending),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeLine(notice: Notice) {
    Text(
        text = notice.text.asString(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (notice.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SectionTitle(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMediumEmphasized,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
    )
}

private val FREQUENCIES = listOf(
    Triple(AutoBackupFrequency.OFF, R.string.auto_backup_off, Icons.Outlined.EventBusy),
    Triple(AutoBackupFrequency.DAILY, R.string.auto_backup_daily, Icons.Outlined.Today),
    Triple(AutoBackupFrequency.WEEKLY, R.string.auto_backup_weekly, Icons.Outlined.CalendarViewWeek),
)

/** Icône des entrées qui ouvrent le réglage (menu du coffre, réglages). */
val AutoBackupIcon: ImageVector get() = Icons.Outlined.EventRepeat
