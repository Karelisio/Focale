package app.focale.ui.map

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.BuildConfig
import app.focale.R
import app.focale.core.database.GeoPhoto
import app.focale.core.model.MediaQuery
import app.focale.map.Basemap
import app.focale.map.PhotoCluster
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.openCollection
import app.focale.ui.navigation.openViewer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Carte des photos géolocalisées. Hors ligne par défaut, sur un fond vectoriel embarqué
 * (Natural Earth) aux couleurs du thème ; le fond OpenStreetMap en ligne n'est utilisé
 * que s'il a été activé dans les réglages (variante standard uniquement).
 */
@Composable
fun MapScreen(onBack: () -> Unit, viewModel: MapViewModel = hiltViewModel()) {
    val resources = LocalResources.current
    val tabsNav = LocalTabsNavController.current
    val rootNav = LocalRootNavController.current
    val scope = rememberCoroutineScope()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val basemap by viewModel.basemap.collectAsStateWithLifecycle()
    val online by viewModel.onlineTiles.collectAsStateWithLifecycle()
    val analysisEnabled by viewModel.analysisEnabled.collectAsStateWithLifecycle()
    var fitRequests by remember { mutableIntStateOf(0) }
    val geotaggedTitle = stringResource(R.string.map_geotagged_title)
    val hasPhotos = !photos.isNullOrEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.map_title))
                        photos?.takeIf { it.isNotEmpty() }?.let { list ->
                            Text(
                                text = pluralStringResource(R.plurals.map_photo_count, list.size, list.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (hasPhotos) {
                        IconButton(
                            onClick = { tabsNav.openCollection(geotaggedTitle, MediaQuery(geotaggedOnly = true)) },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.map_show_grid))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (hasPhotos) {
                FloatingActionButton(onClick = { fitRequests++ }) {
                    Icon(Icons.Outlined.ZoomOutMap, contentDescription = stringResource(R.string.map_fit))
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            key(online) {
                PhotoMap(
                    photos = photos.orEmpty(),
                    basemap = basemap,
                    online = online,
                    camera = viewModel.camera,
                    onCameraChange = { viewModel.camera = it },
                    fitRequests = fitRequests,
                    onClusterTap = { cluster ->
                        if (cluster.count == 1) {
                            scope.launch {
                                viewModel.item(cluster.cover.mediaId)?.let { item ->
                                    rootNav.openViewer(MediaQuery(mediaIds = listOf(item.id)), item)
                                }
                            }
                        } else {
                            tabsNav.openCollection(clusterTitle(resources, cluster), MediaQuery(area = cluster.area))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            ) {
                Text(
                    text = stringResource(if (online) R.string.map_attribution_osm else R.string.map_attribution_offline),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (photos?.isEmpty() == true) {
                MapHint(
                    message = stringResource(if (analysisEnabled) R.string.map_empty_message else R.string.map_analysis_off),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

private fun clusterTitle(resources: Resources, cluster: PhotoCluster): String =
    cluster.cover.place ?: resources.getQuantityString(R.plurals.media_count, cluster.count, cluster.count)

@Composable
private fun MapHint(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = modifier
            .padding(32.dp)
            .widthIn(max = 360.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(Icons.Outlined.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            Text(
                text = stringResource(R.string.map_empty_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Vue osmdroid intégrée à Compose, avec fond hors ligne, noms de pays et groupes de photos. */
@Composable
private fun PhotoMap(
    photos: List<GeoPhoto>,
    basemap: Basemap?,
    online: Boolean,
    camera: MapCamera?,
    onCameraChange: (MapCamera) -> Unit,
    fitRequests: Int,
    onClusterTap: (PhotoCluster) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val french = LocalConfiguration.current.locales[0].language == "fr"
    val currentOnTap by rememberUpdatedState(onClusterTap)
    val currentOnCameraChange by rememberUpdatedState(onCameraChange)

    val mapView = remember { createMapView(context, online, camera) }
    val clusters = remember(mapView) {
        PhotoClusterOverlay(context, scope) { currentOnTap(it) }.also {
            it.attach(mapView)
            mapView.overlays.add(it)
        }
    }
    var basemapOverlay by remember(mapView) { mutableStateOf<BasemapOverlay?>(null) }
    var labelOverlay by remember(mapView) { mutableStateOf<CountryLabelOverlay?>(null) }
    var fitted by remember(mapView) { mutableStateOf(camera != null) }

    // Fond vectoriel construit hors du thread principal (près de 80 000 points).
    LaunchedEffect(mapView, basemap, french) {
        if (online || basemap == null) return@LaunchedEffect
        val (base, labels) = withContext(Dispatchers.Default) {
            BasemapOverlay(basemap, density) to CountryLabelOverlay(basemap.labels, french, context)
        }
        basemapOverlay?.let(mapView.overlays::remove)
        labelOverlay?.let(mapView.overlays::remove)
        mapView.overlays.add(0, base)
        mapView.overlays.add(1, labels)
        basemapOverlay = base
        labelOverlay = labels
    }

    val water = lerp(colors.surfaceContainerLow, colors.primary, 0.10f).toArgb()
    val land = colors.surfaceContainerHighest.toArgb()
    val dark = colors.surface.luminance() < 0.5f
    SideEffect {
        mapView.setBackgroundColor(water)
        basemapOverlay?.setColors(land = land, water = water, border = colors.outlineVariant.toArgb())
        labelOverlay?.setColors(label = colors.onSurfaceVariant.toArgb(), background = land)
        clusters.setColors(
            ringColor = colors.surface.toArgb(),
            placeholderColor = colors.primaryContainer.toArgb(),
            badgeColor = colors.primary.toArgb(),
            badgeTextColor = colors.onPrimary.toArgb(),
        )
        if (online) mapView.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        mapView.invalidate()
    }

    LaunchedEffect(photos) {
        clusters.photos = photos
        if (!fitted && photos.isNotEmpty()) {
            fitted = true
            mapView.fitTo(photos, animated = false)
        }
    }
    LaunchedEffect(fitRequests) {
        if (fitRequests > 0 && photos.isNotEmpty()) mapView.fitTo(photos, animated = true)
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            val center = mapView.mapCenter
            currentOnCameraChange(MapCamera(center.latitude, center.longitude, mapView.zoomLevelDouble))
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * Hors ligne, la vue n'a aucun module de tuiles : aucune requête réseau n'est possible.
 * En ligne, osmdroid télécharge les tuiles OpenStreetMap et les met en cache dans le
 * dossier cache privé de l'application.
 */
private fun createMapView(context: Context, online: Boolean, camera: MapCamera?): MapView {
    Configuration.getInstance().apply {
        userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
        osmdroidBasePath = File(context.cacheDir, "osmdroid")
        osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
    }
    val view = if (online) {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setUseDataConnection(true)
        }
    } else {
        MapView(context, MapTileProviderArray(TileSourceFactory.MAPNIK, null, emptyArray<MapTileModuleProviderBase>())).apply {
            setUseDataConnection(false)
            overlayManager.tilesOverlay.isEnabled = false
        }
    }
    return view.apply {
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        isHorizontalMapRepetitionEnabled = false
        isVerticalMapRepetitionEnabled = false
        setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        setMinZoomLevel(MIN_ZOOM)
        setMaxZoomLevel(if (online) MAX_ZOOM_ONLINE else MAX_ZOOM_OFFLINE)
        controller.setZoom(camera?.zoom ?: MIN_ZOOM)
        controller.setCenter(GeoPoint(camera?.latitude ?: 20.0, camera?.longitude ?: 0.0))
    }
}

/** Cadre la carte sur toutes les photos (zone élargie si elles sont au même endroit). */
private fun MapView.fitTo(photos: List<GeoPhoto>, animated: Boolean) {
    val south = photos.minOf { it.latitude }
    val north = photos.maxOf { it.latitude }
    val west = photos.minOf { it.longitude }
    val east = photos.maxOf { it.longitude }
    val margin = 0.02
    val box = BoundingBox(
        (north + margin).coerceAtMost(MapView.getTileSystem().maxLatitude),
        (east + margin).coerceAtMost(180.0),
        (south - margin).coerceAtLeast(MapView.getTileSystem().minLatitude),
        (west - margin).coerceAtLeast(-180.0),
    )
    val border = (48 * resources.displayMetrics.density).toInt()
    val apply = { zoomToBoundingBox(box, animated, border, FIT_MAX_ZOOM, null) }
    if (isLayoutOccurred) apply() else addOnFirstLayoutListener { _, _, _, _, _ -> apply() }
}

private const val MIN_ZOOM = 2.0
private const val MAX_ZOOM_OFFLINE = 17.0
private const val MAX_ZOOM_ONLINE = 19.0
private const val FIT_MAX_ZOOM = 14.0
