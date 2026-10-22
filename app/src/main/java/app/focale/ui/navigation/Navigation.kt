package app.focale.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavDestination.Companion.hasRoute
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

/**
 * Ouvre [route], demandée à l'activité déjà lancée (widget, autre application), par-dessus l'écran
 * courant. Un écran du même type au sommet (la visionneuse sur une autre photo) est remplacé :
 * `launchSingleTop` le réutiliserait avec son ViewModel, qui garderait sa photo au lieu de celle
 * demandée. Sauf s'il est la racine (activité lancée pour lui) : son retour referme l'activité.
 */
inline fun <reified T : Any> NavHostController.openOnTop(route: T) {
    val top = currentBackStackEntry?.destination
    val replace = top != null && top.hasRoute<T>() && previousBackStackEntry != null
    navigate(route) {
        if (replace) popUpTo(top.id) { inclusive = true }
    }
}

/** Ouvre la visionneuse sur [item], en parcourant les médias de [query]. */
fun NavHostController.openViewer(query: MediaQuery, item: MediaItem) {
    ViewerHandoff.put(item)
    navigate(ViewerRoute(RouteCodec.encode(query), item.id)) { launchSingleTop = true }
}

/**
 * Média de l'index reçu d'une autre application : visionneuse parmi les médias de son dossier
 * (masqué de la timeline ou non), à la place de l'écran d'ouverture.
 */
fun NavHostController.openFromOtherApp(item: MediaItem, closeOnBack: Boolean) {
    ViewerHandoff.put(item)
    navigate(ViewerRoute(RouteCodec.encode(MediaQuery(bucketId = item.bucketId)), item.id, closeOnBack)) {
        popUpTo<ExternalMediaRoute> { inclusive = true }
    }
}

/**
 * Collage tout juste enregistré : visionneuse sur lui, parmi les médias de son dossier, à la place
 * de l'écran de collage (le retour ramène à la grille d'où il a été créé).
 */
fun NavHostController.openSavedCollage(item: MediaItem) {
    ViewerHandoff.put(item)
    navigate(ViewerRoute(RouteCodec.encode(MediaQuery(bucketId = item.bucketId)), item.id)) {
        popUpTo<CollageRoute> { inclusive = true }
    }
}

/** Ouvre l'écran de collage des photos [ids]. */
fun NavHostController.openCollage(ids: List<Long>, truncated: Boolean) {
    navigate(CollageRoute(ids, truncated)) { launchSingleTop = true }
}

/**
 * Retour d'un écran racine : dépile ; ou, l'activité ayant été lancée pour ouvrir le média d'une
 * autre application ([closeOnBack]), la referme pour revenir à celle-ci (retour système compris).
 */
@Composable
fun rememberRootBack(closeOnBack: Boolean): () -> Unit {
    val rootNav = LocalRootNavController.current
    // Activité à refermer, ou null pour dépiler.
    val activity = LocalActivity.current?.takeIf { closeOnBack }
    // Dans la tâche de l'appelant, le retour système referme déjà l'activité (avec l'animation
    // prédictive) ; à la racine de sa tâche, il ne ferait que la passer en arrière-plan.
    BackHandler(enabled = activity?.isTaskRoot == true) { activity?.finish() }
    return remember(rootNav, activity) {
        { if (activity != null) activity.finish() else rootNav.popBackStack() }
    }
}

/** Lance le diaporama des médias de [query], à partir de [startId] si fourni. */
fun NavHostController.openSlideshow(query: MediaQuery, startId: Long? = null) {
    navigate(SlideshowRoute(RouteCodec.encode(query), startId)) { launchSingleTop = true }
}

/** Ouvre une grille générique (album, favoris, catégorie…). */
fun NavHostController.openCollection(
    title: String,
    query: MediaQuery,
    kind: CollectionKind = CollectionKind.QUERY,
    albumId: Long? = null,
) {
    navigate(CollectionRoute(title, RouteCodec.encode(query), kind, albumId)) { launchSingleTop = true }
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
