package app.focale.ui.viewer

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.rounded.MotionPhotosPause
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import app.focale.R
import app.focale.video.MotionPhoto
import app.focale.video.MotionPhotos

/** Lecture de la vidéo d'une photo animée : arrêtée, une fois (puce) ou tant que l'appui dure. */
enum class MotionPlayback { STOPPED, ONCE, HOLD }

/** État de lecture partagé par la puce « Animée » de la barre supérieure et la page courante. */
@Stable
class MotionPhotoState {
    var playback by mutableStateOf(MotionPlayback.STOPPED)
        private set

    val isPlaying: Boolean get() = playback != MotionPlayback.STOPPED

    fun playOnce() {
        playback = MotionPlayback.ONCE
    }

    fun hold() {
        playback = MotionPlayback.HOLD
    }

    fun stop() {
        playback = MotionPlayback.STOPPED
    }

    fun toggle() = if (isPlaying) stop() else playOnce()
}

/**
 * Page photo capable de lire la vidéo d'une photo animée par-dessus l'image : un appui long la
 * lit en boucle tant que le doigt reste posé, la puce « Animée » la lit une fois ; on revient
 * ensuite à la photo. [content] (la photo zoomable) reste toujours à la même place dans
 * l'arbre, pour ne jamais être recréé.
 *
 * @param motionPhoto vidéo intégrée, seulement pour la page courante d'une photo animée.
 */
@Composable
fun MotionPhotoBox(
    motionPhoto: MotionPhoto?,
    state: MotionPhotoState,
    muted: Boolean,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier
            .fillMaxSize()
            .holdToPlay(
                enabled = motionPhoto != null,
                state = state,
                onHold = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.hold()
                },
            ),
    ) {
        content()
        if (motionPhoto != null) MotionVideoLayer(motionPhoto, state, muted)
    }
}

/**
 * Appui long : lecture tant que le doigt reste posé. L'attente ne consomme rien (zoom, double
 * appui, défilement et glisser pour fermer restent à la visionneuse) ; une fois la lecture
 * lancée, tous les évènements du geste sont consommés, dès la première passe, pour que la
 * photo n'y voie ni appui ni déplacement.
 */
private fun Modifier.holdToPlay(enabled: Boolean, state: MotionPhotoState, onHold: () -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(state) {
        var lastUpTime = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Second appui d'un double appui (zoom) : pas de lecture.
            if (down.uptimeMillis - lastUpTime < viewConfiguration.doubleTapTimeoutMillis) return@awaitEachGesture
            val interrupted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var waiting = true
                while (waiting) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    when {
                        change == null || event.changes.size > 1 -> waiting = false
                        !change.pressed -> {
                            lastUpTime = change.uptimeMillis
                            waiting = false
                        }
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> waiting = false
                    }
                }
                true
            }
            if (interrupted == true) return@awaitEachGesture
            onHold()
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            } finally {
                state.stop()
            }
        }
    }
}

/**
 * Vidéo intégrée, préparée dès que la page devient courante pour démarrer sans délai, et
 * affichée en fondu par-dessus la photo pendant la lecture seulement (la photo reçoit sinon
 * tous les appuis). Le fond n'apparaît qu'avec la première image : jamais de flash.
 */
@OptIn(UnstableApi::class)
@Composable
private fun MotionVideoLayer(motionPhoto: MotionPhoto, state: MotionPhotoState, muted: Boolean) {
    val context = LocalContext.current
    val player = remember(motionPhoto) { MotionPhotos.createPlayer(context, motionPhoto).apply { prepare() } }
    val currentState by rememberUpdatedState(state)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Lecture unique terminée : retour à la photo.
                if (playbackState == Player.STATE_ENDED) currentState.stop()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { state.stop() }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }

    val playback = state.playback
    LaunchedEffect(player, playback) {
        if (playback == MotionPlayback.STOPPED) {
            player.pause()
        } else {
            player.repeatMode = if (playback == MotionPlayback.HOLD) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            player.seekTo(0)
            player.play()
        }
    }

    AnimatedVisibility(
        visible = playback != MotionPlayback.STOPPED,
        enter = fadeIn(tween(FADE_IN_MS)),
        exit = fadeOut(tween(FADE_OUT_MS)),
        modifier = Modifier.fillMaxSize(),
    ) {
        val presentation = rememberPresentationState(player, keepContentOnReset = true)
        val background = MaterialTheme.colorScheme.surfaceContainerLowest
        Box(
            Modifier
                .fillMaxSize()
                .background(if (presentation.coverSurface) Color.Transparent else background)
                // Lecture unique : un appui y met fin.
                .pointerInput(state) { detectTapGestures { state.stop() } },
        ) {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = ContentScale.Fit,
                keepContentOnReset = true,
                shutter = {},
            )
        }
    }
}

/** Puce « Animée » de la barre supérieure : lit la vidéo une fois, ou l'arrête. */
@Composable
fun MotionPhotoChip(playing: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    FilterChip(
        selected = playing,
        onClick = {
            haptics.performHapticFeedback(if (playing) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
            onClick()
        },
        label = { Text(stringResource(R.string.motion_photo_chip)) },
        leadingIcon = {
            Icon(
                imageVector = if (playing) Icons.Rounded.MotionPhotosPause else Icons.Outlined.MotionPhotosOn,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
        shape = MaterialTheme.shapes.large,
    )
}

private const val FADE_IN_MS = 160
private const val FADE_OUT_MS = 240
