package app.focale.ui.viewer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import app.focale.R

/**
 * Retour visuel des sauts d'une vidéo ([SeekFeedback]) : du côté concerné, un demi-ovale
 * translucide accolé au bord de l'écran porte l'icône d'avance ou de retour rapide et le cumul
 * (« +20 s »). Il apparaît et s'efface en fondu, et l'icône bat à chaque saut. À mi-hauteur de
 * la vidéo, il reste dans l'espace libre : hors des barres système et de l'encoche, et, quand
 * elles sont affichées, de la barre supérieure et des commandes du bas. Couleurs Material You
 * de l'appareil, comme les commandes : à placer dans [app.focale.ui.theme.ViewerChromeTheme].
 */
@Composable
internal fun VideoSeekFeedback(feedback: SeekFeedback?, controlsVisible: Boolean, modifier: Modifier = Modifier) {
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    val motion = MaterialTheme.motionScheme
    // Côtés physiques : l'avance reste à droite, même en écriture de droite à gauche.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val top = safe.calculateTopPadding() + if (controlsVisible) ViewerChromeMetrics.TopBarHeight else 0.dp
            val bottom = safe.calculateBottomPadding() + if (controlsVisible) ViewerChromeMetrics.VideoControlsHeight else 0.dp
            val radius = (min(maxWidth, maxHeight) * RADIUS_FRACTION).coerceIn(MinRadius, MaxRadius)
            val height = min(radius * 2, maxHeight - top - bottom - Margin * 2).coerceAtLeast(MinHeight)
            // Demi-disque, aplati en ovale si la hauteur libre manque (paysage, commandes affichées).
            val width = min(radius, height * WIDTH_PER_HEIGHT)
            // Centré comme la vidéo et son bouton de lecture, sans quitter l'espace libre.
            val center = max(top + Margin + height / 2, min(maxHeight / 2, maxHeight - bottom - Margin - height / 2))
            AnimatedContent(
                targetState = feedback,
                // Même côté : le cumul change sur place ; changement de côté ou fin : fondu.
                contentKey = { it?.direction },
                transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.defaultEffectsSpec()) using null },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .offset(y = center - height / 2),
                label = "seek",
            ) { shown ->
                Box(Modifier.fillMaxSize()) {
                    if (shown != null) {
                        val forward = shown.direction == SeekDirection.FORWARD
                        // Le demi-ovale passe sous l'encoche ou la barre de navigation ; son contenu non.
                        val edge = if (forward) safe.calculateRightPadding(LayoutDirection.Ltr) else safe.calculateLeftPadding(LayoutDirection.Ltr)
                        SeekBubble(
                            feedback = shown,
                            edge = edge,
                            modifier = Modifier
                                .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                                .size(width + edge, height),
                        )
                    }
                }
            }
        }
    }
}

/** Demi-ovale accolé au bord : icône et cumul, centrés dans sa part hors de [edge] (encoche, barre de navigation). */
@Composable
private fun SeekBubble(feedback: SeekFeedback, edge: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val forward = feedback.direction == SeekDirection.FORWARD
    val pulse = remember { Animatable(1f) }
    val spring = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    LaunchedEffect(feedback.seconds) {
        pulse.animateTo(PULSE_SCALE, spring)
        pulse.animateTo(1f, spring)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .background(colors.secondaryContainer.copy(alpha = BUBBLE_ALPHA), EdgeOvalShape(flatOnRight = forward))
            .padding(start = if (forward) 0.dp else edge, end = if (forward) edge else 0.dp),
    ) {
        Icon(
            imageVector = if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
            contentDescription = null,
            tint = colors.onSecondaryContainer,
            modifier = Modifier
                .size(IconSize)
                .graphicsLayer {
                    scaleX = pulse.value
                    scaleY = pulse.value
                },
        )
        Text(
            text = stringResource(if (forward) R.string.video_seek_forward_amount else R.string.video_seek_back_amount, feedback.seconds),
            // Chiffres à chasse fixe, comme les temps de la progression.
            style = MaterialTheme.typography.titleMediumEmphasized.copy(fontFeatureSettings = "tnum"),
            color = colors.onSecondaryContainer,
            // Lu par TalkBack à chaque changement : confirme les sauts de ses actions.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Demi-ellipse plate du côté du bord de l'écran ([flatOnRight] : à droite), bombée vers le centre. */
private class EdgeOvalShape(private val flatOnRight: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val round = CornerRadius(size.width, size.height / 2f)
        val flat = CornerRadius.Zero
        return Outline.Rounded(
            if (flatOnRight) {
                RoundRect(size.toRect(), topLeft = round, topRight = flat, bottomRight = flat, bottomLeft = round)
            } else {
                RoundRect(size.toRect(), topLeft = flat, topRight = round, bottomRight = round, bottomLeft = flat)
            },
        )
    }
}

/** Rayon du demi-disque : part du petit côté de l'écran, dans des limites lisibles. */
private const val RADIUS_FRACTION = 0.3f
private val MinRadius = 96.dp
private val MaxRadius = 144.dp

/** Largeur maximale de l'ovale aplati, par rapport à sa hauteur (demi-disque : 0,5). */
private const val WIDTH_PER_HEIGHT = 0.6f

/** Hauteur minimale : icône et cumul. */
private val MinHeight = 72.dp
private val Margin = 8.dp
private val IconSize = 32.dp
private const val BUBBLE_ALPHA = 0.6f
private const val PULSE_SCALE = 1.2f
