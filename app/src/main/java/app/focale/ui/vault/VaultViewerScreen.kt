package app.focale.ui.vault

import android.content.Context
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.focale.R
import app.focale.core.settings.AppSettings
import app.focale.core.util.Formatters
import app.focale.panorama.Panorama
import app.focale.ui.theme.PhotoTheme
import app.focale.ui.viewer.ImmersiveSystemBars
import app.focale.ui.viewer.PanoramaButton
import app.focale.ui.viewer.PanoramaOverlay
import app.focale.ui.viewer.PanoramaPhoto
import app.focale.ui.viewer.VideoPlayerPage
import app.focale.ui.viewer.ViewerChromeMetrics
import app.focale.ui.viewer.rememberSphereVideo
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

/**
 * Visionneuse du coffre : photos zoomables, GIF animés et vidéos déchiffrés à la volée, photos et
 * vidéos 360° comprises ; captures d'écran interdites tant qu'elle est affichée.
 */
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
    val panorama by viewModel.panorama.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { message -> launch { snackbarHostState.showSnackbar(message.resolve(context)) } }
    }

    val list = items
    // Dernier élément restauré ou supprimé : plus rien à afficher.
    LaunchedEffect(list?.isEmpty()) { if (list?.isEmpty() == true) onBack() }
    if (list.isNullOrEmpty()) return

    VaultViewerPages(
        items = list,
        startId = viewModel.startId,
        settings = settings,
        busy = busy,
        panorama = panorama,
        snackbarHostState = snackbarHostState,
        onPageChanged = viewModel::onPageChanged,
        onRestore = viewModel::restore,
        onDelete = viewModel::delete,
        mediaItemFor = viewModel::mediaItemFor,
        playerFactory = viewModel::createPlayer,
        panoramaPhoto = viewModel::panoramaPhoto,
        onBack = onBack,
    )
}

/**
 * Pages de la visionneuse du coffre, à partir de [startId] : photos zoomables, GIF animés,
 * vidéos lues par [playerFactory] (source chiffrée) ; habillage, restauration et suppression.
 * Photo 360° ou panorama ([panorama] de la page courante) : bouton au-dessus de la barre
 * d'outils, puis vue plein écran ; vidéo 360° : au-dessus de ses commandes, puis vue immersive où
 * le même lecteur dessine (pager figé). Photo déchiffrée en mémoire ([panoramaPhoto]), surfaces
 * OpenGL exclues des captures, comme la fenêtre du coffre ([SecureWindow]).
 */
@Composable
internal fun VaultViewerPages(
    items: List<VaultItem>,
    startId: Long,
    settings: AppSettings,
    busy: Boolean,
    panorama: Panorama?,
    snackbarHostState: SnackbarHostState,
    onPageChanged: (VaultItem) -> Unit,
    onRestore: (VaultItem) -> Unit,
    onDelete: (VaultItem) -> Unit,
    mediaItemFor: (VaultItem) -> MediaItem,
    playerFactory: (Context) -> Player,
    panoramaPhoto: (VaultItem) -> PanoramaPhoto,
    onBack: () -> Unit,
) {
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    // Vitesse des vidéos : la même d'une vidéo à l'autre, 1× à chaque ouverture.
    var videoSpeed by rememberVideoSpeed()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    // Vue 360° ou panoramique ouverte (identifiant de l'élément), gardée à la recréation de l'activité.
    var panoramaOpen by rememberSaveable { mutableStateOf<Long?>(null) }
    // Largeur de la barre d'outils : la progression d'une vidéo s'aligne dessus.
    var toolbarWidth by remember { mutableStateOf(Dp.Unspecified) }
    val density = LocalDensity.current
    ImmersiveSystemBars(visible = chromeVisible)

    val list = items
    val initialPage = remember { list.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage) { list.size }
    val current = list.getOrNull(pagerState.currentPage)
    LaunchedEffect(current?.id) { current?.let(onPageChanged) }
    val offered = panorama?.takeIf { current != null && it.mediaId == current.id }
    val immersive = offered?.takeIf { it.mediaId == panoramaOpen }
    val photoPanorama = immersive?.takeIf { current?.isVideo == false }
    val closePanorama = { panoramaOpen = null }

    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text(pluralStringResource(R.plurals.vault_trash_confirm, 1, 1)) },
            text = { Text(stringResource(R.string.vault_trash_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(current)
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
            // Vidéo 360° : glisser regarde autour, sans changer de page.
            userScrollEnabled = immersive == null,
            // Sous une vue panoramique ouverte, la photo reste en place mais sort de TalkBack.
            modifier = Modifier
                .fillMaxSize()
                .then(if (photoPanorama != null) Modifier.clearAndSetSemantics {} else Modifier),
        ) { page ->
            val item = list[page]
            val isCurrent = page == pagerState.settledPage
            if (item.isVideo) {
                val sphere = immersive?.takeIf { isCurrent && it.mediaId == item.id }
                val sphereVideo = rememberSphereVideo(item.id, sphere, chromeVisible, closePanorama, secure = true)
                VideoPlayerPage(
                    key = item.id,
                    isCurrent = isCurrent,
                    autoplay = settings.autoplayVideos,
                    startMuted = settings.muteVideosByDefault,
                    controlsVisible = chromeVisible,
                    onTap = { chromeVisible = !chromeVisible },
                    playbackSpeed = videoSpeed,
                    onPlaybackSpeedChange = { videoSpeed = it },
                    mediaItem = { mediaItemFor(item) },
                    poster = { VaultPoster(item) },
                    controlsWidth = toolbarWidth,
                    contentDescription = item.displayName,
                    playerFactory = playerFactory,
                    frame = sphereVideo?.frame,
                    overlay = sphereVideo?.overlay ?: {},
                )
            } else {
                VaultPhoto(item = item, isCurrent = isCurrent, onTap = { chromeVisible = !chromeVisible })
            }
        }

        AnimatedVisibility(
            visible = chromeVisible && current != null && immersive == null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (current != null) VaultViewerTopBar(item = current, onBack = onBack)
        }
        AnimatedVisibility(
            visible = chromeVisible && current != null && immersive == null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VaultViewerToolbar(
                busy = busy,
                onRestore = { current?.let(onRestore) },
                onDelete = { confirmDelete = true },
                modifier = Modifier.onSizeChanged { toolbarWidth = with(density) { it.width.toDp() } },
            )
        }
        // Photo 360° ou panorama : au-dessus de la barre d'outils ; vidéo 360° : de ses commandes.
        AnimatedVisibility(
            visible = chromeVisible && offered != null && immersive == null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (offered != null) {
                PanoramaButton(
                    kind = offered.kind,
                    onClick = { panoramaOpen = offered.mediaId },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = ViewerChromeMetrics.panoramaButtonBottom(video = current?.isVideo == true)),
                )
            }
        }
        if (photoPanorama != null && current != null) {
            PanoramaOverlay(
                photo = remember(current) { panoramaPhoto(current) },
                panorama = photoPanorama,
                chromeVisible = chromeVisible,
                onToggleChrome = { chromeVisible = !chromeVisible },
                onClose = closePanorama,
                secure = true,
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

/** Barre d'outils de la visionneuse du coffre : restaurer, supprimer (après confirmation). */
@Composable
internal fun VaultViewerToolbar(busy: Boolean, onRestore: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        modifier = Modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp)
            .then(modifier),
    ) {
        TextButton(onClick = onRestore, enabled = !busy) {
            Icon(Icons.Outlined.RestorePage, contentDescription = null)
            Text(stringResource(R.string.action_restore), modifier = Modifier.padding(start = 8.dp))
        }
        IconButton(onClick = onDelete, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
        }
    }
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
internal fun VaultViewerTopBar(item: VaultItem, onBack: () -> Unit) {
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
