package app.focale.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute

/**
 * Transitions de navigation Material, rythmées par le schéma de mouvement du thème :
 * - entre onglets : fondu enchaîné (« fade through ») ;
 * - vers un écran de détail et retour : axe partagé horizontal, léger glissement et
 *   fondu, suivi aussi par le geste de retour prédictif (au lieu de la réduction de
 *   l'écran à 70 % puis de sa disparition, comportement par défaut de Navigation).
 */
@Immutable
class NavMotion(
    private val spatial: FiniteAnimationSpec<IntOffset>,
    private val effects: FiniteAnimationSpec<Float>,
) {
    /** Écran qui arrive ; [forward] : on avance dans la hiérarchie (sinon retour). */
    fun enter(forward: Boolean): EnterTransition =
        slideInHorizontally(spatial) { width -> (if (forward) width else -width) / SHIFT_DIVISOR } + fadeIn(effects)

    /** Écran qui part. */
    fun exit(forward: Boolean): ExitTransition =
        slideOutHorizontally(spatial) { width -> (if (forward) -width else width) / SHIFT_DIVISOR } + fadeOut(effects)

    val fadeThroughIn: EnterTransition get() = fadeIn(effects) + scaleIn(effects, initialScale = FADE_THROUGH_SCALE)
    val fadeThroughOut: ExitTransition get() = fadeOut(effects)

    /** Fondu seul (visionneuse, éditeur…) : la photo partagée porte le mouvement. */
    val fadeIn: EnterTransition get() = fadeIn(effects)
    val fadeOut: ExitTransition get() = fadeOut(effects)

    private companion object {
        /** Décalage du glissement : un dixième de la largeur, comme l'axe partagé Material. */
        const val SHIFT_DIVISOR = 10
        const val FADE_THROUGH_SCALE = 0.96f
    }
}

@Composable
fun rememberNavMotion(): NavMotion {
    val scheme = MaterialTheme.motionScheme
    return remember(scheme) { NavMotion(scheme.defaultSpatialSpec(), scheme.defaultEffectsSpec()) }
}

/** Destination d'onglet (Photos, Albums, Recherche, Plus) plutôt qu'écran de détail. */
fun NavDestination.isTopLevel(): Boolean = TopLevelDestination.entries.any { hasRoute(it.routeClass) }

/** Arrivée sur un onglet (barre de navigation) : fondu enchaîné plutôt que glissement. */
val AnimatedContentTransitionScope<NavBackStackEntry>.toTab: Boolean
    get() = targetState.destination.isTopLevel()
