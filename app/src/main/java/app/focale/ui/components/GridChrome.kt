package app.focale.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.model.MediaFilter

/** Barre supérieure du mode sélection : nombre d'éléments animé et actions. */
@Composable
fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically(spring(stiffness = Spring.StiffnessMedium)) { if (up) it else -it } + fadeIn())
                        .togetherWith(slideOutVertically { if (up) -it else it } + fadeOut())
                },
                label = "selectionCount",
            ) { value ->
                Text(
                    text = pluralStringResource(R.plurals.selection_count, value, value),
                    style = MaterialTheme.typography.titleLargeEmphasized,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onClear, shapes = androidx.compose.material3.IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear_selection))
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier,
    )
}

/** Puces de filtre par type de média. */
@Composable
fun MediaFilterChips(
    selected: MediaFilter,
    onSelect: (MediaFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        MediaFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            FilterChip(
                selected = isSelected,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(filter)
                },
                label = { Text(stringResource(filter.labelRes())) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                shape = MaterialTheme.shapes.large,
            )
        }
    }
}

fun MediaFilter.labelRes(): Int = when (this) {
    MediaFilter.ALL -> R.string.filter_all
    MediaFilter.PHOTOS -> R.string.filter_photos
    MediaFilter.VIDEOS -> R.string.filter_videos
    MediaFilter.SCREENSHOTS -> R.string.filter_screenshots
    MediaFilter.GIFS -> R.string.filter_gifs
}
