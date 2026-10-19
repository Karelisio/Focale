package app.focale.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.image.thumbnail
import app.focale.core.model.Album
import coil3.compose.AsyncImage

/**
 * Choix des dossiers masqués de la timeline : leurs médias restent dans Albums et la
 * recherche, mais n'apparaissent plus dans Photos, les souvenirs ni le widget.
 */
@Composable
fun HiddenFoldersSheet(
    folders: List<Album.Folder>,
    hidden: Set<Long>,
    onHiddenChange: (bucketId: Long, hidden: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            text = stringResource(R.string.settings_hidden_folders),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = stringResource(R.string.settings_hidden_folders_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.navigationBarsPadding()) {
            items(folders, key = { it.bucketId }) { folder ->
                val isHidden = folder.bucketId in hidden
                ListItem(
                    headlineContent = { Text(folder.name, maxLines = 1) },
                    supportingContent = { Text(pluralStringResource(R.plurals.media_count, folder.count, folder.count)) },
                    leadingContent = {
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            folder.cover?.let { cover ->
                                AsyncImage(
                                    model = cover.thumbnail(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(48.dp),
                                )
                            }
                        }
                    },
                    trailingContent = {
                        // Interrupteur « visible dans la timeline ».
                        Switch(checked = !isHidden, onCheckedChange = { visible -> onHiddenChange(folder.bucketId, !visible) })
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
