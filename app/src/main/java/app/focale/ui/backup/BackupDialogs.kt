package app.focale.ui.backup

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
import app.focale.R
import app.focale.backup.BackupPassword
import app.focale.backup.BackupProgress
import app.focale.backup.PasswordStrength
import app.focale.core.util.Formatters
import app.focale.core.util.UiText

// Dialogues communs aux sauvegardes du coffre-fort et des données : mot de passe d'une nouvelle
// sauvegarde ou d'une sauvegarde à restaurer, avancement, bilan. Chaque sauvegarde fournit ses textes.

/**
 * Mot de passe d'une nouvelle sauvegarde, saisi deux fois, avec les règles et l'indicateur de
 * robustesse de [BackupPassword] ; aussi pour les sauvegardes automatiques, d'où les textes et
 * l'icône en paramètres. Protégé des captures, quelle que soit la fenêtre qui l'ouvre.
 *
 * Les champs sont mémorisés sans sauvegarde d'état (`remember` et non `rememberSaveable`) :
 * le mot de passe ne doit pas finir dans l'état d'instance conservé par le système.
 */
@Composable
fun NewPasswordDialog(
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    @StringRes title: Int,
    @StringRes message: Int,
    @StringRes confirmLabel: Int = R.string.backup_choose_location,
    icon: ImageVector = Icons.Outlined.Backup,
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
                    labelText = stringResource(R.string.backup_password),
                    imeAction = ImeAction.Next,
                    supportingText = { StrengthMeter(strength) },
                )
                PasswordField(
                    state = confirmation,
                    labelText = stringResource(R.string.backup_password_confirm),
                    imeAction = ImeAction.Done,
                    isError = mismatch,
                    onDone = submit,
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.backup_password_mismatch)) }
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
 * Mot de passe de la sauvegarde à restaurer ; reste ouvert, avec un message, s'il est faux.
 * [checking] : vérification en cours, décrite par [checkingText] ; « Annuler » l'abandonne aussi.
 * Protégé des captures, comme la saisie d'un nouveau mot de passe.
 */
@Composable
fun RestorePasswordDialog(
    wrong: Boolean,
    checking: Boolean,
    @StringRes title: Int,
    @StringRes message: Int,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
    @StringRes checkingText: Int = R.string.restore_checking,
) {
    val password = remember { TextFieldState() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val canSubmit = password.text.isNotEmpty() && !checking
    val submit = { if (canSubmit) onConfirm(password.text.toSecret()) }
    AlertDialog(
        // Un appui à côté n'interrompt pas une vérification : seul « Annuler » le fait.
        onDismissRequest = { if (!checking) onDismiss() },
        icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
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
                    labelText = stringResource(R.string.restore_password),
                    imeAction = ImeAction.Done,
                    enabled = !checking,
                    isError = wrong && !checking,
                    onDone = submit,
                    supportingText = when {
                        checking -> {
                            { Text(stringResource(checkingText)) }
                        }
                        wrong -> {
                            { Text(stringResource(R.string.restore_wrong_password)) }
                        }
                        else -> null
                    },
                    modifier = Modifier.focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = canSubmit) {
                if (checking) {
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

/**
 * Avancement, non refermable : seul « Annuler » interrompt l'opération (fichier partiel retiré,
 * ou rien de restauré), sauf [cancellable] faux (étape finale, qui ne s'interrompt pas).
 *
 * @param status ce qui se passe, annoncé par TalkBack à chaque changement.
 */
@Composable
fun BackupProgressDialog(
    icon: ImageVector,
    title: String,
    status: String,
    progress: BackupProgress,
    cancelling: Boolean,
    cancellable: Boolean,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val transferring = progress.stage == BackupProgress.Stage.TRANSFERRING
    BasicAlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp)) {
                BusyEmblem(icon)
                Spacer(Modifier.height(16.dp))
                Text(text = title, style = MaterialTheme.typography.headlineSmallEmphasized)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (cancelling) stringResource(R.string.backup_cancelling) else status,
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
                            R.string.backup_progress_bytes,
                            Formatters.fileSize(context, progress.bytesDone),
                            Formatters.fileSize(context, progress.bytesTotal),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.backup_keep_open),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    TextButton(onClick = onCancel, enabled = !cancelling && cancellable) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}

/** Bilan à confirmer : réussite, issue partielle ou échec, ligne à ligne. */
@Composable
fun BackupReportDialog(success: Boolean, title: UiText, lines: List<UiText>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (success) Icons.Outlined.TaskAlt else Icons.Outlined.ErrorOutline, contentDescription = null) },
        title = { Text(title.asString()) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                lines.forEach { Text(it.asString()) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** Écran maintenu allumé pendant une opération longue : sa mise en veille l'interromprait (coffre verrouillé). */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
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
                    contentDescription = stringResource(if (visible) R.string.backup_hide_password else R.string.backup_show_password),
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
                    R.plurals.backup_password_min,
                    BackupPassword.MIN_LENGTH,
                    BackupPassword.MIN_LENGTH,
                )
                PasswordStrength.WEAK -> stringResource(R.string.backup_strength_weak)
                PasswordStrength.FAIR -> stringResource(R.string.backup_strength_fair)
                PasswordStrength.STRONG -> stringResource(R.string.backup_strength_strong)
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
