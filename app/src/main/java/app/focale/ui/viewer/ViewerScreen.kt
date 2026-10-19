package app.focale.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import app.focale.R
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.UltraHdr
import app.focale.core.image.thumbnail
import app.focale.core.model.MediaItem
import app.focale.core.util.Formatters
import app.focale.ml.CountryResolver
import app.focale.ml.LabelDictionary
import app.focale.ui.actions.MediaActionsHost
import app.focale.ui.components.isNavTransitionRunning
import app.focale.ui.components.sharedMedia
import app.focale.ui.navigation.EditorRoute
import app.focale.ui.navigation.LocalMainChrome
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.theme.PhotoTheme
import app.focale.ui.theme.ViewerChromeTheme
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/** Visionneuse plein écran, toujours sombre, teintée par la photo affichée. */
@Composable
fun ViewerScreen(onBack: () -> Unit, viewModel: ViewerViewModel = hiltViewModel()) {
    val seed by viewModel.seedColor.collectAsStateWithLifecycle()
    PhotoTheme(seed = seed, forceDark = true) {
        ViewerContent(viewModel = viewModel, onBack = onBack)
    }
}

@Composable
private fun ViewerContent(viewModel: ViewerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val chrome = LocalMainChrome.current
    val rootNav = LocalRootNavController.current
    val items = viewModel.pages.collectAsLazyPagingItems()
    val initialIndex by viewModel.initialIndex.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val place by viewModel.place.collectAsStateWithLifecycle()
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val transitionRunning = isNavTransitionRunning()
    val flickState = rememberFlickToDismissState(dismissThresholdRatio = 0.15f, rotateOnDrag = false)

    ImmersiveSystemBars(visible = chromeVisible)
    HdrWindowMode(current)
    BackHandler(enabled = showInfo) { showInfo = false }

    // Plus rien à afficher (dernier média supprimé, filtre vidé) : on referme.
    val emptyAfterLoad = items.itemCount == 0 &&
        items.loadState.refresh is LoadState.NotLoading &&
        items.loadState.append.endOfPaginationReached
    LaunchedEffect(emptyAfterLoad, initialIndex) {
        if (emptyAfterLoad && initialIndex != null) onBack()
    }

    // Glisser vers le bas : on quitte dès que le geste est validé, la transition
    // partagée ramène alors l'image vers sa miniature.
    if (flickState.gestureState is FlickToDismissState.GestureState.Dismissing) {
        LaunchedEffect(Unit) { onBack() }
    }

    val background = MaterialTheme.colorScheme.surfaceContainerLowest
    Box(
        Modifier
            .fillMaxSize()
            .background(background.copy(alpha = (1f - flickState.offsetFraction * 1.5f).coerceIn(0f, 1f))),
    ) {
        FlickToDismiss(state = flickState, enabled = !zoomed && !showInfo) {
            val start = initialIndex
            val pagerReady = start != null && items.itemCount > start
            if (start != null && pagerReady) {
                ViewerPager(
                    items = items,
                    initialPage = start,
                    hidden = transitionRunning,
                    chromeVisible = chromeVisible,
                    autoplay = settings.autoplayVideos,
                    muted = settings.muteVideosByDefault,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onZoomChanged = { zoomed = it },
                    onPageChanged = { item ->
                        viewModel.onPageChanged(item)
                        chrome.lastViewedMediaId = item.id
                    },
                )
            }
            // Image « héros » : porte la transition partagée avec la grille.
            val hero = current
            if (hero != null && (transitionRunning || !pagerReady)) {
                HeroImage(hero)
            }
        }

        val item = current
        AnimatedVisibility(
            visible = chromeVisible && item != null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (item != null) {
                ViewerTopBar(
                    item = item,
                    onBack = onBack,
                    onInfo = {
                        showInfo = true
                        viewModel.loadDetails()
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = chromeVisible && item != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ViewerToolbar(
                isFavorite = isFavorite,
                onShare = { viewModel.onCurrent { share(it) } },
                onToggleFavorite = {
                    haptics.performHapticFeedback(if (isFavorite) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    viewModel.toggleFavorite()
                },
                onTrash = { viewModel.onCurrent { trash(it) } },
                onEdit = item?.takeIf { !it.isVideo && !it.isGif }?.let { editable ->
                    { rootNav.navigate(EditorRoute(editable.id)) }
                },
                menu = { dismiss ->
                    ViewerMenuItem(Icons.Outlined.LibraryAdd, stringResource(R.string.action_add_to_album)) {
                        dismiss()
                        viewModel.onCurrent { requestAddToAlbum(it) }
                    }
                    ViewerMenuItem(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy_to)) {
                        dismiss()
                        viewModel.onCurrent { requestCopy(it) }
                    }
                    ViewerMenuItem(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.action_move_to)) {
                        dismiss()
                        viewModel.onCurrent { requestMove(it) }
                    }
                    ViewerMenuItem(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.action_rename)) {
                        dismiss()
                        viewModel.onCurrent { ids -> requestRename(ids.first()) }
                    }
                    if (item?.isVideo == false) {
                        ViewerMenuItem(Icons.Outlined.Wallpaper, stringResource(R.string.action_set_as)) {
                            dismiss()
                            item.let { startChooser(context, Intent.ACTION_ATTACH_DATA, it) }
                        }
                    }
                    ViewerMenuItem(Icons.AutoMirrored.Outlined.OpenInNew, stringResource(R.string.action_open_with)) {
                        dismiss()
                        item?.let { startChooser(context, Intent.ACTION_VIEW, it) }
                    }
                    ViewerExtraMenuItems(viewModel, item, dismiss)
                },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 88.dp),
        )
    }
    MediaActionsHost(actions = viewModel.actions, snackbarHostState = snackbarHostState)
    ViewerExtraSheets()

    val infoItem = current
    if (showInfo && infoItem != null) {
        val locale = LocalConfiguration.current.locales[0]
        MediaInfoSheet(
            item = infoItem,
            details = details?.takeIf { it.item.id == infoItem.id },
            placeName = place?.let { (name, country) ->
                if (country.isBlank()) name else "$name, ${CountryResolver.displayName(country, locale)}"
            },
            labels = labels.map { LabelDictionary.displayName(it, locale) },
            people = people,
            onDismiss = {
                showInfo = false
                viewModel.clearDetails()
            },
        )
    }
}

@Composable
private fun ViewerPager(
    items: LazyPagingItems<MediaItem>,
    initialPage: Int,
    hidden: Boolean,
    chromeVisible: Boolean,
    autoplay: Boolean,
    muted: Boolean,
    onToggleChrome: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onPageChanged: (MediaItem) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { items.itemCount }
    LaunchedEffect(pagerState, items) {
        snapshotFlow { items.peek(pagerState.currentPage.coerceIn(0, (items.itemCount - 1).coerceAtLeast(0))) }
            .filterNotNull()
            .distinctUntilChanged()
            .collect(onPageChanged)
    }
    HorizontalPager(
        state = pagerState,
        key = items.itemKey { it.id },
        beyondViewportPageCount = 1,
        pageSpacing = 12.dp,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (hidden) 0f else 1f },
    ) { page ->
        val item = items[page]
        val isCurrent = page == pagerState.settledPage
        when {
            item == null -> Box(Modifier.fillMaxSize())
            item.isVideo -> VideoPage(
                item = item,
                isCurrent = isCurrent,
                autoplay = autoplay,
                startMuted = muted,
                controlsVisible = chromeVisible,
                onTap = onToggleChrome,
            )
            else -> ZoomablePhoto(
                item = item,
                isCurrent = isCurrent,
                onTap = onToggleChrome,
                onZoomChanged = { if (isCurrent) onZoomChanged(it) },
            )
        }
    }
}

/** Photo zoomable (pincement, double appui) avec sous-échantillonnage des grandes images. */
@Composable
private fun ZoomablePhoto(
    item: MediaItem,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val zoomState = rememberZoomableImageState(rememberZoomableState())
    val zoomFraction = zoomState.zoomableState.zoomFraction ?: 0f
    val zoomed = zoomFraction > 0.01f
    LaunchedEffect(zoomed) { onZoomChanged(zoomed) }
    LaunchedEffect(isCurrent) {
        if (!isCurrent) zoomState.zoomableState.resetZoom(animationSpec = androidx.compose.animation.core.snap())
    }
    val request = remember(item.id, item.dateModified) {
        ImageRequest.Builder(context)
            .data(item.uri)
            .placeholderMemoryCacheKey(
                MemoryCache.Key(ThumbnailSizes.memoryKey(item.id, item.dateModified, ThumbnailSizes.lastGridBucket)),
            )
            .build()
    }
    ZoomableAsyncImage(
        model = request,
        contentDescription = item.displayName,
        state = zoomState,
        modifier = Modifier.fillMaxSize(),
        onClick = { onTap() },
    )
}

/** Image calée sur le cadre réel de la photo, support de la transition partagée. */
@Composable
private fun HeroImage(item: MediaItem) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ratio = item.aspectRatio
        val containerRatio = maxWidth / maxHeight
        val width = if (ratio > containerRatio) maxWidth else maxHeight * ratio
        val height = if (ratio > containerRatio) maxWidth / ratio else maxHeight
        val bucket = ThumbnailSizes.lastGridBucket
        AsyncImage(
            model = remember(item.id, bucket) {
                ImageRequest.Builder(context)
                    .data(item.thumbnail())
                    .size(bucket)
                    .memoryCacheKey(ThumbnailSizes.memoryKey(item.id, item.dateModified, bucket))
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(width, height)
                .sharedMedia(item.id),
        )
    }
}

/** Barre supérieure : retour, date et album, détails. */
@Composable
private fun ViewerTopBar(item: MediaItem, onBack: () -> Unit, onInfo: () -> Unit) = ViewerChromeTheme {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0.5f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = colors.onSurface)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = Formatters.dateTime(context, item.dateTaken),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colors.onSurface,
                maxLines = 1,
            )
            Text(
                text = item.bucketName,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = onInfo, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.action_info), tint = colors.onSurface)
        }
    }
}

/**
 * Barre d'actions sobre : conteneur tonal translucide aux couleurs Material You de
 * l'appareil (pas celles de la photo), icônes au trait, favori coloré une fois actif.
 */
@Composable
private fun ViewerToolbar(
    isFavorite: Boolean,
    onShare: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTrash: () -> Unit,
    onEdit: (() -> Unit)?,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
) = ViewerChromeTheme {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = colors.surfaceContainer.copy(alpha = 0.9f),
            toolbarContentColor = colors.onSurfaceVariant,
        ),
        modifier = Modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        IconButton(onClick = onShare, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share))
        }
        IconToggleButton(
            checked = isFavorite,
            onCheckedChange = { onToggleFavorite() },
            shapes = IconButtonDefaults.toggleableShapes(),
            colors = IconButtonDefaults.iconToggleButtonColors(checkedContentColor = colors.primary),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = stringResource(if (isFavorite) R.string.action_unfavorite else R.string.action_favorite),
            )
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_edit))
            }
        }
        IconButton(onClick = onTrash, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.action_trash))
        }
        Box {
            IconButton(onClick = { menuOpen = true }, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                menu { menuOpen = false }
            }
        }
    }
}

@Composable
fun ViewerMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** « Définir comme » / « Ouvrir avec » : délègue à une autre application. */
private fun startChooser(context: Context, action: String, item: MediaItem) {
    val intent = Intent(action).apply {
        setDataAndType(item.uri, item.mimeType.ifBlank { if (item.isVideo) "video/*" else "image/*" })
        putExtra("mimeType", item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Photo Ultra HDR affichée (Android 14+) : la fenêtre passe en mode couleur HDR pour
 * que la gainmap restitue les hautes lumières ; retour au mode standard sinon.
 */
@Composable
private fun HdrWindowMode(item: MediaItem?) {
    if (!UltraHdr.supported) return
    val context = LocalContext.current
    val window = context.findActivity()?.window ?: return
    var hdr by remember { mutableStateOf(false) }
    LaunchedEffect(item?.id, item?.dateModified) {
        hdr = item != null && !item.isVideo && UltraHdr.isCandidate(item.mimeType) &&
            withContext(Dispatchers.IO) { UltraHdr.hasGainmap(context.contentResolver, item.uri) }
    }
    DisposableEffect(window, hdr) {
        window.colorMode = if (hdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        onDispose { window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT }
    }
}

/** Masque les barres système en mode immersif et force des icônes claires. */
@Composable
internal fun ImmersiveSystemBars(visible: Boolean) {
    val view = LocalView.current
    val window = view.context.findActivity()?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    // Icônes claires tant que la visionneuse (toujours sombre) est affichée.
    DisposableEffect(controller) {
        val previousLightStatus = controller.isAppearanceLightStatusBars
        val previousLightNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = previousLightStatus
            controller.isAppearanceLightNavigationBars = previousLightNav
        }
    }
    LaunchedEffect(controller, visible) {
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
