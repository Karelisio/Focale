package app.focale.ui.viewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.focale.MainActivity
import app.focale.R
import app.focale.core.settings.AppSettings
import app.focale.core.util.Formatters
import app.focale.data.ExternalMedia
import app.focale.panorama.Panorama
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.openFromOtherApp
import app.focale.ui.theme.PhotoTheme
import app.focale.ui.theme.ViewerChromeTheme
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * Photo ou vidéo ouverte depuis une autre application. Un média de l'index rejoint la visionneuse de
 * la galerie, parmi les médias de son dossier ; un autre fichier (messagerie, téléchargements…)
 * s'affiche seul : zoom ou lecture, vue 360° ou panoramique, partage et « Ouvrir avec », sans les
 * actions de la galerie.
 */
@Composable
fun ExternalMediaScreen(onBack: () -> Unit, viewModel: ExternalMediaViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val panorama by viewModel.panorama.collectAsStateWithLifecycle()
    val rootNav = LocalRootNavController.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.shares.collect { runCatching { context.startActivity(it) } }
    }
    PhotoTheme(seed = null, forceDark = true) {
        when (val current = state) {
            is ExternalMediaState.Standalone -> StandaloneViewer(
                media = current.media,
                settings = settings,
                panorama = panorama,
                onBack = onBack,
                onShare = viewModel::share,
                onOpenWith = { openWith(context, current.media) },
            )
            else -> {
                // Média de l'index (trouvé le plus souvent aussitôt) : la visionneuse remplace cet écran.
                if (current is ExternalMediaState.Indexed) {
                    LaunchedEffect(current.item.id) { rootNav.openFromOtherApp(current.item, viewModel.closeOnBack) }
                }
                ResolvingPreview(viewModel.uri)
            }
        }
    }
}

/** Fond de la visionneuse, puis aperçu de [uri] si la recherche dans l'index se prolonge. */
@Composable
private fun ResolvingPreview(uri: Uri) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PREVIEW_DELAY_MS)
        shown = true
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        if (shown) ExternalPreview(uri)
    }
}

/**
 * Visionneuse d'un fichier hors index : glisser vers le bas pour fermer, habillage au toucher.
 * Photo 360° ou panorama ([panorama]) : bouton au-dessus de la barre d'outils, puis vue plein écran
 * par-dessus la photo ; vidéo 360° : au-dessus de ses commandes, puis vue immersive où le même
 * lecteur dessine, comme dans la visionneuse de la galerie. [playerFactory] : lecteur de la vidéo.
 */
@Composable
internal fun StandaloneViewer(
    media: ExternalMedia,
    settings: AppSettings,
    panorama: Panorama?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
    playerFactory: (Context) -> Player = ::galleryPlayer,
) {
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    // Vitesse de la vidéo, gardée après une rotation, 1× à chaque ouverture.
    var videoSpeed by rememberVideoSpeed()
    var zoomed by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    // Vue 360° ou panoramique ouverte, gardée à la recréation de l'activité.
    var panoramaOpen by rememberSaveable { mutableStateOf(false) }
    val immersive = panorama?.takeIf { panoramaOpen }
    // Largeur de la barre d'outils : la progression d'une vidéo s'aligne dessus.
    var toolbarWidth by remember { mutableStateOf(Dp.Unspecified) }
    val density = LocalDensity.current
    val flickState = rememberFlickToDismissState(dismissThresholdRatio = 0.15f, rotateOnDrag = false)
    val toggleChrome = { chromeVisible = !chromeVisible }
    val closePanorama = { panoramaOpen = false }

    ImmersiveSystemBars(visible = chromeVisible)
    if (flickState.gestureState is FlickToDismissState.GestureState.Dismissing) {
        LaunchedEffect(Unit) { onBack() }
    }

    val background = MaterialTheme.colorScheme.surfaceContainerLowest
    Box(
        Modifier
            .fillMaxSize()
            .background(background.copy(alpha = (1f - flickState.offsetFraction * 1.5f).coerceIn(0f, 1f))),
    ) {
        // Sous une vue panoramique ouverte, la photo reste en place mais sort de TalkBack.
        FlickToDismiss(
            state = flickState,
            enabled = !zoomed && immersive == null,
            modifier = if (immersive != null && !media.isVideo) Modifier.clearAndSetSemantics {} else Modifier,
        ) {
            if (media.isVideo) {
                val sphereVideo = rememberSphereVideo(media.uri, immersive, chromeVisible, closePanorama)
                VideoPlayerPage(
                    key = media.uri,
                    isCurrent = true,
                    autoplay = settings.autoplayVideos,
                    startMuted = settings.muteVideosByDefault,
                    controlsVisible = chromeVisible,
                    onTap = toggleChrome,
                    playbackSpeed = videoSpeed,
                    onPlaybackSpeedChange = { videoSpeed = it },
                    mediaItem = { ExoMediaItem.fromUri(media.uri) },
                    poster = { ExternalPreview(media.uri) },
                    controlsWidth = toolbarWidth,
                    contentDescription = media.displayName,
                    playerFactory = playerFactory,
                    frame = sphereVideo?.frame,
                    overlay = sphereVideo?.overlay ?: {},
                )
            } else {
                ExternalPhoto(media = media, onTap = toggleChrome, onZoomChanged = { zoomed = it }, onError = { failed = true })
            }
        }
        if (failed) {
            Text(
                text = stringResource(R.string.external_unreadable),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
        }
        AnimatedVisibility(
            visible = chromeVisible && immersive == null,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ExternalViewerTopBar(media = media, onBack = onBack)
        }
        // Un chemin `file://` ne se transmet pas : ni partage, ni « Ouvrir avec ».
        if (media.canHandOver) {
            AnimatedVisibility(
                visible = chromeVisible && immersive == null,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                ExternalViewerToolbar(
                    onShare = onShare,
                    onOpenWith = onOpenWith,
                    modifier = Modifier.onSizeChanged { toolbarWidth = with(density) { it.width.toDp() } },
                )
            }
        }
        AnimatedVisibility(
            visible = chromeVisible && panorama != null && immersive == null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            if (panorama != null) {
                PanoramaButton(
                    kind = panorama.kind,
                    onClick = { panoramaOpen = true },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = ViewerChromeMetrics.panoramaButtonBottom(video = media.isVideo)),
                )
            }
        }
        if (immersive != null && !media.isVideo) {
            PanoramaOverlay(
                photo = remember(media.uri) { UriPanoramaPhoto(media.uri, media.displayName, previewKey(media.uri)) },
                panorama = immersive,
                chromeVisible = chromeVisible,
                onToggleChrome = toggleChrome,
                onClose = closePanorama,
            )
        }
    }
}

/** Photo zoomable (pincement, double appui), sous-échantillonnée si elle est grande. */
@Composable
private fun ExternalPhoto(media: ExternalMedia, onTap: () -> Unit, onZoomChanged: (Boolean) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val zoomState = rememberZoomableImageState(rememberZoomableState())
    val zoomed = (zoomState.zoomableState.zoomFraction ?: 0f) > 0.01f
    LaunchedEffect(zoomed) { onZoomChanged(zoomed) }
    val currentOnError by rememberUpdatedState(onError)
    val request = remember(media.uri) {
        ImageRequest.Builder(context)
            .data(media.uri)
            .placeholderMemoryCacheKey(previewKey(media.uri))
            .listener(onError = { _, _ -> currentOnError() })
            .build()
    }
    ZoomableAsyncImage(
        model = request,
        contentDescription = media.displayName,
        state = zoomState,
        modifier = Modifier.fillMaxSize(),
        onClick = { onTap() },
    )
}

/** Aperçu de [uri] ajusté à l'écran (image d'une vidéo comprise), en attendant la photo zoomable. */
@Composable
private fun ExternalPreview(uri: Uri) {
    val context = LocalContext.current
    AsyncImage(
        model = remember(uri) { ImageRequest.Builder(context).data(uri).memoryCacheKey(previewKey(uri)).build() },
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Clé mémoire de l'aperçu, reprise comme attente par l'image zoomable. */
private fun previewKey(uri: Uri): String = "external:$uri"

/** Barre supérieure : retour, nom du fichier et taille. */
@Composable
internal fun ExternalViewerTopBar(media: ExternalMedia, onBack: () -> Unit) = ViewerChromeTheme {
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
        Column(
            Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 16.dp),
        ) {
            Text(
                text = media.displayName,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (media.size > 0) {
                Text(
                    text = Formatters.fileSize(context, media.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Barre d'outils de la visionneuse simple, à l'image de [ViewerToolbar] : partage et « Ouvrir avec ». */
@Composable
internal fun ExternalViewerToolbar(onShare: () -> Unit, onOpenWith: () -> Unit, modifier: Modifier = Modifier) = ViewerChromeTheme {
    val colors = MaterialTheme.colorScheme
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(
            toolbarContainerColor = colors.surfaceContainer.copy(alpha = 0.9f),
            toolbarContentColor = colors.onSurfaceVariant,
        ),
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = ViewerChromeMetrics.ScreenOffset)
            .padding(bottom = ViewerChromeMetrics.ScreenOffset)
            .widthIn(max = ViewerChromeMetrics.BarMaxWidth)
            .then(modifier),
    ) {
        IconButton(onClick = onShare, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share))
        }
        IconButton(onClick = onOpenWith, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.action_open_with))
        }
    }
}

/** « Ouvrir avec » : la permission de lecture reçue de l'application d'origine est transmise. */
private fun openWith(context: Context, media: ExternalMedia) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(media.uri, media.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(chooserWithoutFocale(context, view)) }
}

/** Sélecteur d'applications pour [intent], sans Focale : il ouvre lui-même les photos et ne se propose pas. */
internal fun chooserWithoutFocale(context: Context, intent: Intent): Intent =
    Intent.createChooser(intent, null)
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Attente avant l'aperçu : un média de l'index est en général trouvé avant, sans passer par lui. */
private const val PREVIEW_DELAY_MS = 300L
