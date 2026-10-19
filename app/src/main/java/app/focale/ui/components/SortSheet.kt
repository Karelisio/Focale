package app.focale.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaSort
import app.focale.core.model.SortField

/** Feuille « Tri et filtres » : champ et sens du tri, type de média. */
@Composable
fun SortSheet(
    sort: MediaSort,
    onSortChange: (MediaSort) -> Unit,
    onDismiss: () -> Unit,
    filter: MediaFilter? = null,
    onFilterChange: ((MediaFilter) -> Unit)? = null,
) {
    val fields = listOf(
        Triple(SortField.DATE_TAKEN, R.string.sort_date_taken, Icons.Outlined.CalendarMonth),
        Triple(SortField.DATE_MODIFIED, R.string.sort_date_modified, Icons.Outlined.EditCalendar),
        Triple(SortField.NAME, R.string.sort_name, Icons.Outlined.SortByAlpha),
        Triple(SortField.SIZE, R.string.sort_size, Icons.Outlined.SdStorage),
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.sort_title),
                style = MaterialTheme.typography.titleLargeEmphasized,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
            fields.forEachIndexed { index, (field, label, icon) ->
                SortRow(
                    label = stringResource(label),
                    icon = icon,
                    selected = sort.field == field,
                    index = index,
                    count = fields.size,
                    onClick = { onSortChange(sort.copy(field = field)) },
                )
            }
            ConnectedChoiceGroup(
                choices = listOf(
                    Choice(false, stringResource(R.string.sort_descending), Icons.Rounded.ArrowDownward),
                    Choice(true, stringResource(R.string.sort_ascending), Icons.Rounded.ArrowUpward),
                ),
                selected = sort.ascending,
                onSelect = { onSortChange(sort.copy(ascending = it)) },
                modifier = Modifier.padding(top = 12.dp),
            )
            if (filter != null && onFilterChange != null) {
                Text(
                    text = stringResource(R.string.filter_title),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 4.dp),
                )
                MediaFilterChips(selected = filter, onSelect = onFilterChange, modifier = Modifier.padding(horizontal = 0.dp))
            }
        }
    }
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
