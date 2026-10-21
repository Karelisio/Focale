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
import androidx.navigation.toRoute
import app.focale.core.model.MediaQuery
import app.focale.ui.components.LocalNavAnimatedVisibilityScope
import app.focale.ui.components.LocalSharedTransitionScope
import app.focale.ui.viewer.ViewerScreen
import app.focale.ui.whatsnew.WhatsNewHost
import kotlinx.coroutines.flow.StateFlow

/**
 * Racine de l'interface : un NavHost plein écran (onglets, visionneuse…) enveloppé
 * dans un [SharedTransitionLayout] pour les transitions grille → visionneuse.
 *
 * @param startRoute [MainRoute], ou [ExternalMediaRoute] quand l'activité est lancée pour ouvrir
 *   le média d'une autre application : la timeline n'est alors pas construite dessous.
 * @param openRequests ouvertures reçues par l'activité déjà lancée, consommées par [onOpenHandled].
 */
@Composable
fun FocaleApp(startRoute: Any, openRequests: StateFlow<OpenRequest?>, onOpenHandled: () -> Unit) {
    val rootNav = rememberNavController()
    val chrome = remember { MainChromeState() }
    val motion = rememberNavMotion()
    val openRequest by openRequests.collectAsStateWithLifecycle()

    LaunchedEffect(openRequest) {
        val request = openRequest ?: return@LaunchedEffect
        // Icône ou widget de Focale alors que l'activité ne montrait que le média d'une autre
        // application : la galerie est rétablie (absente de la pile, getBackStackEntry lève une exception).
        if (request !is OpenRequest.External && runCatching { rootNav.getBackStackEntry<MainRoute>() }.isFailure) {
            rootNav.navigate(MainRoute) { popUpTo(rootNav.graph.id) { inclusive = true } }
        }
        when (request) {
            OpenRequest.Home -> Unit
            // Appui sur le widget : ouvre la photo dans la visionneuse, au sein de la timeline.
            is OpenRequest.Media ->
                rootNav.navigate(ViewerRoute(RouteCodec.encode(MediaQuery.Timeline), request.mediaId)) { launchSingleTop = true }
            OpenRequest.Vault -> rootNav.navigate(VaultRoute) { launchSingleTop = true }
            // Autre application : par-dessus l'écran courant, que le retour retrouve.
            is OpenRequest.External -> rootNav.navigate(request.route) { launchSingleTop = true }
        }
        onOpenHandled()
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
                startDestination = startRoute,
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
                composable<ViewerRoute> { entry ->
                    val onBack = rememberRootBack(closeOnBack = remember(entry) { entry.toRoute<ViewerRoute>().closeOnBack })
                    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
                        ViewerScreen(onBack = onBack)
                    }
                }
                additionalRootDestinations(rootNav)
            }
            // Premier lancement après une mise à jour : nouveautés de la version, pas par-dessus la
            // photo d'une autre application.
            if (startRoute == MainRoute) WhatsNewHost()
        }
    }
}
