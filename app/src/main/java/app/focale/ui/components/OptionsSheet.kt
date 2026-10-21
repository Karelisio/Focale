package app.focale.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import app.focale.R
import kotlinx.coroutines.launch

/** Option d'un menu « Plus » ou d'une feuille d'options : libellé, icône et action, grisée sans [enabled]. */
@Immutable
data class MenuOption(val label: String, val icon: ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

/** Bouton « Plus » d'une barre supérieure et son menu des [options]. */
@Composable
fun OptionsMenu(options: List<MenuOption>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { Icon(option.icon, contentDescription = null) },
                    enabled = option.enabled,
                    onClick = {
                        open = false
                        option.onClick()
                    },
                )
            }
        }
    }
}

/**
 * Feuille des options d'un élément (album…), ouverte par un appui long sur sa carte : [header],
 * puis les [options]. L'option choisie s'applique une fois la feuille refermée (un dialogue ne
 * s'ouvre pas sous elle), puis [onDismiss] est appelé, comme quand on la referme sans rien choisir.
 */
@Composable
fun OptionsSheet(
    header: @Composable () -> Unit,
    options: List<MenuOption>,
    onDismiss: () -> Unit,
    securePolicy: SecureFlagPolicy = SecureFlagPolicy.Inherit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    // Une option par ouverture : un second appui pendant la fermeture est ignoré.
    var picked by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(securePolicy = securePolicy),
    ) {
        OptionsSheetContent(
            header = header,
            options = options,
            onPick = { option ->
                if (!picked) {
                    picked = true
                    scope.launch { sheetState.hide() }.invokeOnCompletion {
                        option.onClick()
                        onDismiss()
                    }
                }
            },
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}

/** Contenu de la feuille d'options, séparé de la feuille pour les captures de référence. */
@Composable
internal fun OptionsSheetContent(
    header: @Composable () -> Unit,
    options: List<MenuOption>,
    onPick: (MenuOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp)) { header() }
        options.forEachIndexed { index, option ->
            SegmentedListItem(
                onClick = { onPick(option) },
                enabled = option.enabled,
                shapes = ListItemDefaults.segmentedShapes(index, options.size),
                leadingContent = { Icon(option.icon, contentDescription = null) },
            ) {
                Text(option.label)
            }
        }
    }
}

/** En-tête d'une feuille d'options : vignette [leading], [title] et [subtitle] (nombre d'éléments…). */
@Composable
fun OptionsSheetHeader(title: String, subtitle: String, leading: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        leading()
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLargeEmphasized,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Toucher d'une carte (album…) : un appui l'ouvre, un appui long ([onOptions], avec son retour
 * haptique) en donne les options. L'ondulation passe par [interactionSource], à poser sur la partie
 * illustrée. TalkBack lit la carte d'un bloc et propose ses options deux fois : appui long nommé
 * [optionsLabel] et action personnalisée du même nom.
 */
fun Modifier.clickableWithOptions(
    interactionSource: MutableInteractionSource,
    optionsLabel: String,
    onOptions: (() -> Unit)?,
    onClick: () -> Unit,
): Modifier = combinedClickable(
    interactionSource = interactionSource,
    indication = null,
    onLongClickLabel = optionsLabel.takeIf { onOptions != null },
    onLongClick = onOptions,
    onClick = onClick,
).then(
    if (onOptions == null) {
        Modifier
    } else {
        Modifier.semantics {
            customActions = listOf(
                CustomAccessibilityAction(optionsLabel) {
                    onOptions()
                    true
                },
            )
        }
    },
)
