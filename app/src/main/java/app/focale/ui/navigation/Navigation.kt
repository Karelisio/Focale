package app.focale.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.ui.viewer.ViewerHandoff

/** Contrôleur du NavHost racine (visionneuse, éditeur, diaporama…). */
val LocalRootNavController = staticCompositionLocalOf<NavHostController> {
    error("LocalRootNavController non fourni")
}

/** Contrôleur du NavHost des onglets. */
val LocalTabsNavController = staticCompositionLocalOf<NavHostController> {
    error("LocalTabsNavController non fourni")
}

/** Ouvre la visionneuse sur [item], en parcourant les médias de [query]. */
fun NavHostController.openViewer(query: MediaQuery, item: MediaItem) {
    ViewerHandoff.put(item)
    navigate(ViewerRoute(RouteCodec.encode(query), item.id)) { launchSingleTop = true }
}

/**
 * Permet à un écran de masquer la barre de navigation (mode sélection) et à la
 * visionneuse d'indiquer le dernier média consulté (pour y ramener la grille).
 */
@Stable
class MainChromeState {
    /** Demandes de masquage de la barre de navigation, par écran. */
    var navigationHidden by mutableStateOf(false)

    /** Dernier média affiché par la visionneuse, consommé par la grille au retour. */
    var lastViewedMediaId by mutableStateOf<Long?>(null)
}

val LocalMainChrome = staticCompositionLocalOf { MainChromeState() }
