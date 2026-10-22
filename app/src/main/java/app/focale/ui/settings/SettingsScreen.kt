package app.focale.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.core.model.GroupMode
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.components.Choice
import app.focale.ui.components.ConnectedChoiceGroup
import kotlinx.coroutines.launch

/** Écran des réglages. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    backupViewModel: DataBackupViewModel = hiltViewModel(),
    autoBackupViewModel: DataAutoBackupViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val autoBackup by autoBackupViewModel.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Sélecteurs et dialogues de la sauvegarde des données, hors de la liste.
    val backupActions = rememberDataBackup(backupViewModel, autoBackupViewModel) { message ->
        scope.launch { snackbarHostState.showSnackbar(message.resolve(context)) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item { AppearanceSection(settings, viewModel) }
            item { GridSection(settings, viewModel) }
            item { VideoSection(settings, viewModel) }
            settingsExtraSections(settings, viewModel)
            item { DataBackupSection(autoBackup, backupActions) }
            item { LanguageSection() }
            item { UpdatesSection(settings, viewModel) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun AppearanceSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val themeChoices = listOf(
        Choice(ThemeMode.SYSTEM, stringResource(R.string.settings_theme_system), Icons.Outlined.SettingsSuggest),
        Choice(ThemeMode.LIGHT, stringResource(R.string.settings_theme_light), Icons.Outlined.LightMode),
        Choice(ThemeMode.DARK, stringResource(R.string.settings_theme_dark), Icons.Outlined.DarkMode),
    )
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    SettingsGroup(stringResource(R.string.settings_appearance)) {
        item { shapes ->
            SettingsCustom(title = stringResource(R.string.settings_theme), shapes = shapes) {
                ConnectedChoiceGroup(choices = themeChoices, selected = settings.themeMode, onSelect = viewModel::setThemeMode)
            }
        }
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_amoled),
                subtitle = stringResource(R.string.settings_amoled_summary),
                icon = Icons.Outlined.Contrast,
                checked = settings.amoled,
                onCheckedChange = viewModel::setAmoled,
                shapes = shapes,
            )
        }
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(
                    if (dynamicAvailable) R.string.settings_dynamic_color_summary else R.string.settings_dynamic_color_unavailable,
                ),
                icon = Icons.Outlined.Wallpaper,
                checked = settings.dynamicColor && dynamicAvailable,
                enabled = dynamicAvailable,
                onCheckedChange = viewModel::setDynamicColor,
                shapes = shapes,
            )
        }
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_photo_color),
                subtitle = stringResource(R.string.settings_photo_color_summary),
                icon = Icons.Outlined.Palette,
                checked = settings.colorFromPhoto,
                onCheckedChange = viewModel::setColorFromPhoto,
                shapes = shapes,
            )
        }
    }
}

@Composable
private fun GridSection(settings: AppSettings, viewModel: SettingsViewModel) {
    var choosingFolders by rememberSaveable { mutableStateOf(false) }
    if (choosingFolders) {
        val folders by viewModel.folders.collectAsStateWithLifecycle()
        HiddenFoldersSheet(
            folders = folders,
            hidden = settings.hiddenFolders,
            onHiddenChange = viewModel::setFolderHidden,
            onDismiss = { choosingFolders = false },
        )
    }
    val groupChoices = listOf(
        Choice(GroupMode.AUTO, stringResource(R.string.settings_grouping_auto)),
        Choice(GroupMode.DAY, stringResource(R.string.settings_grouping_day)),
        Choice(GroupMode.MONTH, stringResource(R.string.settings_grouping_month)),
        Choice(GroupMode.YEAR, stringResource(R.string.settings_grouping_year)),
    )
    SettingsGroup(stringResource(R.string.settings_grid)) {
        item { shapes ->
            SettingsCustom(
                title = stringResource(R.string.settings_grouping),
                subtitle = stringResource(R.string.settings_grouping_summary),
                shapes = shapes,
            ) {
                ConnectedChoiceGroup(choices = groupChoices, selected = settings.groupMode, onSelect = viewModel::setGroupMode)
            }
        }
        item { shapes ->
            val hiddenCount = settings.hiddenFolders.size
            SettingsAction(
                title = stringResource(R.string.settings_hidden_folders),
                subtitle = if (hiddenCount == 0) {
                    stringResource(R.string.settings_hidden_folders_none)
                } else {
                    pluralStringResource(R.plurals.settings_hidden_folders_count, hiddenCount, hiddenCount)
                },
                icon = Icons.Outlined.VisibilityOff,
                shapes = shapes,
                onClick = { choosingFolders = true },
            )
        }
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_memories),
                subtitle = stringResource(R.string.settings_memories_summary),
                icon = Icons.Outlined.AutoAwesome,
                checked = settings.showMemories,
                onCheckedChange = viewModel::setShowMemories,
                shapes = shapes,
            )
        }
    }
}

@Composable
private fun VideoSection(settings: AppSettings, viewModel: SettingsViewModel) {
    SettingsGroup(stringResource(R.string.settings_videos)) {
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_autoplay),
                icon = Icons.Outlined.PlayCircle,
                checked = settings.autoplayVideos,
                onCheckedChange = viewModel::setAutoplayVideos,
                shapes = shapes,
            )
        }
        item { shapes ->
            SettingsSwitch(
                title = stringResource(R.string.settings_mute),
                icon = Icons.AutoMirrored.Outlined.VolumeOff,
                checked = settings.muteVideosByDefault,
                onCheckedChange = viewModel::setMuteVideos,
                shapes = shapes,
            )
        }
    }
}

@Composable
private fun LanguageSection() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    SettingsGroup(stringResource(R.string.settings_language)) {
        item { shapes ->
            SettingsAction(
                title = stringResource(R.string.settings_language_app),
                subtitle = stringResource(R.string.settings_language_summary),
                icon = Icons.Outlined.Language,
                shapes = shapes,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
            )
        }
    }
}
