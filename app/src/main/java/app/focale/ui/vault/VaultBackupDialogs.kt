package app.focale.ui.vault

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.core.util.Formatters
import app.focale.vault.BackupPassword
import app.focale.vault.BackupProgress
import app.focale.vault.PasswordStrength

/**
 * Dialogues de la sauvegarde et de la restauration, selon l'étape en cours.
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
        )
        is BackupFlow.ArchivePassword -> ArchivePasswordDialog(current, onConfirm = viewModel::restore, onDismiss = viewModel::dismiss)
        is BackupFlow.Running -> BackupProgressDialog(current, onCancel = viewModel::cancel)
        is BackupFlow.Report -> BackupReportDialog(current, onDismiss = viewModel::dismiss)
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

/** Écran maintenu allumé pendant une opération longue : sa mise en veille verrouillerait le coffre. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Mot de passe d'une nouvelle sauvegarde, saisi deux fois, avec les règles et l'indicateur de
 * robustesse de [BackupPassword] ; aussi pour les sauvegardes automatiques, d'où les textes et
 * l'icône en paramètres. Protégé des captures, quelle que soit la fenêtre qui l'ouvre.
 *
 * Les champs sont mémorisés sans sauvegarde d'état (`remember` et non `rememberSaveable`) :
 * le mot de passe ne doit pas finir dans l'état d'instance conservé par le système.
 */
@Composable
internal fun NewPasswordDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    icon: ImageVector = Icons.Outlined.Backup,
    @StringRes title: Int = R.string.vault_backup_title,
    @StringRes message: Int = R.string.vault_backup_password_message,
    @StringRes confirmLabel: Int = R.string.vault_backup_choose_location,
) {
    val password = remember { TextFieldState() }
    val confirmation = remember { TextFieldState() }
    val strength = BackupPassword.strength(password.text)
    val matches = password.text.contentEquals(confirmation.text)
    val mismatch = confirmation.text.isNotEmpty() && !matches
    val valid = BackupPassword.isValid(password.text) && matches
    val submit = {
        if (valid) {
            onConfirm(password.text.toSecret())
            password.clearText()
            confirmation.clearText()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(icon, contentDescription = null) },
        title = { Text(stringResource(title)) },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(message))
                PasswordField(
                    state = password,
                    labelText = stringResource(R.string.vault_backup_password),
                    imeAction = ImeAction.Next,
                    supportingText = { StrengthMeter(strength) },
                )
                PasswordField(
                    state = confirmation,
                    labelText = stringResource(R.string.vault_backup_password_confirm),
                    imeAction = ImeAction.Done,
                    isError = mismatch,
                    onDone = submit,
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.vault_backup_password_mismatch)) }
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = valid) { Text(stringResource(confirmLabel)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Mot de passe de l'archive à restaurer ; reste ouvert, avec un message, s'il est faux.
 * « Annuler » abandonne aussi une vérification en cours.
 */
@Composable
private fun ArchivePasswordDialog(state: BackupFlow.ArchivePassword, onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    val password = remember { TextFieldState() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val canSubmit = password.text.isNotEmpty() && !state.checking
    val submit = { if (canSubmit) onConfirm(password.text.toSecret()) }
    AlertDialog(
        // Un appui à côté n'interrompt pas une vérification : seul « Annuler » le fait.
        onDismissRequest = { if (!state.checking) onDismiss() },
        icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
        title = { Text(stringResource(R.string.vault_restore_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.vault_restore_password_message))
                PasswordField(
                    state = password,
                    labelText = stringResource(R.string.vault_restore_password),
                    imeAction = ImeAction.Done,
                    enabled = !state.checking,
                    isError = state.wrong && !state.checking,
                    onDone = submit,
                    supportingText = when {
                        state.checking -> {
                            { Text(stringResource(R.string.vault_restore_checking)) }
                        }
                        state.wrong -> {
                            { Text(stringResource(R.string.vault_restore_wrong_password)) }
                        }
                        else -> null
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canSubmit) {
                if (state.checking) {
                    LoadingIndicator(Modifier.size(24.dp))
                } else {
                    Text(stringResource(R.string.action_restore))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Avancement, non refermable : seul « Annuler » interrompt l'opération (fichier partiel retiré). */
@Composable
private fun BackupProgressDialog(state: BackupFlow.Running, onCancel: () -> Unit) {
    val context = LocalContext.current
    val progress = state.progress
    val transferring = progress.stage == BackupProgress.Stage.TRANSFERRING
    BasicAlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp)) {
                BusyEmblem(if (state.restoring) Icons.Outlined.SettingsBackupRestore else Icons.Outlined.Backup)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(if (state.restoring) R.string.vault_restore_progress_title else R.string.vault_backup_progress_title),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        state.cancelling -> stringResource(R.string.vault_backup_cancelling)
                        progress.stage == BackupProgress.Stage.PREPARING -> stringResource(R.string.vault_backup_preparing)
                        progress.stage == BackupProgress.Stage.FINISHING -> stringResource(R.string.vault_restore_finishing)
                        else -> pluralStringResource(R.plurals.vault_backup_progress_items, progress.total, progress.done, progress.total)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(20.dp))
                if (transferring) {
                    val fraction by animateFloatAsState(progress.fraction, label = "backupProgress")
                    LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (transferring && progress.bytesTotal > 0) {
                    Text(
                        text = stringResource(
                            R.string.vault_backup_progress_bytes,
                            Formatters.fileSize(context, progress.bytesDone),
                            Formatters.fileSize(context, progress.bytesTotal),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.vault_backup_keep_open),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    TextButton(
                        onClick = onCancel,
                        enabled = !state.cancelling && progress.stage != BackupProgress.Stage.FINISHING,
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun BackupReportDialog(report: BackupFlow.Report, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (report.success) Icons.Outlined.TaskAlt else Icons.Outlined.ErrorOutline, contentDescription = null) },
        title = { Text(report.title.asString()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                report.lines.forEach { Text(it.asString()) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** Champ de mot de passe masqué, avec bouton pour l'afficher. */
@Composable
private fun PasswordField(
    state: TextFieldState,
    labelText: String,
    imeAction: ImeAction,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    onDone: () -> Unit = {},
    supportingText: (@Composable () -> Unit)? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedSecureTextField(
        state = state,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        label = { Text(labelText) },
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = stringResource(if (visible) R.string.vault_backup_hide_password else R.string.vault_backup_show_password),
                )
            }
        },
        supportingText = supportingText,
        isError = isError,
        inputTransformation = InputTransformation.maxLength(BackupPassword.MAX_LENGTH),
        textObfuscationMode = if (visible) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        onKeyboardAction = { performDefault -> if (imeAction == ImeAction.Done) onDone() else performDefault() },
    )
}

/** Indicateur simple : trois segments colorés et un libellé. */
@Composable
private fun StrengthMeter(strength: PasswordStrength) {
    val level = when (strength) {
        PasswordStrength.TOO_SHORT -> 0
        PasswordStrength.WEAK -> 1
        PasswordStrength.FAIR -> 2
        PasswordStrength.STRONG -> 3
    }
    val color by animateColorAsState(
        targetValue = when (strength) {
            PasswordStrength.TOO_SHORT, PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
            PasswordStrength.FAIR -> MaterialTheme.colorScheme.tertiary
            PasswordStrength.STRONG -> MaterialTheme.colorScheme.primary
        },
        label = "strengthColor",
    )
    Column(Modifier.padding(top = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(if (index < level) color else MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
        Text(
            text = when (strength) {
                PasswordStrength.TOO_SHORT -> pluralStringResource(
                    R.plurals.vault_backup_password_min,
                    BackupPassword.MIN_LENGTH,
                    BackupPassword.MIN_LENGTH,
                )
                PasswordStrength.WEAK -> stringResource(R.string.vault_backup_strength_weak)
                PasswordStrength.FAIR -> stringResource(R.string.vault_backup_strength_fair)
                PasswordStrength.STRONG -> stringResource(R.string.vault_backup_strength_strong)
            },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Forme Expressive en rotation lente derrière l'icône, le temps de l'opération. */
@Composable
private fun BusyEmblem(icon: ImageVector) {
    val rotation by rememberInfiniteTransition(label = "emblem").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 8_000, easing = LinearEasing)),
        label = "emblemRotation",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotation }
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
    }
}

/** Copie modifiable du texte saisi, effacée par son destinataire après usage. */
private fun CharSequence.toSecret(): CharArray = CharArray(length) { this[it] }
