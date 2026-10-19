package app.focale.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.focale.ui.components.LocalNavAnimatedVisibilityScope
import app.focale.ui.components.LocalSharedTransitionScope
import app.focale.ui.viewer.ViewerScreen

/**
 * Racine de l'interface : un NavHost plein écran (onglets, visionneuse…) enveloppé
 * dans un [SharedTransitionLayout] pour les transitions grille → visionneuse.
 */
@Composable
fun FocaleApp() {
    val rootNav = rememberNavController()
    val chrome = remember { MainChromeState() }

    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this,
            LocalRootNavController provides rootNav,
            LocalMainChrome provides chrome,
        ) {
            NavHost(
                navController = rootNav,
                startDestination = MainRoute,
                enterTransition = { fadeIn(tween(220)) },
                exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { fadeOut(tween(220)) },
            ) {
                composable<MainRoute> {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        MainScaffold()
                    }
                }
                composable<ViewerRoute> {
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        ViewerScreen(onBack = { rootNav.popBackStack() })
                    }
                }
                additionalRootDestinations(rootNav)
            }
        }
    }
}
