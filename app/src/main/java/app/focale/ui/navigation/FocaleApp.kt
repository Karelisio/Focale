package app.focale.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.focale.core.model.MediaQuery
import app.focale.ui.components.LocalNavAnimatedVisibilityScope
import app.focale.ui.components.LocalSharedTransitionScope
import app.focale.ui.viewer.ViewerScreen
import app.focale.ui.whatsnew.WhatsNewHost
import kotlinx.coroutines.flow.StateFlow

/**
 * Racine de l'interface : un NavHost plein écran (onglets, visionneuse…) enveloppé
 * dans un [SharedTransitionLayout] pour les transitions grille → visionneuse.
 */
@Composable
fun FocaleApp(openMediaRequests: StateFlow<Long?>, onOpenMediaHandled: () -> Unit) {
    val rootNav = rememberNavController()
    val chrome = remember { MainChromeState() }
    val motion = rememberNavMotion()
    val openMedia by openMediaRequests.collectAsStateWithLifecycle()

    // Appui sur le widget : ouvre la photo dans la visionneuse, au sein de la timeline.
    LaunchedEffect(openMedia) {
        val mediaId = openMedia ?: return@LaunchedEffect
        rootNav.navigate(ViewerRoute(RouteCodec.encode(MediaQuery.Timeline), mediaId)) { launchSingleTop = true }
        onOpenMediaHandled()
    }

    SharedTransitionLayout {
        CompositionLocalProvider(
            LocalSharedTransitionScope provides this,
            LocalRootNavController provides rootNav,
            LocalMainChrome provides chrome,
        ) {
            // Visionneuse, éditeur, coffre : fondu au-dessus de l'écran d'origine, laissé en
            // place ; au retour (geste prédictif compris), le fond s'estompe pendant que la
            // photo partagée rejoint sa miniature, sans réduction de l'écran.
            NavHost(
                navController = rootNav,
                startDestination = MainRoute,
                enterTransition = { motion.fadeIn },
                exitTransition = { ExitTransition.KeepUntilTransitionsFinished },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { motion.fadeOut },
                predictivePopEnterTransition = { _ -> EnterTransition.None },
                predictivePopExitTransition = { _ -> motion.fadeOut },
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
            // Premier lancement après une mise à jour : nouveautés de la version.
            WhatsNewHost()
        }
    }
}
