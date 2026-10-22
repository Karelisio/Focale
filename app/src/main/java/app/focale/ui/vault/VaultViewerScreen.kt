package app.focale.ui.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestorePage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.core.util.Formatters
import app.focale.ui.theme.PhotoTheme
import app.focale.ui.viewer.ImmersiveSystemBars
import app.focale.ui.viewer.VideoPlayerPage
import app.focale.ui.viewer.rememberVideoSpeed
import app.focale.vault.VaultImage
import app.focale.vault.VaultItem
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/** Visionneuse du coffre : photos zoomables, GIF animés et vidéos déchiffrés à la volée. */
@Composable
fun VaultViewerScreen(onBack: () -> Unit, viewModel: VaultViewerViewModel = hiltViewModel()) {
    SecureWindow()
    val unlocked by viewModel.unlocked.collectAsStateWithLifecycle()
    // Verrouillage (arrière-plan, délai écoulé) : retour à l'écran de code.
    LaunchedEffect(unlocked) { if (!unlocked) onBack() }
    PhotoTheme(seed = null, forceDark = true) {
        VaultViewerContent(viewModel = viewModel, onBack = onBack)
    }
}

@Composable
private fun VaultViewerContent(viewModel: VaultViewerViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val items by viewModel.items.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    // Vitesse des vidéos : la même d'une vidéo à l'autre, 1× à chaque ouverture.
    var videoSpeed by rememberVideoSpeed()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Largeur de la barre d'outils : la progression d'une vidéo s'aligne dessus.
    var toolbarWidth by remember { mutableStateOf(Dp.Unspecified) }
    val density = LocalDensity.current
    ImmersiveSystemBars(visible = chromeVisible)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message -> launch { snackbarHostState.showSnackbar(message.resolve(context)) } }
    }

    val list = items
    // Dernier élément restauré ou supprimé : plus rien à afficher.
    LaunchedEffect(list?.isEmpty()) { if (list?.isEmpty() == true) onBack() }
    if (list.isNullOrEmpty()) return

    val initialPage = remember { list.indexOfFirst { it.id == viewModel.startId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage) { list.size }
    val current = list.getOrNull(pagerState.currentPage)
    LaunchedEffect(current?.id) { current?.let(viewModel::onPageChanged) }

    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text(pluralStringResource(R.plurals.vault_trash_confirm, 1, 1)) },
            text = { Text(stringResource(R.string.vault_trash_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(current)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        HorizontalPager(
            state = pagerState,
            key = { list[it].id },
            beyondViewportPageCount = 1,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = list[page]
            val isCurrent = page == pagerState.settledPage
            if (item.isVideo) {
                VideoPlayerPage(
                    key = item.id,
                    isCurrent = isCurrent,
                    autoplay = settings.autoplayVideos,
                    startMuted = settings.muteVideosByDefault,
                    controlsVisible = chromeVisible,
                    onTap = { chromeVisible = !chromeVisible },
                    playbackSpeed = videoSpeed,
                    onPlaybackSpeedChange = { videoSpeed = it },
                    mediaItem = { viewModel.mediaItemFor(item) },
                    poster = { VaultPoster(item) },
                    controlsWidth = toolbarWidth,
                    contentDescription = item.displayName,
                    playerFactory = viewModel::createPlayer,
                )
            } else {
                VaultPhoto(item = item, isCurrent = isCurrent, onTap = { chromeVisible = !chromeVisible })
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && current != null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (current != null) VaultViewerTopBar(item = current, onBack = onBack)
        }
        AnimatedVisibility(
            visible = chromeVisible && current != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            HorizontalFloatingToolbar(
                expanded = true,
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
                    .onSizeChanged { toolbarWidth = with(density) { it.width.toDp() } },
            ) {
                TextButton(onClick = { current?.let(viewModel::restore) }, enabled = !busy) {
                    Icon(Icons.Outlined.RestorePage, contentDescription = null)
                    Text(stringResource(R.string.action_restore), modifier = Modifier.padding(start = 8.dp))
                }
                IconButton(onClick = { confirmDelete = true }, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 88.dp),
        )
    }
}

/**
 * Photo ou GIF déchiffré, zoomable. L'image est décodée au plus à [MAX_SIDE] pixels :
 * sans fichier en clair, le sous-échantillonnage par tuiles n'est pas possible.
 */
@Composable
private fun VaultPhoto(item: VaultItem, isCurrent: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    val zoomState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f))
    val request = remember(item.fileName) {
        ImageRequest.Builder(context)
            .data(item.image())
            .size(MAX_SIDE)
            .apply { item.thumbFileName?.let { placeholderMemoryCacheKey(MemoryCache.Key(thumbnailKey(it))) } }
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    val intrinsicSize = painter.intrinsicSize
    LaunchedEffect(intrinsicSize) {
        zoomState.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(intrinsicSize))
    }
    LaunchedEffect(isCurrent) {
        if (!isCurrent) zoomState.resetZoom(animationSpec = snap())
    }
    Image(
        painter = painter,
        contentDescription = item.displayName,
        contentScale = ContentScale.Inside,
        modifier = Modifier
            .fillMaxSize()
            .zoomable(zoomState, onClick = { onTap() }),
    )
}

@Composable
private fun VaultPoster(item: VaultItem) {
    val name = item.thumbFileName ?: return
    val context = LocalContext.current
    AsyncImage(
        model = remember(name) {
            ImageRequest.Builder(context)
                .data(VaultImage(name))
                .size(THUMBNAIL_SIDE)
                .memoryCacheKey(thumbnailKey(name))
                .build()
        },
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun VaultViewerTopBar(item: VaultItem, onBack: () -> Unit) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = Formatters.dateTime(context, item.dateTaken),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = Color.White,
                maxLines = 1,
            )
            Text(
                text = "${item.displayName} · ${Formatters.fileSize(context, item.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}

/** Clé mémoire d'une miniature du coffre, partagée par la grille et la visionneuse. */
internal fun thumbnailKey(fileName: String): String = VaultImage.KEY_PREFIX + "thumb:" + fileName

private const val MAX_SIDE = 2560
internal const val THUMBNAIL_SIDE = 384
