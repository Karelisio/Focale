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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.CastConnected
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
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.unit.max
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.state.rememberMuteButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPlaybackSpeedState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import app.focale.R
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.thumbnail
import app.focale.core.model.MediaItem
import app.focale.core.util.Formatters
import app.focale.panorama.Panorama
import app.focale.ui.theme.ViewerChromeTheme
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem as ExoMediaItem

/**
 * Diffusion de la vidéo courante de la visionneuse, vue de sa page : sur un écran externe, le
 * lecteur du téléphone y envoie l'image ([Screen]) ; sur une TV, un lecteur distant reflète la
 * lecture de la TV ([Tv], [Tv.player]). Dans les deux cas, le téléphone montre l'affiche de la
 * vidéo, [label] et les commandes.
 */
@Immutable
sealed interface VideoCast {
    val label: String

    data class Screen(override val label: String) : VideoCast

    /** [onPhone] : vidéos que la TV ne lit pas, lues par le téléphone ([app.focale.cast.CastManager.phoneVideos]). */
    class Tv(val udn: String, override val label: String, val onPhone: Set<Long>, val player: (MediaItem) -> Player) : VideoCast

    companion object {
        /**
         * Diffusion de la vidéo [item] : aucune si la TV l'a rendue au téléphone (refusée, envoi
         * impossible), qui la lit alors lui-même ; la diffusion continue pour les médias suivants.
         */
        fun of(cast: VideoCast?, item: MediaItem): VideoCast? = cast?.takeUnless { it is Tv && item.id in it.onPhone }
    }
}

/**
 * Page vidéo de la visionneuse pour un média de la galerie.
 *
 * @param playbackSpeed vitesse de lecture de la visionneuse ([rememberVideoSpeed]), choisie dans
 *   les commandes ([onPlaybackSpeedChange]).
 * @param controlsWidth largeur de la barre d'outils flottante, sur laquelle s'aligne la
 *   progression (non précisée : toute la largeur disponible, dans la limite des barres).
 * @param cast diffusion en cours (page courante seulement) ; [onPlayer] reçoit le lecteur du
 *   téléphone, que l'écran externe affiche.
 * @param sphere vidéo 360° ouverte en vue immersive (page courante seulement, jamais pendant une
 *   diffusion) : le même lecteur dessine dans la sphère ([SphereVideoFrame]), commandes
 *   comprises ; [onCloseSphere] (retour) revient à la vidéo à plat.
 */
@Composable
fun VideoPage(
    item: MediaItem,
    isCurrent: Boolean,
    autoplay: Boolean,
    startMuted: Boolean,
    controlsVisible: Boolean,
    onTap: () -> Unit,
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    controlsWidth: Dp = Dp.Unspecified,
    cast: VideoCast? = null,
    onPlayer: ((Player?) -> Unit)? = null,
    sphere: Panorama? = null,
    onCloseSphere: () -> Unit = {},
) {
    val shown = VideoCast.of(cast, item)
    val tv = shown as? VideoCast.Tv
    val immersive = sphere?.takeIf { shown == null && isCurrent && it.mediaId == item.id }
    val sphereVideo = rememberSphereVideo(item.id, immersive, controlsVisible, onCloseSphere)
    VideoPlayerPage(
        key = item.id,
        isCurrent = isCurrent,
        autoplay = autoplay,
        startMuted = startMuted,
        controlsVisible = controlsVisible,
        onTap = onTap,
        playbackSpeed = playbackSpeed,
        onPlaybackSpeedChange = onPlaybackSpeedChange,
        mediaItem = { ExoMediaItem.fromUri(item.uri) },
        poster = { VideoPoster(item) },
        modifier = modifier,
        controlsWidth = controlsWidth,
        contentDescription = item.displayName,
        // Vers une TV, un autre lecteur : la position passe de l'un à l'autre (PlaybackMemory).
        playerKey = tv?.udn,
        playerFactory = if (tv != null) { _ -> tv.player(item) } else ::galleryPlayer,
        castLabel = shown?.label,
        // La TV continue quand Focale passe en arrière-plan (service au premier plan).
        pauseInBackground = tv == null,
        onPlayerChanged = onPlayer,
        frame = sphereVideo?.frame,
        overlay = sphereVideo?.overlay ?: {},
    )
}

/** Lecteur des vidéos de la galerie : aucune donnée de diagnostic transmise à la plateforme. */
@OptIn(UnstableApi::class)
internal fun galleryPlayer(context: Context): Player = ExoPlayer.Builder(context).setUsePlatformDiagnostics(false).build()

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
 * La vitesse de lecture est celle de la visionneuse, gardée d'une vidéo à l'autre
 * ([rememberVideoSpeed]) : appliquée au lecteur dès sa création puis à chaque choix dans les
 * commandes, hauteur du son conservée. Les sauts restent de 10 s de vidéo, quelle que soit la
 * vitesse.
 *
 * @param key identité de la vidéo (un nouveau lecteur est créé si elle change).
 * @param playbackSpeed vitesse de lecture de la visionneuse, choisie dans les commandes
 *   ([onPlaybackSpeedChange]).
 * @param controlsWidth largeur de la barre d'outils flottante (voir [VideoControlsLayout]).
 * @param contentDescription nom de la vidéo, annoncé par TalkBack avec ses actions de saut.
 * @param playerFactory permet une source de données dédiée (vidéos chiffrées du coffre). Par
 *   défaut, aucune donnée de diagnostic n'est transmise à la plateforme.
 * @param playerKey change quand le lecteur doit être recréé (diffusion vers une TV).
 * @param castLabel diffusion en cours : l'image n'est pas affichée ici (écran externe, TV), mais
 *   l'affiche et ce libellé.
 * @param pauseInBackground pause à la mise en arrière-plan (pas pour une TV, qui continue).
 * @param onPlayerChanged reçoit le lecteur de la page courante, puis null à sa libération.
 * @param frame dessine le lecteur à la place de son cadre habituel (vidéo 360°), sans le recréer.
 * @param overlay par-dessus les commandes (barres de la vue 360°).
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
    playbackSpeed: Float,
    onPlaybackSpeedChange: (Float) -> Unit,
    mediaItem: () -> ExoMediaItem,
    poster: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    controlsWidth: Dp = Dp.Unspecified,
    contentDescription: String? = null,
    playerFactory: (Context) -> Player = ::galleryPlayer,
    playerKey: Any? = null,
    castLabel: String? = null,
    pauseInBackground: Boolean = true,
    onPlayerChanged: ((Player?) -> Unit)? = null,
    frame: (@Composable (Player) -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
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
        val player = remember(key, playerKey) {
            playerFactory(context).apply {
                setMediaItem(mediaItem())
                repeatMode = Player.REPEAT_MODE_ONE
                memory.restoreInto(this, autoplay, startMuted)
                prepare()
            }
        }
        val currentOnPlayerChanged by rememberUpdatedState(onPlayerChanged)
        DisposableEffect(player) {
            memory.attach(player)
            seeking.attach(player)
            currentOnPlayerChanged?.invoke(player)
            onDispose {
                currentOnPlayerChanged?.invoke(null)
                seeking.detach()
                memory.detach()
                player.release()
            }
        }
        val currentPauseInBackground by rememberUpdatedState(pauseInBackground)
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (currentPauseInBackground) memory.pauseForBackground() }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { memory.backInForeground() }
        // Vitesse de la visionneuse, au lecteur neuf puis à chaque choix : Media3 n'en change que
        // le tempo, la hauteur du son reste celle de la vidéo.
        LaunchedEffect(player, playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }

        if (castLabel == null && frame != null) {
            frame(player)
        } else if (castLabel == null) {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                keepContentOnReset = true,
                shutter = poster,
            )
        } else {
            // Image sur l'écran externe ou la TV : ici, l'affiche et l'appareil qui joue la vidéo.
            poster()
            ViewerChromeTheme { CastLabel(castLabel, Modifier.align(Alignment.TopCenter)) }
        }
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
            ViewerChromeTheme { VideoControls(player, controlsWidth, playbackSpeed, onPlaybackSpeedChange) }
        }
        overlay()
    }
}

/**
 * Position, lecture et son d'une vidéo, conservés par média ([rememberSaveable]). Le lecteur
 * vivant est relevé au moment même de la sauvegarde ; une lecture interrompue par la mise en
 * arrière-plan compte comme en cours, pour reprendre telle quelle après une recréation. Un
 * lecteur qui en remplace un autre (diffusion qui commence, s'arrête, ou TV qui rend la vidéo)
 * reprend là où en était le précédent, encore branché à ce moment.
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
        // Relève : le lecteur remplacé, pas encore libéré, passe la main à sa position du moment.
        capture()
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
        // Sans son à lui (TV), un lecteur ne dit rien du son du téléphone.
        if (current.isCommandAvailable(Player.COMMAND_GET_VOLUME)) muted = current.volume == 0f
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

/** « Lecture sur … » : pastille sous la barre supérieure, pendant une diffusion. */
@Composable
private fun CastLabel(label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.secondaryContainer.copy(alpha = BUTTON_ALPHA),
        contentColor = colors.onSecondaryContainer,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = CastLabelTop),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.CastConnected, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.cast_playing_on, label),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoControls(player: Player, width: Dp, speed: Float, onSpeedChange: (Float) -> Unit) {
    val playPause = rememberPlayPauseButtonState(player)
    val mute = rememberMuteButtonState(player)
    val playbackSpeed = rememberPlaybackSpeedState(player)
    val progress = rememberProgressStateWithTickInterval(player, tickIntervalMs = 250)
    VideoControlsLayout(
        playing = !playPause.showPlay,
        positionMs = progress.currentPositionMs,
        durationMs = progress.durationMs.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
        muted = mute.showMuted,
        speed = speed,
        onPlayPause = playPause::onClick,
        onSeek = { player.seekTo(it) },
        onToggleMute = mute::onClick,
        onSpeedChange = onSpeedChange,
        barWidth = width,
        playEnabled = playPause.isEnabled,
        muteEnabled = mute.isEnabled,
        speedEnabled = playbackSpeed.isEnabled,
    )
}

/**
 * Commandes d'une vidéo, aux couleurs Material You de l'appareil (à placer dans
 * [ViewerChromeTheme]) : lecture et pause au centre de la vidéo, jamais sur la progression ;
 * juste au-dessus de la barre d'outils flottante, de la même largeur ([barWidth], au moins celle
 * de la barre de la galerie) et centrée comme elle, une progression compacte : temps écoulé et
 * durée aux extrémités du curseur, puis le curseur entre la vitesse ([VideoSpeedButton]) et le
 * son. Pas de bandeau : un léger dégradé du thème assure la lisibilité.
 */
@Composable
internal fun VideoControlsLayout(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    muted: Boolean,
    speed: Float,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    barWidth: Dp = Dp.Unspecified,
    playEnabled: Boolean = true,
    muteEnabled: Boolean = true,
    speedEnabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
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
                speed = speed,
                speedEnabled = speedEnabled,
                // Menu des vitesses : de sous la barre d'état et la marge des menus jusqu'au bas du bouton.
                speedMenuHeight = maxHeight - navigationBottom - rowBottom - statusTop - SpeedMenuMargin,
                onSeek = onSeek,
                onToggleMute = onToggleMute,
                onSpeedChange = onSpeedChange,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = rowBottom)
                    .padding(horizontal = ViewerChromeMetrics.ScreenOffset)
                    .widthIn(max = ViewerChromeMetrics.BarMaxWidth)
                    .then(if (barWidth.isSpecified) Modifier.width(max(barWidth, ProgressMinWidth)) else Modifier.fillMaxWidth())
                    .height(ViewerChromeMetrics.ProgressHeight),
            )
        }
    }
}

/**
 * Progression compacte : temps écoulé et durée au-dessus des extrémités du curseur (piste et
 * poignée fines, couleurs du thème), le bouton de vitesse à un bout du curseur, le son à l'autre.
 */
@Composable
private fun VideoProgress(
    positionMs: Long,
    durationMs: Long,
    muted: Boolean,
    muteEnabled: Boolean,
    speed: Float,
    speedEnabled: Boolean,
    speedMenuHeight: Dp,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var seekFraction by remember { mutableStateOf<Float?>(null) }
    val position = seekFraction?.let { (it * durationMs).toLong() } ?: positionMs
    val enabled = durationMs > 0
    // Chiffres à chasse fixe : le temps qui défile ne fait pas bouger les libellés.
    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
    // Contenu aligné sur celui de la barre d'outils, dont il reprend la marge intérieure ; les
    // boutons, à la hauteur du curseur.
    Row(modifier.padding(horizontal = ContentInset), verticalAlignment = Alignment.Bottom) {
        VideoSpeedButton(
            speed = speed,
            onSpeedChange = onSpeedChange,
            menuMaxHeight = speedMenuHeight,
            enabled = speedEnabled,
        )
        Column(Modifier.weight(1f)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    // Les temps se posent sur la zone tactile du curseur, au plus près de sa piste.
                    .offset(y = TimeOverlap)
                    .padding(horizontal = TimeInset),
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
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

/**
 * Largeur minimale de la progression, celle de la barre d'outils de la galerie : sous une barre
 * plus étroite (fichier d'une autre application, coffre), le curseur garde sa place entre les
 * boutons de vitesse et de son.
 */
private val ProgressMinWidth = 256.dp

/** Retrait des temps, pour tomber à l'aplomb des extrémités de la piste. */
private val TimeInset = 2.dp
private val TimeOverlap = 10.dp
private val ContentInset = 8.dp
private val TrackHeight = 6.dp
private val ThumbSize = DpSize(4.dp, 22.dp)
private val ButtonGap = 16.dp

/** Sous la barre supérieure de la visionneuse. */
private val CastLabelTop = 72.dp
private val GradientExtent = 72.dp
private const val BUTTON_ALPHA = 0.85f
