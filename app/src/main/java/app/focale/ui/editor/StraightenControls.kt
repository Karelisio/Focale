package app.focale.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.edit.StraightenGesture
import app.focale.edit.StraightenMath
import app.focale.edit.StraightenTick
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Écart entre deux graduations d'un degré : toute la plage (±45°) tient en 810 dp de glissé. */
private val DEGREE_SPACING = 9.dp
private val RULER_HEIGHT = 40.dp

/**
 * Angle affiché, puis bouton de retour à 0° dès qu'il n'est plus nul : pastille tonale,
 * icône et valeur (un chiffre après la virgule au plus, à la manière de la langue).
 */
@Composable
internal fun StraightenValue(angle: Float, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val straightened = angle != 0f
    val content = if (straightened) colors.onSecondaryContainer else colors.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(CircleShape)
            .background(if (straightened) colors.secondaryContainer else Color.Transparent)
            .clickable(enabled = straightened, onClickLabel = stringResource(R.string.editor_straighten_reset), role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onReset()
            }
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (straightened) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(angleLabel(angle), style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/**
 * Règle graduée du redressement, façon Material 3 Expressive : un trait arrondi par degré,
 * plus long tous les 5°, qui défile sous un repère fixe à la couleur d'accent ; un point
 * marque 0°, et les graduations s'estompent vers les bords. Glisser vers la gauche amène les
 * angles positifs (sens horaire) sous le repère. Retour haptique à chaque degré entier, plus
 * marqué à 0° ; un double appui revient à 0°.
 */
@Composable
internal fun StraightenRuler(
    angle: Float,
    onStart: () -> Unit,
    onAngle: (Float) -> Unit,
    onEnd: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val spacing = with(LocalDensity.current) { DEGREE_SPACING.toPx() }
    val currentAngle by rememberUpdatedState(angle)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnAngle by rememberUpdatedState(onAngle)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOnReset by rememberUpdatedState(onReset)
    val title = stringResource(R.string.editor_straighten)
    val label = angleLabel(angle)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
            .pointerInput(spacing) {
                var value = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        value = currentAngle
                        currentOnStart()
                    },
                    onDragEnd = { currentOnEnd() },
                    onDragCancel = { currentOnEnd() },
                ) { change, amount ->
                    change.consume()
                    val next = (value - amount / spacing).coerceIn(-StraightenMath.MAX_ANGLE, StraightenMath.MAX_ANGLE)
                    haptics.straightenTick(value, next)
                    value = next
                    currentOnAngle(next)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (currentAngle != 0f) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            currentOnReset()
                        }
                    },
                )
            }
            .semantics {
                contentDescription = title
                stateDescription = label
                // Un degré par geste d'accessibilité.
                progressBarRangeInfo = ProgressBarRangeInfo(angle, -StraightenMath.MAX_ANGLE..StraightenMath.MAX_ANGLE, steps = STEPS)
                setProgress { target ->
                    currentOnStart()
                    currentOnAngle(target)
                    currentOnEnd()
                    true
                }
            },
    ) {
        val middle = size.width / 2f
        val centerY = size.height / 2f
        val first = ceil(angle - middle / spacing).toInt().coerceAtLeast(-StraightenMath.MAX_ANGLE.toInt())
        val last = floor(angle + middle / spacing).toInt().coerceAtMost(StraightenMath.MAX_ANGLE.toInt())
        for (degree in first..last) {
            val x = middle + (degree - angle) * spacing
            val major = degree % 5 == 0
            val half = size.height * if (major) 0.24f else 0.13f
            val fade = 1f - (abs(x - middle) / middle).coerceIn(0f, 1f)
            drawLine(
                color = colors.onSurfaceVariant.copy(alpha = fade * if (major) 0.9f else 0.5f),
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = (if (major) 2.dp else 1.5.dp).toPx(),
                cap = StrokeCap.Round,
            )
            if (degree == 0) {
                drawCircle(colors.primary.copy(alpha = fade), radius = 2.5.dp.toPx(), center = Offset(x, centerY + half + 6.dp.toPx()))
            }
        }
        // Repère fixe : la valeur courante.
        drawLine(
            color = colors.primary,
            start = Offset(middle, size.height * 0.12f),
            end = Offset(middle, size.height * 0.88f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}

/** Pas d'accessibilité : un degré (90 intervalles entre -45° et 45°). */
private const val STEPS = 89

/** Angle à afficher, au dixième de degré près, signe moins typographique. */
@Composable
private fun angleLabel(angle: Float): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 } }
    return format.format(angle.toDouble()).replace('-', '−') + "°"
}

/** Vibre au passage de 0° ou d'un degré entier, entre [previous] et [current]. */
internal fun HapticFeedback.straightenTick(previous: Float, current: Float) {
    when (StraightenMath.tick(previous, current)) {
        StraightenTick.ZERO -> performHapticFeedback(HapticFeedbackType.SegmentTick)
        StraightenTick.DEGREE -> performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        StraightenTick.NONE -> Unit
    }
}

/**
 * Redressement à deux doigts sur l'aperçu, en mode recadrage. Les évènements sont lus à la
 * passe initiale : dès qu'un second doigt se pose, le geste est réservé au redressement et
 * consommé (le cadre de recadrage ne suit plus le premier doigt) jusqu'au dernier doigt levé.
 * La rotation ne compte qu'au-delà du seuil, et jamais pour un pincement ([StraightenGesture]) ;
 * elle s'ajoute ensuite à l'angle du début, sans à-coup.
 */
internal fun Modifier.straightenGesture(
    angle: () -> Float,
    haptics: HapticFeedback,
    onStart: () -> Unit,
    onAngle: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val gesture = StraightenGesture()
        var start = 0f
        var shown = 0f
        var reserved = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                reserved = true
                val engaged = gesture.engaged
                val added = gesture.update(event.calculateRotation(), event.calculateZoom())
                if (added != null) {
                    if (!engaged) {
                        start = angle()
                        shown = start
                        onStart()
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    }
                    val next = (start + added).coerceIn(-StraightenMath.MAX_ANGLE, StraightenMath.MAX_ANGLE)
                    haptics.straightenTick(shown, next)
                    shown = next
                    onAngle(next)
                }
            }
            if (reserved) event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
        if (gesture.engaged) onEnd()
    }
}
