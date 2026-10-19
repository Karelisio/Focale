package app.focale.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.focale.R
import app.focale.ui.albums.AlbumsScreen
import app.focale.ui.collection.CollectionScreen
import app.focale.ui.components.EmptyState
import app.focale.ui.trash.TrashScreen

/** Destinations des onglets (hors Photos et Plus, déclarés dans [MainScaffold]). */
fun NavGraphBuilder.tabDestinations(tabsNav: NavHostController) {
    composable<AlbumsRoute> { AlbumsScreen() }
    composable<CollectionRoute> { CollectionScreen(onBack = { tabsNav.popBackStack() }) }
    composable<TrashRoute> { TrashScreen(onBack = { tabsNav.popBackStack() }) }
    composable<SearchRoute> {
        EmptyState(icon = Icons.Rounded.Search, title = stringResource(R.string.tab_search))
    }
}
