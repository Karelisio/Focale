package app.focale.ui.slideshow

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.focale.R
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.version
import app.focale.core.model.MediaItem
import app.focale.ui.viewer.ImmersiveSystemBars
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import kotlinx.coroutines.delay

/**
 * Diaporama plein écran : fondu enchaîné et lent zoom (effet Ken Burns), avance
 * automatique selon l'intervalle des réglages, glisser pour passer d'une photo à
 * l'autre, appui pour afficher les commandes. L'écran reste allumé.
 */
@Composable
fun SlideshowScreen(onClose: () -> Unit, viewModel: SlideshowViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val view = LocalView.current
    val haptics = LocalHapticFeedback.current
    val items = viewModel.pages.collectAsLazyPagingItems()
    val initialIndex by viewModel.initialIndex.collectAsStateWithLifecycle()
    val intervalMs by viewModel.intervalMs.collectAsStateWithLifecycle()
    var index by rememberSaveable { mutableIntStateOf(-1) }
    var playing by rememberSaveable { mutableStateOf(true) }
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    val count = items.itemCount

    ImmersiveSystemBars(visible = controlsVisible)
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(initialIndex, count) {
        val start = initialIndex ?: return@LaunchedEffect
        if (index < 0 && count > 0) index = start.coerceIn(0, count - 1)
    }
    // Avance automatique ; le diaporama boucle à la fin.
    LaunchedEffect(index, playing, intervalMs, count) {
        if (!playing || index < 0 || count == 0) return@LaunchedEffect
        delay(intervalMs)
        index = (index + 1) % count
    }
    // Commandes masquées automatiquement après quelques secondes.
    LaunchedEffect(controlsVisible, playing) {
        if (controlsVisible && playing) {
            delay(CONTROLS_TIMEOUT_MS)
            controlsVisible = false
        }
    }
    // Préchargement de la photo suivante pour un fondu sans attente.
    LaunchedEffect(index, count) {
        if (index < 0 || count < 2) return@LaunchedEffect
        items.peek((index + 1) % count)?.let { next ->
            SingletonImageLoader.get(context).enqueue(slideRequest(context, next))
        }
    }

    val step: (Int) -> Unit = { delta ->
        if (count > 0) index = Math.floorMod(index + delta, count)
    }
    val current = if (index in 0 until count) items[index] else null

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) }
            .pointerInput(count) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        val threshold = SWIPE_THRESHOLD_DP * density
                        if (dragged < -threshold) step(1) else if (dragged > threshold) step(-1)
                    },
                ) { _, amount -> dragged += amount }
            },
    ) {
        AnimatedContent(
            targetState = current,
            contentKey = { it?.id },
            transitionSpec = { fadeIn(tween(CROSSFADE_MS)) togetherWith fadeOut(tween(CROSSFADE_MS)) },
            label = "slide",
            modifier = Modifier.fillMaxSize(),
        ) { item ->
            if (item != null) KenBurnsSlide(item = item, durationMs = intervalMs + CROSSFADE_MS)
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(8.dp),
                ) {
                    IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close), tint = Color.White)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 32.dp),
                ) {
                    IconButton(onClick = { step(-1) }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = stringResource(R.string.slideshow_previous), tint = Color.White)
                    }
                    FilledTonalIconButton(
                        onClick = {
                            haptics.performHapticFeedback(if (playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            playing = !playing
                        },
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(IconButtonDefaults.largeContainerSize()),
                    ) {
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                            modifier = Modifier.size(IconButtonDefaults.largeIconSize),
                        )
                    }
                    IconButton(onClick = { step(1) }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = stringResource(R.string.slideshow_next), tint = Color.White)
                    }
                }
            }
        }
    }
}

/** Photo plein écran animée d'un lent zoom, alterné en direction d'une photo à l'autre. */
@Composable
private fun KenBurnsSlide(item: MediaItem, durationMs: Long) {
    val context = LocalContext.current
    val progress = remember(item.id) { Animatable(0f) }
    LaunchedEffect(item.id) {
        progress.animateTo(1f, tween(durationMillis = durationMs.toInt(), easing = LinearEasing))
    }
    val zoomIn = item.id % 2 == 0L
    AsyncImage(
        model = remember(item.id, item.dateModified) { slideRequest(context, item) },
        contentDescription = item.displayName,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val t = progress.value
                val scale = if (zoomIn) 1f + KEN_BURNS_ZOOM * t else 1f + KEN_BURNS_ZOOM * (1f - t)
                scaleX = scale
                scaleY = scale
                translationX = size.width * KEN_BURNS_PAN * (t - 0.5f) * if (zoomIn) 1f else -1f
            },
    )
}

/**
 * Requête à la taille de l'écran (identique pour le préchargement et l'affichage, donc
 * servie par le cache mémoire), avec la miniature de la grille comme image d'attente.
 */
private fun slideRequest(context: Context, item: MediaItem): ImageRequest =
    ImageRequest.Builder(context)
        .data(item.uri)
        .version(item)
        .size(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        .placeholderMemoryCacheKey(
            MemoryCache.Key(ThumbnailSizes.memoryKey(item.id, item.dateModified, ThumbnailSizes.lastGridBucket)),
        )
        .build()

private const val CROSSFADE_MS = 900
private const val CONTROLS_TIMEOUT_MS = 3_000L
private const val SWIPE_THRESHOLD_DP = 56f
private const val KEN_BURNS_ZOOM = 0.08f
private const val KEN_BURNS_PAN = 0.03f
