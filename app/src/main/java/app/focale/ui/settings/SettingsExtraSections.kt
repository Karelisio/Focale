package app.focale.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.focale.R
import app.focale.core.settings.AppSettings

/** Sections ajoutées par les fonctionnalités (fichiers, vie privée, analyse, carte, coffre). */
fun LazyListScope.settingsExtraSections(settings: AppSettings, viewModel: SettingsViewModel) {
    item { AnalysisSection(settings, viewModel) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        item { FilesSection() }
    }
}

@Composable
private fun AnalysisSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsGroup(stringResource(R.string.settings_analysis)) {
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_analysis_enabled),
                subtitle = stringResource(R.string.settings_analysis_enabled_summary),
                icon = Icons.Outlined.AutoAwesome,
                checked = settings.analysisEnabled,
                onCheckedChange = viewModel::setAnalysisEnabled,
                shapes = shapes,
            )
        }
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_analysis_charging),
                subtitle = stringResource(R.string.settings_analysis_charging_summary),
                icon = Icons.Outlined.BatteryChargingFull,
                checked = settings.analysisOnlyWhileCharging,
                enabled = settings.analysisEnabled,
                onCheckedChange = viewModel::setAnalysisOnlyWhileCharging,
                shapes = shapes,
            )
        }
    }
}

/** Accès spécial « Gestion des médias » : supprime les confirmations à chaque opération. */
@Composable
private fun FilesSection() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val context = LocalContext.current
    var granted by remember { mutableStateOf(MediaStore.canManageMedia(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = MediaStore.canManageMedia(context) }
    LaunchedEffect(Unit) { granted = MediaStore.canManageMedia(context) }
    SettingsGroup(stringResource(R.string.settings_files)) {
        item { shapes ->
            SettingsAction(
                title = stringResource(R.string.settings_manage_media),
                subtitle = stringResource(
                    if (granted) R.string.settings_manage_media_on else R.string.settings_manage_media_off,
                ),
                icon = Icons.Outlined.PermMedia,
                shapes = shapes,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }
    }
}
