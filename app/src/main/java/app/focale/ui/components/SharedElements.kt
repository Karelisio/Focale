package app.focale.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/** Portée de la transition partagée qui englobe le NavHost racine. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** Portée d'animation de la destination racine courante (onglets ou visionneuse). */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Clé partagée entre une miniature de grille et l'image de la visionneuse. */
fun mediaSharedKey(mediaId: Long): String = "media-$mediaId"

/** Ressort expressif pour l'agrandissement grille → visionneuse. */
private val MediaBoundsTransform = BoundsTransform { _: Rect, _: Rect ->
    spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = Rect.VisibilityThreshold,
    )
}

/**
 * Associe l'élément à la transition partagée du média [mediaId].
 * Sans portée disponible (aperçu, test), le modificateur est neutre.
 */
@Composable
fun Modifier.sharedMedia(mediaId: Long, enabled: Boolean = true): Modifier {
    val sharedScope = LocalSharedTransitionScope.current
    val visibilityScope = LocalNavAnimatedVisibilityScope.current
    if (!enabled || sharedScope == null || visibilityScope == null) return this
    return with(sharedScope) {
        this@sharedMedia.sharedElement(
            sharedContentState = rememberSharedContentState(mediaSharedKey(mediaId)),
            animatedVisibilityScope = visibilityScope,
            boundsTransform = MediaBoundsTransform,
        )
    }
}

/** Vrai pendant une transition de navigation de la destination courante. */
@Composable
fun isNavTransitionRunning(): Boolean =
    LocalNavAnimatedVisibilityScope.current?.transition?.isRunning == true
