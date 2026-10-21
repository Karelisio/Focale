package app.focale.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaSort
import app.focale.core.model.SortField

/** Champ proposé par une feuille de tri. */
@Immutable
data class SortOption<T>(val value: T, @StringRes val label: Int, val icon: ImageVector)

/** Feuille « Tri et filtres » des grilles de la galerie : champ et sens du tri, type de média. */
@Composable
fun SortSheet(
    sort: MediaSort,
    onSortChange: (MediaSort) -> Unit,
    onDismiss: () -> Unit,
    filter: MediaFilter? = null,
    onFilterChange: ((MediaFilter) -> Unit)? = null,
) {
    SortSheet(title = stringResource(R.string.sort_title), onDismiss = onDismiss) {
        SortChoices(
            options = MEDIA_SORT_OPTIONS,
            selected = sort.field,
            ascending = sort.ascending,
            onSelect = { onSortChange(sort.copy(field = it)) },
            onAscendingChange = { onSortChange(sort.copy(ascending = it)) },
        )
        if (filter != null && onFilterChange != null) {
            SortSectionTitle(stringResource(R.string.filter_title))
            MediaFilterChips(selected = filter, onSelect = onFilterChange, modifier = Modifier.padding(horizontal = 0.dp))
        }
    }
}

/** Feuille de tri : titre puis [content], fait de [SortChoices] et de titres de section. */
@Composable
fun SortSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
            content()
        }
    }
}

/** Titre d'une section de la feuille de tri. */
@Composable
fun SortSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMediumEmphasized,
        modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** Champs de tri, un seul choisi, puis le sens : décroissant ou croissant. */
@Composable
fun <T> SortChoices(
    options: List<SortOption<T>>,
    selected: T,
    ascending: Boolean,
    onSelect: (T) -> Unit,
    onAscendingChange: (Boolean) -> Unit,
) {
    options.forEachIndexed { index, option ->
        SortRow(
            label = stringResource(option.label),
            icon = option.icon,
            selected = selected == option.value,
            index = index,
            count = options.size,
            onClick = { onSelect(option.value) },
        )
    }
    ConnectedChoiceGroup(
        choices = listOf(
            Choice(false, stringResource(R.string.sort_descending), Icons.Rounded.ArrowDownward),
            Choice(true, stringResource(R.string.sort_ascending), Icons.Rounded.ArrowUpward),
        ),
        selected = ascending,
        onSelect = onAscendingChange,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun SortRow(label: String, icon: ImageVector, selected: Boolean, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    ) {
        Text(label)
    }
}

private val MEDIA_SORT_OPTIONS = listOf(
    SortOption(SortField.DATE_TAKEN, R.string.sort_date_taken, Icons.Outlined.CalendarMonth),
    SortOption(SortField.DATE_MODIFIED, R.string.sort_date_modified, Icons.Outlined.EditCalendar),
    SortOption(SortField.NAME, R.string.sort_name, Icons.Outlined.SortByAlpha),
    SortOption(SortField.SIZE, R.string.sort_size, Icons.Outlined.SdStorage),
)
