package app.focale.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.data.PhotoRotator
import app.focale.edit.SaveMode

/**
 * Barre flottante d'une rotation en attente : « Annuler » revient à 0°, « Enregistrer la
 * rotation » écrit le fichier. Sans perte (JPEG, WebP), l'enregistrement est direct ; sinon
 * un menu propose, comme l'éditeur, une copie ou le remplacement de l'original. Couleurs du
 * thème courant : la visionneuse l'habille de [app.focale.ui.theme.ViewerChromeTheme].
 */
@Composable
internal fun RotationBar(
    options: PhotoRotator.Options,
    saving: Boolean,
    onSave: (SaveMode) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = colors.surfaceContainer.copy(alpha = 0.9f),
            toolbarContentColor = colors.onSurfaceVariant,
        ),
        modifier = modifier,
    ) {
        TextButton(onClick = onCancel, enabled = !saving, shapes = ButtonDefaults.shapes()) {
            Text(stringResource(R.string.action_cancel))
        }
        Box {
            Button(
                onClick = { if (options.lossless) onSave(SaveMode.REPLACE) else menuOpen = true },
                enabled = !saving,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                if (saving) {
                    LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
                } else {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.rotation_save))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Text(
                    text = stringResource(R.string.rotation_reencoded),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_save_copy)) },
                    onClick = {
                        menuOpen = false
                        onSave(SaveMode.COPY)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_save_replace)) },
                    enabled = options.canReplace,
                    onClick = {
                        menuOpen = false
                        onSave(SaveMode.REPLACE)
                    },
                )
            }
        }
    }
}
