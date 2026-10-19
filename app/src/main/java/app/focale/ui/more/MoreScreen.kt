package app.focale.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.ui.navigation.AboutRoute
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.SettingsRoute

/** Raccourci de l'onglet Plus. */
data class MoreEntry(
    val title: String,
    val subtitle: String?,
    val icon: ImageVector,
    val shape: RoundedPolygon,
    val containerColor: Color,
    val contentColor: Color,
    val onClick: () -> Unit,
)

/** Onglet Plus : fonctionnalités secondaires (favoris, corbeille, coffre…) et réglages. */
@Composable
fun MoreScreen(viewModel: MoreViewModel = hiltViewModel()) {
    val tabsNav = LocalTabsNavController.current
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val entries = moreEntries(counts, tabsNav)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tab_more)) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.title }) { entry -> FeatureTile(entry) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    MoreListItem(
                        icon = Icons.Outlined.Settings,
                        title = stringResource(R.string.settings_title),
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                        onClick = { tabsNav.navigate(SettingsRoute) },
                    )
                    MoreListItem(
                        icon = Icons.Outlined.Info,
                        title = stringResource(R.string.about_title),
                        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                        onClick = { tabsNav.navigate(AboutRoute) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureTile(entry: MoreEntry) {
    Card(
        onClick = entry.onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(entry.shape.toShape())
                    .background(entry.containerColor),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                Icon(entry.icon, contentDescription = null, tint = entry.contentColor, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(entry.title, style = MaterialTheme.typography.titleMediumEmphasized)
            entry.subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MoreListItem(icon: ImageVector, title: String, shapes: ListItemShapes, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}
