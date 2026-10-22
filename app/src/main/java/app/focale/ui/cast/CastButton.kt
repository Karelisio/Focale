package app.focale.ui.cast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.focale.R
import app.focale.cast.CastState
import app.focale.cast.CastTarget

/**
 * Bouton « Diffuser » des barres de la visionneuse et du diaporama : visible seulement quand un
 * écran externe ou une TV est là, qu'une recherche est en cours ou qu'une diffusion a lieu
 * ([CastState.available]) ; icône pleine, teintée, pendant la diffusion.
 */
@Composable
fun CastButton(state: CastState, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    AnimatedVisibility(
        visible = state.available,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = modifier,
    ) {
        val target = state.target
        IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
            Icon(
                imageVector = if (target != null) Icons.Outlined.CastConnected else Icons.Outlined.Cast,
                contentDescription = if (target != null) {
                    stringResource(R.string.cast_action_active, castTargetName(target))
                } else {
                    stringResource(R.string.cast_action)
                },
                tint = when {
                    target != null -> MaterialTheme.colorScheme.primary
                    tint != Color.Unspecified -> tint
                    else -> LocalContentColor.current
                },
            )
        }
    }
}

/** Nom affiché de l'appareil qui reçoit la diffusion. */
@Composable
fun castTargetName(target: CastTarget): String = when (target) {
    is CastTarget.Screen -> target.display.name.ifBlank { stringResource(R.string.cast_screen_default_name) }
    is CastTarget.Tv -> target.renderer.name
}
