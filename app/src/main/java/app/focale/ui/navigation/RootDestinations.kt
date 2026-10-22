package app.focale.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.focale.ui.collage.CollageScreen
import app.focale.ui.components.LocalNavAnimatedVisibilityScope
import app.focale.ui.editor.EditorScreen
import app.focale.ui.slideshow.SlideshowScreen
import app.focale.ui.vault.VaultAlbumScreen
import app.focale.ui.vault.VaultPickerScreen
import app.focale.ui.vault.VaultScreen
import app.focale.ui.vault.VaultTrashScreen
import app.focale.ui.vault.VaultViewerScreen
import app.focale.ui.videoeditor.VideoEditorScreen
import app.focale.ui.viewer.ExternalMediaScreen

/**
 * Destinations racine plein écran (éditeur, coffre-fort, diaporama, média d'une autre
 * application…), au-dessus des onglets comme la visionneuse.
 */
fun NavGraphBuilder.additionalRootDestinations(rootNav: NavHostController) {
    fullScreen<ExternalMediaRoute> { entry ->
        ExternalMediaScreen(onBack = rememberRootBack(closeOnBack = remember(entry) { entry.toRoute<ExternalMediaRoute>().closeOnBack }))
    }
    fullScreen<EditorRoute> {
        EditorScreen(onClose = { rootNav.popBackStack() })
    }
    fullScreen<VideoEditorRoute> {
        VideoEditorScreen(onClose = { rootNav.popBackStack() })
    }
    fullScreen<CollageRoute> {
        CollageScreen(onClose = { rootNav.popBackStack() }, onSaved = rootNav::openSavedCollage)
    }
    fullScreen<SlideshowRoute> {
        SlideshowScreen(onClose = { rootNav.popBackStack() })
    }
    fullScreen<VaultRoute> {
        VaultScreen(
            onBack = { rootNav.popBackStack() },
            onOpen = { rootNav.navigate(VaultViewerRoute(it.id)) { launchSingleTop = true } },
            onAdd = { rootNav.navigate(VaultPickerRoute()) { launchSingleTop = true } },
            onOpenAlbum = { rootNav.navigate(VaultAlbumRoute(it.id)) { launchSingleTop = true } },
            onOpenTrash = { rootNav.navigate(VaultTrashRoute) { launchSingleTop = true } },
        )
    }
    fullScreen<VaultTrashRoute> {
        VaultTrashScreen(onBack = { rootNav.popBackStack() })
    }
    fullScreen<VaultUnlockRoute> {
        VaultScreen(
            onBack = { rootNav.popBackStack() },
            onOpen = {},
            onAdd = {},
            onOpenAlbum = {},
            onUnlocked = { rootNav.popBackStack() },
        )
    }
    fullScreen<VaultAlbumRoute> { entry ->
        val albumId = entry.toRoute<VaultAlbumRoute>().albumId
        VaultAlbumScreen(
            onBack = { rootNav.popBackStack() },
            onOpen = { rootNav.navigate(VaultViewerRoute(it.id, albumId)) { launchSingleTop = true } },
            onAdd = { rootNav.navigate(VaultPickerRoute(albumId)) { launchSingleTop = true } },
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
