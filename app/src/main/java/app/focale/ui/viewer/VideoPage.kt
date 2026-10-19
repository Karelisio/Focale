package app.focale.ui.viewer

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.state.rememberMuteButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import app.focale.R
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.thumbnail
import app.focale.core.model.MediaItem
import app.focale.core.util.Formatters
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.media3.common.MediaItem as ExoMediaItem

/** Page vidéo de la visionneuse pour un média de la galerie. */
@Composable
fun VideoPage(
    item: MediaItem,
    isCurrent: Boolean,
    autoplay: Boolean,
    startMuted: Boolean,
    controlsVisible: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VideoPlayerPage(
        key = item.id,
        isCurrent = isCurrent,
        autoplay = autoplay,
        startMuted = startMuted,
        controlsVisible = controlsVisible,
        onTap = onTap,
        mediaItem = { ExoMediaItem.fromUri(item.uri) },
        poster = { VideoPoster(item) },
        modifier = modifier,
    )
}

/**
 * Page vidéo générique. Le lecteur n'est créé que pour la page courante et libéré dès
 * qu'elle ne l'est plus, pour ne jamais garder plusieurs décodeurs actifs.
 *
 * @param key identité de la vidéo (un nouveau lecteur est créé si elle change).
 * @param playerFactory permet une source de données dédiée (vidéos chiffrées du coffre).
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerPage(
    key: Any,
    isCurrent: Boolean,
    autoplay: Boolean,
    startMuted: Boolean,
    controlsVisible: Boolean,
    onTap: () -> Unit,
    mediaItem: () -> ExoMediaItem,
    poster: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    playerFactory: (Context) -> ExoPlayer = { ExoPlayer.Builder(it).build() },
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        if (!isCurrent) {
            poster()
            PlayGlyph()
            return@Box
        }
        val player = remember(key) {
            playerFactory(context).apply {
                setMediaItem(mediaItem())
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (startMuted) 0f else 1f
                playWhenReady = autoplay
                prepare()
            }
        }
        DisposableEffect(player) { onDispose { player.release() } }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }

        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            keepContentOnReset = true,
            shutter = poster,
        )
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            VideoControls(player)
        }
    }
}

@Composable
private fun VideoPoster(item: MediaItem) {
    val context = LocalContext.current
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
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun PlayGlyph() {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Black.copy(alpha = 0.45f),
        contentColor = Color.White,
    ) {
        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.padding(16.dp).size(36.dp))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoControls(player: Player) {
    val playPause = rememberPlayPauseButtonState(player)
    val mute = rememberMuteButtonState(player)
    val progress = rememberProgressStateWithTickInterval(player, tickIntervalMs = 250)
    var seekFraction by remember { mutableStateOf<Float?>(null) }

    Box(Modifier.fillMaxSize()) {
        FilledTonalIconButton(
            onClick = playPause::onClick,
            enabled = playPause.isEnabled,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .align(Alignment.Center)
                .size(IconButtonDefaults.largeContainerSize()),
        ) {
            Icon(
                imageVector = if (playPause.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = stringResource(if (playPause.showPlay) R.string.action_play else R.string.action_pause),
                modifier = Modifier.size(IconButtonDefaults.largeIconSize),
            )
        }

        val duration = progress.durationMs.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val position = seekFraction?.let { (it * duration).toLong() } ?: progress.currentPositionMs
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f), MaterialTheme.shapes.extraLarge)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = Formatters.duration(position),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
                val sliderState = rememberSliderState()
                if (seekFraction == null) {
                    sliderState.value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                }
                Slider(
                    state = sliderState,
                    onValueChange = {
                        sliderState.value = it
                        seekFraction = it
                    },
                    onValueChangeFinished = {
                        seekFraction?.let { player.seekTo((it * duration).toLong()) }
                        seekFraction = null
                    },
                    enabled = duration > 0,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = Formatters.duration(duration),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
                IconButton(onClick = mute::onClick, enabled = mute.isEnabled, shapes = IconButtonDefaults.shapes()) {
                    Icon(
                        imageVector = if (mute.showMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = stringResource(if (mute.showMuted) R.string.action_unmute else R.string.action_mute),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
