package app.focale.ui.cast

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.outlined.ConnectedTv
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.cast.CastState
import app.focale.cast.CastTarget
import app.focale.cast.TvStatus
import app.focale.cast.display.ExternalDisplay
import app.focale.cast.dlna.DiscoveryProblem
import app.focale.cast.dlna.DlnaRenderer
import app.focale.cast.dlna.LocalNetworks
import app.focale.ui.components.rememberNotificationPermission
import kotlin.math.roundToInt

/**
 * Feuille « Diffuser » : diffusion en cours (avec « Arrêter » et le volume de la TV), écrans
 * externes, puis TV du réseau local (variante standard) et état de leur recherche, relancée à
 * chaque ouverture. Android 17+ : l'accès au réseau local est demandé ici, quand il sert.
 */
@Composable
fun CastSheet(viewModel: CastViewModel, state: CastState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val askNotifications = rememberNotificationPermission()
    // Refus définitif : le système ne redemande plus, les réglages de l'application le peuvent.
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.search() else permissionBlocked = true
    }
    LaunchedEffect(Unit) { if (state.tvSupported) viewModel.search() }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CastSheetContent(
            state = state,
            permissionBlocked = permissionBlocked,
            onSelectScreen = viewModel::selectScreen,
            onSelectTv = { renderer ->
                // La notification de diffusion (arrière-plan) a besoin de la permission, Android 13+.
                askNotifications()
                viewModel.selectTv(renderer)
            },
            onSearch = viewModel::search,
            onRequestPermission = {
                if (permissionBlocked) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    permission.launch(LocalNetworks.LOCAL_NETWORK_PERMISSION)
                }
            },
            onStop = viewModel::stop,
            onVolume = viewModel::setVolume,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        )
    }
}

/** Contenu de la feuille, séparé de la feuille pour les tests et les captures de référence. */
@Composable
internal fun CastSheetContent(
    state: CastState,
    onSelectScreen: (ExternalDisplay) -> Unit,
    onSelectTv: (DlnaRenderer) -> Unit,
    onSearch: () -> Unit,
    onRequestPermission: () -> Unit,
    onStop: (CastTarget) -> Unit,
    onVolume: (Int) -> Unit,
    modifier: Modifier = Modifier,
    permissionBlocked: Boolean = false,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.cast_sheet_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 16.dp),
        )
        state.target?.let { target ->
            CastStatusCard(target = target, connecting = state.tvStatus == TvStatus.CONNECTING, volume = state.volume, onStop = { onStop(target) }, onVolume = onVolume)
        }
        if (state.screens.isNotEmpty()) {
            SectionTitle(stringResource(R.string.cast_screens))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                state.screens.forEachIndexed { index, display ->
                    val selected = (state.target as? CastTarget.Screen)?.display?.id == display.id
                    DeviceItem(
                        selected = selected,
                        index = index,
                        count = state.screens.size,
                        icon = { Icon(Icons.Outlined.Monitor, contentDescription = null) },
                        title = display.name.ifBlank { stringResource(R.string.cast_screen_default_name) },
                        subtitle = stringResource(R.string.cast_screen_summary),
                        onClick = { if (!selected) onSelectScreen(display) },
                    )
                }
            }
        }
        if (state.tvSupported) {
            TvSection(state, permissionBlocked, onSelectTv, onSearch, onRequestPermission)
            Text(
                text = stringResource(R.string.cast_privacy_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp),
            )
        }
    }
}

/** Diffusion en cours : appareil, état, volume de la TV s'il est réglable, « Arrêter ». */
@Composable
private fun CastStatusCard(target: CastTarget, connecting: Boolean, volume: Int?, onStop: () -> Unit, onVolume: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CastConnected, contentDescription = null)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(
                        text = stringResource(R.string.cast_casting_to, castTargetName(target)),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Text(
                        text = when {
                            connecting -> stringResource(R.string.cast_connecting)
                            target is CastTarget.Screen -> stringResource(R.string.cast_screen_active_summary)
                            else -> stringResource(R.string.cast_tv_active_summary)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (target is CastTarget.Tv && volume != null) {
                VolumeSlider(volume = volume, onVolume = onVolume)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onStop, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.cast_stop))
                }
            }
        }
    }
}

@Composable
private fun VolumeSlider(volume: Int, onVolume: (Int) -> Unit) {
    // Suit le volume de la TV, sauf pendant un glissement ; chaque pas lui est envoyé (regroupés
    // par CastManager).
    val sliderState = rememberSliderState()
    var dragging by remember { mutableStateOf(false) }
    if (!dragging) {
        val fraction = volume / 100f
        if (sliderState.value != fraction) sliderState.value = fraction
    }
    val label = stringResource(R.string.cast_volume)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
        Icon(Icons.AutoMirrored.Outlined.VolumeDown, contentDescription = null)
        Slider(
            state = sliderState,
            onValueChange = {
                sliderState.value = it
                dragging = true
                onVolume((it * 100).roundToInt())
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .semantics { contentDescription = label },
        )
        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null)
    }
}

/** TV trouvées, ou ce qui empêche de les trouver ; titre accompagné de la recherche (indicateur ou relance). */
@Composable
private fun TvSection(
    state: CastState,
    permissionBlocked: Boolean,
    onSelectTv: (DlnaRenderer) -> Unit,
    onSearch: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.cast_tvs), Modifier.weight(1f))
        if (state.searching) {
            LoadingIndicator(Modifier.padding(end = 8.dp, top = 12.dp).size(32.dp))
        } else if (state.problem != DiscoveryProblem.PERMISSION) {
            IconButton(onClick = onSearch, shapes = IconButtonDefaults.shapes(), modifier = Modifier.padding(top = 12.dp)) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.cast_search_again))
            }
        }
    }
    if (state.tvs.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            state.tvs.forEachIndexed { index, renderer ->
                val selected = (state.target as? CastTarget.Tv)?.renderer?.udn == renderer.udn
                DeviceItem(
                    selected = selected,
                    index = index,
                    count = state.tvs.size,
                    icon = { Icon(Icons.Outlined.ConnectedTv, contentDescription = null) },
                    title = renderer.name,
                    subtitle = listOfNotNull(renderer.manufacturer, renderer.model?.takeIf { it != renderer.name })
                        .distinct()
                        .joinToString(" · ")
                        .ifBlank { stringResource(R.string.cast_tv_default_summary) },
                    onClick = { if (!selected) onSelectTv(renderer) },
                )
            }
        }
        return
    }
    val message = when {
        state.problem == DiscoveryProblem.PERMISSION -> R.string.cast_permission
        state.problem == DiscoveryProblem.NO_WIFI -> R.string.cast_no_wifi
        state.searching -> R.string.cast_searching
        else -> R.string.cast_none_found
    }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            state.problem == DiscoveryProblem.PERMISSION -> Button(
                onClick = onRequestPermission,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(stringResource(if (permissionBlocked) R.string.cast_permission_settings else R.string.cast_permission_allow))
            }
            !state.searching -> TextButton(onClick = onSearch, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.cast_search_again))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
    )
}

/** Appareil de la liste : coché quand il reçoit la diffusion. */
@Composable
private fun DeviceItem(
    selected: Boolean,
    index: Int,
    count: Int,
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        leadingContent = icon,
        supportingContent = { Text(subtitle) },
        trailingContent = if (selected) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.width(24.dp)) }
        } else {
            null
        },
    ) {
        Text(title)
    }
}
