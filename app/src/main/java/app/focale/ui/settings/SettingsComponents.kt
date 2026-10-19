package app.focale.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/** Contenu d'un groupe de réglages : chaque élément reçoit sa forme segmentée. */
class SettingsGroupScope {
    internal val items = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun item(content: @Composable (ListItemShapes) -> Unit) {
        items += content
    }
}

/**
 * Groupe de réglages « segmenté » (Material 3 Expressive) : éléments séparés par un
 * fin écart, le premier et le dernier ayant des coins plus arrondis.
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: SettingsGroupScope.() -> Unit,
) {
    val scope = SettingsGroupScope().apply(content)
    Column(modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 28.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            scope.items.forEachIndexed { index, item ->
                item(ListItemDefaults.segmentedShapes(index = index, count = scope.items.size))
            }
        }
    }
}

/** Fond des segments : distinct de la page, sinon écarts et coins arrondis disparaissent. */
@Composable
private fun segmentColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

@Composable
fun SettingsSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = LocalHapticFeedback.current
    SegmentedListItem(
        checked = checked,
        onCheckedChange = { value ->
            haptics.performHapticFeedback(if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onCheckedChange(value)
        },
        shapes = shapes,
        colors = segmentColors(),
        enabled = enabled,
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
        },
        modifier = modifier,
    ) {
        Text(title)
    }
}

@Composable
fun SettingsAction(
    title: String,
    onClick: () -> Unit,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        colors = segmentColors(),
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = trailing,
        modifier = modifier,
    ) {
        Text(title)
    }
}

/** Élément libre (groupe de boutons, curseur…) avec titre. */
@Composable
fun SettingsCustom(
    title: String,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    SegmentedListItem(
        shapes = shapes,
        colors = segmentColors(),
        supportingContent = {
            Column {
                subtitle?.let { Text(it) }
                Column(Modifier.padding(top = 12.dp)) { content() }
            }
        },
        modifier = modifier,
    ) {
        Text(title)
    }
}
