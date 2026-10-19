package app.focale.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** Option d'un groupe de boutons connectés. */
data class Choice<T>(val value: T, val label: String, val icon: ImageVector? = null)

/**
 * Groupe de boutons bascule connectés (Material 3 Expressive) : la forme du bouton
 * coché se transforme en pilule, avec retour haptique. En mode [compact], seul le
 * bouton coché affiche son libellé et s'élargit ; les autres montrent leur icône.
 */
@Composable
fun <T> ConnectedChoiceGroup(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = modifier.fillMaxWidth(),
    ) {
        choices.forEachIndexed { index, choice ->
            val checked = choice.value == selected
            val showLabel = !compact || checked || choice.icon == null
            val weight by animateFloatAsState(if (compact && checked) COMPACT_SELECTED_WEIGHT else 1f, label = "choiceWeight")
            ToggleButton(
                checked = checked,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(choice.value)
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .weight(weight)
                    .semantics { role = Role.RadioButton },
            ) {
                choice.icon?.let {
                    Icon(it, contentDescription = if (showLabel) null else choice.label, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
                    if (showLabel) Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                }
                if (showLabel) Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val COMPACT_SELECTED_WEIGHT = 2.6f
