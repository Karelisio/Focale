package app.focale.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.focale.R
import app.focale.ui.more.AboutScreen
import app.focale.ui.more.MoreScreen
import app.focale.ui.settings.SettingsScreen
import app.focale.ui.timeline.TimelineScreen
import kotlin.reflect.KClass

/** Onglets principaux. */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val label: Int,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    PHOTOS(PhotosRoute, PhotosRoute::class, R.string.tab_photos, Icons.Outlined.Photo, Icons.Rounded.Photo),
    ALBUMS(AlbumsRoute, AlbumsRoute::class, R.string.tab_albums, Icons.Outlined.PhotoLibrary, Icons.Rounded.PhotoLibrary),
    SEARCH(SearchRoute, SearchRoute::class, R.string.tab_search, Icons.Rounded.Search, Icons.Rounded.Search),
    MORE(MoreRoute, MoreRoute::class, R.string.tab_more, Icons.Outlined.Widgets, Icons.Rounded.Widgets),
}

/**
 * Échafaudage des onglets : barre de navigation courte sur téléphone, rail sur
 * tablette et écran déplié ([NavigationSuiteScaffold] adaptatif).
 */
@Composable
fun MainScaffold() {
    val tabsNav = rememberNavController()
    val chrome = LocalMainChrome.current
    val haptics = LocalHapticFeedback.current
    val backStackEntry by tabsNav.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val suiteState = rememberNavigationSuiteScaffoldState()
    val suiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(currentWindowAdaptiveInfoV2())

    LaunchedEffect(chrome.navigationHidden) {
        if (chrome.navigationHidden) suiteState.hide() else suiteState.show()
    }

    NavigationSuiteScaffold(
        navigationSuiteType = suiteType,
        state = suiteState,
        navigationItems = {
            TopLevelDestination.entries.forEach { tab ->
                val selected = destination?.hierarchy?.any { it.hasRoute(tab.routeClass) } == true
                NavigationSuiteItem(
                    selected = selected,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        tabsNav.navigateToTab(tab)
                    },
                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                    navigationSuiteType = suiteType,
                )
            }
        },
    ) {
        CompositionLocalProvider(LocalTabsNavController provides tabsNav) {
            NavHost(
                navController = tabsNav,
                startDestination = PhotosRoute,
                enterTransition = { fadeIn(tween(200)) },
                exitTransition = { fadeOut(tween(120)) },
            ) {
                composable<PhotosRoute> { TimelineScreen() }
                composable<MoreRoute> { MoreScreen() }
                composable<SettingsRoute> { SettingsScreen(onBack = { tabsNav.popBackStack() }) }
                composable<AboutRoute> { AboutScreen(onBack = { tabsNav.popBackStack() }) }
                tabDestinations(tabsNav)
            }
        }
    }
}

/** Navigation entre onglets : une pile par onglet, conservée et restaurée. */
fun NavHostController.navigateToTab(tab: TopLevelDestination) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
