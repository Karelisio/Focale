package app.focale.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.focale.R
import app.focale.ui.components.EmptyState

/** Destinations des onglets Albums et Recherche (complétées aux étapes suivantes). */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.tabDestinations(tabsNav: NavHostController) {
    composable<AlbumsRoute> {
        EmptyState(icon = Icons.Rounded.PhotoLibrary, title = stringResource(R.string.tab_albums))
    }
    composable<SearchRoute> {
        EmptyState(icon = Icons.Rounded.Search, title = stringResource(R.string.tab_search))
    }
}
