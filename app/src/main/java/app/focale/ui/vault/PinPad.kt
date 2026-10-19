package app.focale.ui.vault

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.vault.PinHasher

/**
 * Saisie d'un code à 4–12 chiffres sur un pavé numérique expressif : touches rondes
 * qui se déforment à l'appui, points animés, secousse et vibration en cas d'erreur.
 *
 * En paysage sur un écran bas, l'en-tête et le pavé sont placés côte à côte.
 *
 * @param errorTick incrémenté à chaque erreur pour rejouer l'animation de secousse.
 */
@Composable
fun PinEntry(
    title: String,
    message: String,
    length: Int,
    error: String?,
    errorTick: Int,
    confirmLabel: String,
    enabled: Boolean,
    checking: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    onBiometric: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val shake = remember { Animatable(0f) }
    // Dernière erreur déjà signalée : une rotation ne rejoue ni secousse ni vibration.
    var handledTick by rememberSaveable { mutableIntStateOf(errorTick) }
    LaunchedEffect(errorTick) {
        if (errorTick == handledTick) return@LaunchedEffect
        handledTick = errorTick
        haptics.performHapticFeedback(HapticFeedbackType.Reject)
        shake.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = 420
                -28f at 60
                24f at 120
                -16f at 190
                12f at 260
                -6f at 330
            },
        )
    }

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val sideBySide = maxWidth > maxHeight && maxHeight < 560.dp
        val header: @Composable () -> Unit = {
            PinHeader(
                title = title,
                message = message,
                length = length,
                error = error,
                checking = checking,
                shakeOffset = shake.value,
                onBiometric = onBiometric,
            )
        }
        val pad: @Composable () -> Unit = {
            Keypad(
                enabled = enabled && !checking,
                canConfirm = enabled && !checking && length >= PinHasher.MIN_LENGTH,
                confirmLabel = confirmLabel,
                onDigit = { digit ->
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onDigit(digit)
                },
                onBackspace = {
                    haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
                    onBackspace()
                },
                onConfirm = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onConfirm()
                },
            )
        }
        if (sideBySide) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(24.dp),
            ) {
                Box(Modifier.widthIn(max = 320.dp)) { header() }
                pad()
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                header()
                Spacer(Modifier.height(24.dp))
                pad()
            }
        }
    }
}

@Composable
private fun PinHeader(
    title: String,
    message: String,
    length: Int,
    error: String?,
    checking: Boolean,
    shakeOffset: Float,
    onBiometric: (() -> Unit)?,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            AnimatedContent(
                targetState = checking,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() },
                label = "pinEmblem",
            ) { busy ->
                if (busy) {
                    LoadingIndicator(color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PinDots(length = length, modifier = Modifier.offset { IntOffset(shakeOffset.dp.roundToPx(), 0) })
        Box(Modifier.heightIn(min = 48.dp).padding(top = 12.dp), contentAlignment = Alignment.Center) {
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else if (onBiometric != null) {
                TextButton(onClick = onBiometric, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = null)
                    Text(stringResource(R.string.vault_use_biometric), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Points de saisie : au moins quatre emplacements, remplis au fil de la frappe. */
@Composable
private fun PinDots(length: Int, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.vault_pin_entered, length, length)
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(24.dp)
            .semantics { contentDescription = description },
    ) {
        repeat(maxOf(PinHasher.MIN_LENGTH, length)) { index ->
            if (index < length) FilledDot() else EmptyDot()
        }
    }
}

@Composable
private fun FilledDot() {
    val scale = remember { Animatable(0.2f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    Box(
        Modifier
            .size(16.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(MaterialShapes.Cookie4Sided.toShape())
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun EmptyDot() {
    Box(
        Modifier
            .size(12.dp)
            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
private fun Keypad(
    enabled: Boolean,
    canConfirm: Boolean,
    confirmLabel: String,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { digit -> DigitKey(digit, enabled) { onDigit(digit) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(KeySize), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = onBackspace,
                    enabled = enabled,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(KeySize),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = stringResource(R.string.vault_backspace))
                }
            }
            DigitKey('0', enabled) { onDigit('0') }
            FilledIconButton(
                onClick = onConfirm,
                enabled = canConfirm,
                shapes = IconButtonDefaults.shapes(
                    shape = RoundedCornerShape(28.dp),
                    pressedShape = RoundedCornerShape(16.dp),
                ),
                modifier = Modifier.size(KeySize),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = confirmLabel, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shapes = ButtonDefaults.shapes(shape = CircleShape, pressedShape = RoundedCornerShape(20.dp)),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.size(KeySize),
    ) {
        Text(digit.toString(), style = MaterialTheme.typography.headlineMedium)
    }
}

private val KeySize = 76.dp
