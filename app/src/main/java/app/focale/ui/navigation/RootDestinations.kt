package app.focale.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.focale.ui.components.LocalNavAnimatedVisibilityScope
import app.focale.ui.editor.EditorScreen
import app.focale.ui.slideshow.SlideshowScreen
import app.focale.ui.vault.VaultPickerScreen
import app.focale.ui.vault.VaultScreen
import app.focale.ui.vault.VaultViewerScreen

/**
 * Destinations racine plein écran (éditeur, coffre-fort, diaporama…), au-dessus des
 * onglets comme la visionneuse.
 */
fun NavGraphBuilder.additionalRootDestinations(rootNav: NavHostController) {
    fullScreen<EditorRoute> {
        EditorScreen(onClose = { rootNav.popBackStack() })
    }
    fullScreen<SlideshowRoute> {
        SlideshowScreen(onClose = { rootNav.popBackStack() })
    }
    fullScreen<VaultRoute> {
        VaultScreen(
            onBack = { rootNav.popBackStack() },
            onOpen = { rootNav.navigate(VaultViewerRoute(it.id)) { launchSingleTop = true } },
            onAdd = { rootNav.navigate(VaultPickerRoute) { launchSingleTop = true } },
        )
    }
    fullScreen<VaultViewerRoute> {
        VaultViewerScreen(onBack = { rootNav.popBackStack() })
    }
    fullScreen<VaultPickerRoute> {
        VaultPickerScreen(onBack = { rootNav.popBackStack() })
    }
}

/** Destination en fondu, qui expose sa portée d'animation aux éléments partagés. */
private inline fun <reified T : Any> NavGraphBuilder.fullScreen(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T>(
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) { entry ->
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            content(entry)
        }
    }
}
