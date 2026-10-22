package app.focale.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.BuildConfig
import app.focale.R
import app.focale.core.settings.AppSettings
import app.focale.ui.analysis.AnalysisStatusContent
import app.focale.ui.analysis.analysisStatusText
import app.focale.ui.components.Choice
import app.focale.ui.components.ConnectedChoiceGroup
import app.focale.ui.update.UpdateControls
import app.focale.ui.update.UpdateViewModel
import app.focale.ui.update.updateStatusText

/** Sections ajoutées par les fonctionnalités (fichiers, vie privée, analyse, carte, coffre). */
fun LazyListScope.settingsExtraSections(settings: AppSettings, viewModel: SettingsViewModel) {
    item { SlideshowSection(settings, viewModel) }
    if (BuildConfig.ONLINE_MAP_AVAILABLE) {
        item { MapSection(settings, viewModel) }
    }
    if (BuildConfig.TV_CAST_AVAILABLE) {
        item { CastSection(settings, viewModel) }
    }
    item { PrivacySection(settings, viewModel) }
    item { AnalysisSection(settings, viewModel) }
    item { FilesSection(viewModel) }
}

@Composable
private fun SlideshowSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val choices = SLIDESHOW_INTERVALS.map { Choice(it, stringResource(R.string.settings_seconds, it)) }
    SettingsGroup(stringResource(R.string.action_slideshow)) {
        item { shapes ->
            SettingsCustom(title = stringResource(R.string.settings_slideshow_interval), shapes = shapes) {
                ConnectedChoiceGroup(
                    choices = choices,
                    selected = settings.slideshowIntervalSeconds,
                    onSelect = viewModel::setSlideshowInterval,
                )
            }
        }
    }
}

private val SLIDESHOW_INTERVALS = listOf(3, 5, 10)

/** Fond de carte en ligne : seul usage du réseau, désactivé par défaut. */
@Composable
private fun MapSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsGroup(stringResource(R.string.map_title)) {
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_online_map),
                subtitle = stringResource(R.string.settings_online_map_summary),
                icon = Icons.Outlined.Public,
                checked = settings.onlineMapTiles,
                onCheckedChange = viewModel::setOnlineMapTiles,
                shapes = shapes,
            )
        }
    }
}

/** Diffusion vers les TV : recherche automatique (réseau local seulement), désactivable. */
@Composable
private fun CastSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsGroup(stringResource(R.string.cast_settings_title)) {
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.cast_settings_discovery),
                subtitle = stringResource(R.string.cast_settings_discovery_summary),
                icon = Icons.Outlined.Cast,
                checked = settings.tvDiscovery,
                onCheckedChange = viewModel::setTvDiscovery,
                shapes = shapes,
            )
        }
    }
}

/** Partage sans métadonnées et verrouillage automatique du coffre. */
@Composable
private fun PrivacySection(settings: AppSettings, viewModel: SettingsViewModel) {
    val lockChoices = listOf(
        Choice(0, stringResource(R.string.vault_auto_lock_immediately)),
        Choice(60, stringResource(R.string.vault_auto_lock_minutes, 1)),
        Choice(300, stringResource(R.string.vault_auto_lock_minutes, 5)),
        Choice(900, stringResource(R.string.vault_auto_lock_minutes, 15)),
    )
    SettingsGroup(stringResource(R.string.settings_privacy)) {
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_strip_metadata),
                subtitle = stringResource(R.string.settings_strip_metadata_summary),
                icon = Icons.Outlined.LocationOff,
                checked = settings.stripMetadataOnShare,
                onCheckedChange = viewModel::setStripMetadata,
                shapes = shapes,
            )
        }
        item { shapes ->
            SettingsCustom(
                title = stringResource(R.string.vault_auto_lock),
                subtitle = stringResource(R.string.vault_auto_lock_summary),
                shapes = shapes,
            ) {
                ConnectedChoiceGroup(
                    choices = lockChoices,
                    selected = settings.vaultAutoLockSeconds,
                    onSelect = viewModel::setVaultAutoLock,
                )
            }
        }
    }
}

@Composable
private fun AnalysisSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val status by viewModel.analysisStatus.collectAsStateWithLifecycle()
    SettingsGroup(stringResource(R.string.settings_analysis)) {
        val current = status
        if (settings.analysisEnabled && current != null) {
            item { shapes ->
                SettingsCustom(
                    title = stringResource(R.string.analysis_state),
                    subtitle = analysisStatusText(current),
                    shapes = shapes,
                ) {
                    AnalysisStatusContent(status = current, onRunNow = viewModel::runAnalysisNow)
                }
            }
        }
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

/** Application par défaut pour les photos et, Android 12+, accès spécial « Gestion des médias ». */
@Composable
private fun FilesSection(viewModel: SettingsViewModel) {
    SettingsGroup(stringResource(R.string.settings_files)) {
        item { shapes -> DefaultAppSetting(shapes = shapes, samplePhoto = viewModel::samplePhoto) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item { shapes -> ManageMediaSetting(shapes) }
        }
    }
}

/** Accès spécial « Gestion des médias » : supprime les confirmations à chaque opération. */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
private fun ManageMediaSetting(shapes: ListItemShapes) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(MediaStore.canManageMedia(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = MediaStore.canManageMedia(context) }
    LaunchedEffect(Unit) { granted = MediaStore.canManageMedia(context) }
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

/** Version installée, recherche et installation des mises à jour (variante standard). */
@Composable
fun UpdatesSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    updates: UpdateViewModel = hiltViewModel(),
) {
    if (!updates.supported) return
    val state by updates.state.collectAsStateWithLifecycle()
    SettingsGroup(stringResource(R.string.update_title)) {
        item { shapes ->
            SettingsCustom(
                title = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                subtitle = updateStatusText(state),
                shapes = shapes,
            ) {
                UpdateControls(state = state, onCheck = updates::check, onInstall = updates::install)
            }
        }
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.update_auto),
                subtitle = stringResource(R.string.update_auto_summary),
                icon = Icons.Outlined.Update,
                checked = settings.autoCheckUpdates,
                onCheckedChange = viewModel::setAutoCheckUpdates,
                shapes = shapes,
            )
        }
    }
}
