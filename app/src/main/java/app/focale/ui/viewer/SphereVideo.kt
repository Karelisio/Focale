package app.focale.ui.viewer

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import app.focale.R
import app.focale.panorama.MotionSensor
import app.focale.panorama.Panorama
import app.focale.panorama.SphereCamera
import app.focale.panorama.VideoSphereRenderer
import app.focale.panorama.VideoTarget
import app.focale.panorama.sphereMesh
import app.focale.ui.theme.ViewerChromeTheme

/**
 * Vue 360° d'une page vidéo ([VideoPlayerPage]) ouverte : cadre où le lecteur dessine ([frame]) et
 * habillage par-dessus ses commandes ([overlay]), à passer à la page ; voir [rememberSphereVideo].
 */
internal class SphereVideoSlots(
    val frame: @Composable (Player) -> Unit,
    val overlay: @Composable BoxScope.() -> Unit,
)

/**
 * Vue 360° de la vidéo [key] d'une visionneuse (galerie, autre application, coffre), tant que
 * [sphere] n'est pas null : « Bouger le téléphone » et cap de la vue gardés à la recréation de
 * l'activité, retour ([onClose]) vers la vidéo à plat ; [secure] : sphère exclue des captures
 * (coffre). Null : vidéo à plat, dans le cadre habituel de la page.
 */
@Composable
internal fun rememberSphereVideo(key: Any, sphere: Panorama?, controlsVisible: Boolean, onClose: () -> Unit, secure: Boolean = false): SphereVideoSlots? {
    var motion by rememberSaveable(key) { mutableStateOf(false) }
    var resets by remember(key) { mutableIntStateOf(0) }
    if (sphere == null) return null
    val camera = rememberSaveable(sphere, saver = sphereCameraSaver(sphere)) { sphere.camera() }
    BackHandler(onBack = onClose)
    return SphereVideoSlots(
        frame = { player -> SphereVideoFrame(player, sphere, camera, motion, resets, secure = secure) },
        overlay = {
            SphereVideoChrome(
                visible = controlsVisible,
                motion = motion,
                onMotionChange = { motion = it },
                onReset = { resets++ },
                onClose = onClose,
            )
        },
    )
}

/**
 * Vue 360° d'une vidéo, dans sa page ([VideoPage]) : le lecteur de la page dessine dans la
 * sphère, le cube ou le maillage du fichier ([VideoSphereRenderer]), horizon redressé, au lieu de
 * son cadre habituel, sans rien perdre de sa lecture (position, vitesse, son) ; ses commandes
 * restent celles de toutes les vidéos. Glisser pour regarder autour, pincer pour le champ ;
 * « Bouger le téléphone » suit le capteur de rotation ([motion]), comme pour une photo 360° ;
 * chaque nouvelle valeur de [resets] (« Vue initiale ») ramène la vue au cap du fichier.
 */
@Composable
internal fun SphereVideoFrame(
    player: Player,
    panorama: Panorama,
    camera: SphereCamera,
    motion: Boolean,
    resets: Int,
    modifier: Modifier = Modifier,
    secure: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val (sensorManager, sensor) = rememberMotionSensor()
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    var target by remember { mutableStateOf<VideoTarget?>(null) }
    // Première image arrivée : l'indicateur de chargement s'efface.
    var showing by remember { mutableStateOf(false) }
    val renderer = remember(camera, panorama) {
        val main = ContextCompat.getMainExecutor(context)
        VideoSphereRenderer(
            camera = camera,
            mesh = panorama.sphereMesh(),
            pose = panorama.pose,
            stereo = panorama.stereo,
            onTarget = { created -> main.execute { target = created } },
            onFrame = {
                surface?.requestRender()
                if (!showing) showing = true
            },
        )
    }

    // Surface d'un nouveau contexte : le lecteur y est rebranché, l'ancienne libérée avec lui.
    val current = target
    DisposableEffect(player, current) {
        if (current == null) return@DisposableEffect onDispose {}
        player.setVideoSurface(current.surface)
        // En pause, rien ne serait dessiné dans la nouvelle surface : l'image en cours est redemandée.
        if (!player.isPlaying) player.seekTo(player.currentPosition)
        onDispose {
            player.clearVideoSurface(current.surface)
            current.release()
        }
    }
    LaunchedEffect(motion) {
        camera.setMotion(motion)
        surface?.requestRender()
    }
    LaunchedEffect(resets) {
        if (resets == 0) return@LaunchedEffect
        camera.reset()
        surface?.requestRender()
    }
    if (motion && sensorManager != null && sensor != null) {
        LaunchedEffect(sensor) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                MotionSensor.rotations(sensorManager, sensor).collect { rotation ->
                    camera.onDeviceRotation(rotation, view.display?.rotation ?: 0)
                    surface?.requestRender()
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { surface?.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { surface?.onResume() }

    val description = stringResource(R.string.panorama_video_description)
    Box(modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext -> sphereSurface(viewContext, renderer, secure).also { surface = it } },
            onRelease = { released ->
                // Le contexte disparaît avec la vue ; texture et programme sont libérés avant si possible.
                released.queueEvent(renderer::release)
                surface = null
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Appuis (commandes, sauts de 10 s) laissés à la page ; ici, seulement glisser et pincer.
        Box(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(camera) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        camera.drag(pan.x, pan.y)
                        camera.zoom(zoom)
                        surface?.requestRender()
                    }
                },
        )
        if (!showing) ViewerChromeTheme { LoadingIndicator(Modifier.align(Alignment.Center)) }
    }
}

/**
 * Habillage d'une vidéo 360° par-dessus ses commandes : barre supérieure (retour à la vidéo à
 * plat, titre) et barre flottante (« Bouger le téléphone », « Vue initiale ») à la place de la
 * barre d'outils de la visionneuse, au-dessus de laquelle s'aligne la progression.
 */
@Composable
internal fun BoxScope.SphereVideoChrome(
    visible: Boolean,
    motion: Boolean,
    onMotionChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) = ViewerChromeTheme {
    val (_, sensor) = rememberMotionSensor()
    PanoramaTopBar(stringResource(R.string.panorama_video_title), visible, onClose, Modifier.align(Alignment.TopCenter))
    PanoramaToolbar(visible, sensor != null, motion, onMotionChange, onReset, Modifier.align(Alignment.BottomCenter))
}
