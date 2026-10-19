package app.focale.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.permissions.MediaAccessManager

/** Lanceur de la demande de permissions médias (toutes les versions d'Android). */
@Composable
fun rememberMediaPermissionLauncher(onResult: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onResult()
    }
    return remember(launcher) { { launcher.launch(MediaAccessManager.requestedPermissions) } }
}

/**
 * Écran d'accueil lorsque l'accès aux médias n'est pas accordé. Après deux refus,
 * Android n'affiche plus la boîte de dialogue : on propose alors les réglages.
 */
@Composable
fun MediaPermissionScreen(onAccessChanged: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var attempts by rememberSaveable { mutableIntStateOf(0) }
    val request = rememberMediaPermissionLauncher {
        attempts++
        onAccessChanged()
    }
    EmptyState(
        icon = Icons.Rounded.PhotoLibrary,
        title = stringResource(R.string.permission_title),
        message = stringResource(R.string.permission_message),
        shape = MaterialShapes.Flower,
        modifier = modifier,
        action = {
            if (attempts < 2) {
                Button(
                    onClick = request,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(min = ButtonDefaults.MediumContainerHeight),
                ) {
                    Text(stringResource(R.string.permission_grant), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(R.string.permission_open_settings))
                }
            }
        },
    )
}

/** Bannière d'accès partiel (Android 14+) avec raccourci pour modifier la sélection. */
@Composable
fun PartialAccessBanner(onManage: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null)
            Text(
                text = stringResource(R.string.permission_partial),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onManage) { Text(stringResource(R.string.permission_partial_manage)) }
        }
    }
}
