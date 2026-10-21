package app.focale.ui.viewer

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
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
import app.focale.ui.theme.ViewerChromeTheme
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * Page vidéo de la visionneuse pour un média de la galerie.
 *
 * @param controlsWidth largeur de la barre d'outils flottante, sur laquelle s'aligne la
 *   progression (non précisée : toute la largeur disponible, dans la limite des barres).
 */
@Composable
fun VideoPage(
    item: MediaItem,
    isCurrent: Boolean,
    autoplay: Boolean,
    startMuted: Boolean,
    controlsVisible: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    controlsWidth: Dp = Dp.Unspecified,
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
        controlsWidth = controlsWidth,
        contentDescription = item.displayName,
    )
}

/**
 * Page vidéo générique. Le lecteur n'est créé que pour la page courante et libéré dès
 * qu'elle ne l'est plus, pour ne jamais garder plusieurs décodeurs actifs. Position, lecture
 * et son sont conservés par média : la vidéo reprend où on l'a laissée en revenant sur la
 * page, et dans le même état après une recréation de l'activité.
 *
 * Un appui affiche ou masque les commandes ([onTap]) ; un double appui sur la moitié droite
 * avance de 10 s, sur la gauche recule d'autant, et les appuis rapprochés qui suivent
 * s'ajoutent ([videoTapGestures]). TalkBack propose les mêmes sauts en actions.
 *
 * @param key identité de la vidéo (un nouveau lecteur est créé si elle change).
 * @param controlsWidth largeur de la barre d'outils flottante (voir [VideoControlsLayout]).
 * @param contentDescription nom de la vidéo, annoncé par TalkBack avec ses actions de saut.
 * @param playerFactory permet une source de données dédiée (vidéos chiffrées du coffre). Par
 *   défaut, aucune donnée de diagnostic n'est transmise à la plateforme.
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
    controlsWidth: Dp = Dp.Unspecified,
    contentDescription: String? = null,
    playerFactory: (Context) -> Player = { ExoPlayer.Builder(it).setUsePlatformDiagnostics(false).build() },
) {
    val context = LocalContext.current
    val memory = rememberSaveable(key, saver = PlaybackMemory.Saver) { PlaybackMemory() }
    val seeking = remember(key) { VideoSeekState() }
    val currentOnTap by rememberUpdatedState(onTap)
    val forwardLabel = pluralStringResource(R.plurals.video_seek_forward, VideoSeekMath.STEP_SECONDS, VideoSeekMath.STEP_SECONDS)
    val backLabel = pluralStringResource(R.plurals.video_seek_back, VideoSeekMath.STEP_SECONDS, VideoSeekMath.STEP_SECONDS)
    Box(
        modifier = modifier
            .fillMaxSize()
            .videoTapGestures(seeking) { currentOnTap() }
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
                // Sauts de TalkBack, comme un double appui : même cumul, même retour visuel.
                if (isCurrent) {
                    customActions = listOf(
                        CustomAccessibilityAction(forwardLabel) { seeking.seek(SeekDirection.FORWARD, SystemClock.uptimeMillis()) },
                        CustomAccessibilityAction(backLabel) { seeking.seek(SeekDirection.BACKWARD, SystemClock.uptimeMillis()) },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!isCurrent) {
            poster()
            ViewerChromeTheme { PlayGlyph() }
            return@Box
        }
        val player = remember(key) {
            playerFactory(context).apply {
                setMediaItem(mediaItem())
                repeatMode = Player.REPEAT_MODE_ONE
                memory.restoreInto(this, autoplay, startMuted)
                prepare()
            }
        }
        DisposableEffect(player) {
            memory.attach(player)
            seeking.attach(player)
            onDispose {
                seeking.detach()
                memory.detach()
                player.release()
            }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { memory.pauseForBackground() }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { memory.backInForeground() }

        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            keepContentOnReset = true,
            shutter = poster,
        )
        // Cumul des sauts, sous les commandes ; effacé peu après le dernier appui de la série.
        val feedback = seeking.feedback
        LaunchedEffect(feedback) {
            if (feedback != null) {
                delay(VideoSeekMath.SERIES_TIMEOUT_MS)
                seeking.hide()
            }
        }
        ViewerChromeTheme { VideoSeekFeedback(feedback, controlsVisible) }
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            ViewerChromeTheme { VideoControls(player, controlsWidth) }
        }
    }
}

/**
 * Position, lecture et son d'une vidéo, conservés par média ([rememberSaveable]). Le lecteur
 * vivant est relevé au moment même de la sauvegarde ; une lecture interrompue par la mise en
 * arrière-plan compte comme en cours, pour reprendre telle quelle après une recréation.
 */
private class PlaybackMemory(
    private var positionMs: Long = 0L,
    private var playing: Boolean? = null,
    private var muted: Boolean? = null,
) {
    private var player: Player? = null
    private var playingBeforeBackground = false

    /** Applique l'état retenu à un lecteur neuf ; à défaut, les réglages de la visionneuse. */
    fun restoreInto(player: Player, autoplay: Boolean, startMuted: Boolean) {
        player.volume = if (muted ?: startMuted) 0f else 1f
        player.playWhenReady = playing ?: autoplay
        if (positionMs > 0) player.seekTo(positionMs)
    }

    fun attach(player: Player) {
        this.player = player
    }

    /** Page quittée : position et son sont gardés, la lecture suivra le réglage au retour. */
    fun detach() {
        capture()
        playing = null
        player = null
        playingBeforeBackground = false
    }

    fun pauseForBackground() {
        val current = player ?: return
        playingBeforeBackground = current.playWhenReady
        current.pause()
    }

    fun backInForeground() {
        playingBeforeBackground = false
    }

    private fun capture() {
        val current = player ?: return
        positionMs = current.currentPosition
        muted = current.volume == 0f
        playing = current.playWhenReady || playingBeforeBackground
    }

    companion object {
        val Saver: Saver<PlaybackMemory, Any> = listSaver(
            save = { memory ->
                memory.capture()
                listOf(memory.positionMs, memory.playing.toCode(), memory.muted.toCode())
            },
            restore = { values ->
                PlaybackMemory(values[0] as Long, (values[1] as Int).toFlag(), (values[2] as Int).toFlag())
            },
        )

        private fun Boolean?.toCode(): Int = when (this) {
            null -> -1
            false -> 0
            true -> 1
        }

        private fun Int.toFlag(): Boolean? = if (this < 0) null else this == 1
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

/** Repère « vidéo » des pages voisines, à l'image du bouton de lecture de la page courante. */
@Composable
private fun PlayGlyph() {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.secondaryContainer.copy(alpha = BUTTON_ALPHA),
        contentColor = colors.onSecondaryContainer,
        modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(IconButtonDefaults.mediumIconSize))
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoControls(player: Player, width: Dp) {
    val playPause = rememberPlayPauseButtonState(player)
    val mute = rememberMuteButtonState(player)
    val progress = rememberProgressStateWithTickInterval(player, tickIntervalMs = 250)
    VideoControlsLayout(
        playing = !playPause.showPlay,
        positionMs = progress.currentPositionMs,
        durationMs = progress.durationMs.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
        muted = mute.showMuted,
        onPlayPause = playPause::onClick,
        onSeek = { player.seekTo(it) },
        onToggleMute = mute::onClick,
        barWidth = width,
        playEnabled = playPause.isEnabled,
        muteEnabled = mute.isEnabled,
    )
}

/**
 * Commandes d'une vidéo, aux couleurs Material You de l'appareil (à placer dans
 * [ViewerChromeTheme]) : lecture et pause au centre de la vidéo, jamais sur la progression ;
 * juste au-dessus de la barre d'outils flottante, de la même largeur ([barWidth]) et centrée
 * comme elle, une progression compacte : temps écoulé et durée aux extrémités du curseur, puis
 * le curseur et le son. Pas de bandeau : un léger dégradé du thème assure la lisibilité.
 */
@Composable
internal fun VideoControlsLayout(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    muted: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
    barWidth: Dp = Dp.Unspecified,
    playEnabled: Boolean = true,
    muteEnabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val rowBottom = ViewerChromeMetrics.ToolbarHeight + ViewerChromeMetrics.ControlsGap
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(navigationBottom + rowBottom + ViewerChromeMetrics.ProgressHeight + GradientExtent)
                    .background(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0f), colors.scrim.copy(alpha = 0.5f)))),
            )

            // Centré sur la vidéo, remonté si l'écran est trop bas pour laisser la progression libre.
            val buttonSize = IconButtonDefaults.mediumContainerSize()
            val lowest = maxHeight - navigationBottom - rowBottom - ViewerChromeMetrics.ProgressHeight - ButtonGap - buttonSize.height / 2
            val center = minOf(maxHeight / 2, lowest)
            FilledTonalIconButton(
                onClick = onPlayPause,
                enabled = playEnabled,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = colors.secondaryContainer.copy(alpha = BUTTON_ALPHA),
                    contentColor = colors.onSecondaryContainer,
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = center - buttonSize.height / 2)
                    .size(buttonSize),
            ) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                    modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
                )
            }

            VideoProgress(
                positionMs = positionMs,
                durationMs = durationMs,
                muted = muted,
                muteEnabled = muteEnabled,
                onSeek = onSeek,
                onToggleMute = onToggleMute,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = rowBottom)
                    .padding(horizontal = ViewerChromeMetrics.ScreenOffset)
                    .widthIn(max = ViewerChromeMetrics.BarMaxWidth)
                    .then(if (barWidth.isSpecified) Modifier.width(barWidth) else Modifier.fillMaxWidth())
                    .height(ViewerChromeMetrics.ProgressHeight),
            )
        }
    }
}

/**
 * Progression compacte : temps écoulé et durée au-dessus des extrémités du curseur (piste et
 * poignée fines, couleurs du thème), le bouton son au bout du curseur.
 */
@Composable
private fun VideoProgress(
    positionMs: Long,
    durationMs: Long,
    muted: Boolean,
    muteEnabled: Boolean,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    val position = seekFraction?.let { (it * durationMs).toLong() } ?: positionMs
    val enabled = durationMs > 0
    // Chiffres à chasse fixe : le temps qui défile ne fait pas bouger les libellés.
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    // Contenu aligné sur celui de la barre d'outils, dont il reprend la marge intérieure.
    Column(modifier.padding(horizontal = ContentInset)) {
        Row(
            Modifier
                .fillMaxWidth()
                // Les temps se posent sur la zone tactile du curseur, au plus près de sa piste.
                .offset(y = TimeOverlap)
                .padding(start = TimeInset, end = TimeInset + MuteButtonWidth),
        ) {
            Text(
                text = Formatters.duration(position),
                style = timeStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = Formatters.duration(durationMs),
                style = timeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            VideoSlider(
                position = position,
                durationMs = durationMs,
                enabled = enabled,
                onScrub = { seekFraction = it },
                onScrubFinished = {
                    seekFraction?.let { onSeek((it * durationMs).toLong()) }
                    seekFraction = null
                },
                scrubbing = seekFraction != null,
                modifier = Modifier.weight(1f),
            )
            IconToggleButton(
                checked = muted,
                onCheckedChange = { onToggleMute() },
                enabled = muteEnabled,
                shapes = IconButtonDefaults.toggleableShapes(),
            ) {
                Icon(
                    imageVector = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = stringResource(if (muted) R.string.action_unmute else R.string.action_mute),
                )
            }
        }
    }
}

/** Curseur Material 3 à piste fine ; suit la lecture sauf pendant un glissement. */
@Composable
private fun VideoSlider(
    position: Long,
    durationMs: Long,
    enabled: Boolean,
    scrubbing: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderState = rememberSliderState()
    if (!scrubbing) {
        val fraction = if (enabled) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        if (sliderState.value != fraction) sliderState.value = fraction
    }
    val interactionSource = remember { MutableInteractionSource() }
    Slider(
        state = sliderState,
        onValueChange = {
            sliderState.value = it
            onScrub(it)
        },
        onValueChangeFinished = onScrubFinished,
        enabled = enabled,
        interactionSource = interactionSource,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interactionSource,
                enabled = enabled,
                thumbSize = ThumbSize,
            )
        },
        track = {
            SliderDefaults.Track(
                sliderState = it,
                modifier = Modifier.height(TrackHeight),
                enabled = enabled,
                drawStopIndicator = null,
                thumbTrackGapSize = 4.dp,
            )
        },
        modifier = modifier,
    )
}

/** Bouton son au bout du curseur : cible tactile de 48 dp. */
private val MuteButtonWidth = 48.dp

/** Retrait des temps, pour tomber à l'aplomb des extrémités de la piste. */
private val TimeInset = 2.dp
private val TimeOverlap = 10.dp
private val ContentInset = 8.dp
private val TrackHeight = 6.dp
private val ThumbSize = DpSize(4.dp, 22.dp)
private val ButtonGap = 16.dp
private val GradientExtent = 72.dp
private const val BUTTON_ALPHA = 0.85f
