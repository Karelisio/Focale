package app.focale.ui.more

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import app.focale.R
import app.focale.core.model.MediaQuery
import app.focale.ui.navigation.CollectionKind
import app.focale.ui.navigation.TrashRoute
import app.focale.ui.navigation.openCollection

/** Tuiles de l'onglet Plus. */
@Composable
fun moreEntries(counts: MoreCounts, tabsNav: NavHostController): List<MoreEntry> {
    val colors = MaterialTheme.colorScheme
    val favoritesTitle = stringResource(R.string.collection_favorites)
    return listOf(
        MoreEntry(
            title = favoritesTitle,
            subtitle = pluralStringResource(R.plurals.media_count, counts.favorites, counts.favorites),
            icon = Icons.Outlined.Favorite,
            shape = MaterialShapes.Heart,
            containerColor = colors.tertiaryContainer,
            contentColor = colors.onTertiaryContainer,
            onClick = { tabsNav.openCollection(favoritesTitle, MediaQuery(favoritesOnly = true), CollectionKind.FAVORITES) },
        ),
        MoreEntry(
            title = stringResource(R.string.collection_trash),
            subtitle = pluralStringResource(R.plurals.media_count, counts.trash, counts.trash),
            icon = Icons.Outlined.DeleteOutline,
            shape = MaterialShapes.Pill,
            containerColor = colors.errorContainer,
            contentColor = colors.onErrorContainer,
            onClick = { tabsNav.navigate(TrashRoute) },
        ),
    )
}
