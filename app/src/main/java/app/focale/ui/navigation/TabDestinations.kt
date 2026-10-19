package app.focale.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.focale.ui.albums.AlbumsScreen
import app.focale.ui.cleanup.CleanupScreen
import app.focale.ui.collection.CollectionScreen
import app.focale.ui.map.MapScreen
import app.focale.ui.people.PeopleScreen
import app.focale.ui.people.PersonScreen
import app.focale.ui.search.SearchScreen
import app.focale.ui.trash.TrashScreen

/** Destinations des onglets (hors Photos et Plus, déclarés dans [MainScaffold]). */
fun NavGraphBuilder.tabDestinations(tabsNav: NavHostController) {
    composable<AlbumsRoute> { AlbumsScreen() }
    composable<CollectionRoute> { CollectionScreen(onBack = { tabsNav.popBackStack() }) }
    composable<TrashRoute> { TrashScreen(onBack = { tabsNav.popBackStack() }) }
    composable<SearchRoute> { SearchScreen() }
    composable<CleanupRoute> { CleanupScreen(onBack = { tabsNav.popBackStack() }) }
    composable<MapRoute> { MapScreen(onBack = { tabsNav.popBackStack() }) }
    composable<PeopleRoute> { PeopleScreen(onBack = { tabsNav.popBackStack() }) }
    composable<PersonRoute> { PersonScreen(onBack = { tabsNav.popBackStack() }) }
}
