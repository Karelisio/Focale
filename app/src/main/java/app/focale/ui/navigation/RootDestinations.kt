package app.focale.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.focale.ui.components.LocalNavAnimatedVisibilityScope
import app.focale.ui.editor.EditorScreen

/**
 * Destinations racine plein écran (éditeur, diaporama, coffre-fort…), au-dessus des
 * onglets comme la visionneuse.
 */
fun NavGraphBuilder.additionalRootDestinations(rootNav: NavHostController) {
    composable<EditorRoute>(
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
    ) {
        CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this) {
            EditorScreen(onClose = { rootNav.popBackStack() })
        }
    }
}
