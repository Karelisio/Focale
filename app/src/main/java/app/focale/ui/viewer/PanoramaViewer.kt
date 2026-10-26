package app.focale.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.PanoramaHorizontal
import androidx.compose.material.icons.outlined._360
import androidx.compose.material.icons.outlined._3dRotation
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.focale.R
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.version
import app.focale.core.model.MediaItem
import app.focale.panorama.HeadingTracker
import app.focale.panorama.MotionSensor
import app.focale.panorama.Panorama
import app.focale.panorama.PanoramaKind
import app.focale.panorama.SphereCamera
import app.focale.panorama.SphereRenderer
import app.focale.panorama.SphereTexture
import app.focale.panorama.UriPhotoStreams
import app.focale.ui.theme.ViewerChromeTheme
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomableImage
import me.saket.telephoto.zoomable.ZoomableImageSource
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.coil
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * Bouton d'une photo panoramique, au-dessus de la barre d'outils de la visionneuse : « 360° »
 * ouvre la vue immersive d'une sphère, « Vue panoramique » le parcours d'un panorama à plat.
 */
@Composable
fun PanoramaButton(kind: PanoramaKind, onClick: () -> Unit, modifier: Modifier = Modifier) = ViewerChromeTheme {
    val sphere = kind == PanoramaKind.SPHERE
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (sphere) Icons.Outlined._360 else Icons.Outlined.PanoramaHorizontal,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(if (sphere) R.string.panorama_sphere_button else R.string.panorama_flat_button))
    }
}

/**
 * Photo d'une vue panoramique, d'où qu'elle vienne : galerie ([GalleryPanoramaPhoto]), autre
 * application ([UriPanoramaPhoto]) ou coffre, déchiffrée en mémoire. [key] l'identifie : l'état de
 * la vue (« Bouger le téléphone ») est gardé par photo.
 */
@Stable
internal interface PanoramaPhoto {
    val key: Any
    val contentDescription: String?

    /** Bloquant : texture d'une photo 360° ([SphereTexture]), null si elle est illisible. */
    fun texture(context: Context, maxTextureSize: Int, maxPixels: Long): Bitmap?

    /** Image d'un panorama à plat, pour telephoto. */
    @Composable
    fun flatImage(): ZoomableImageSource
}

/** Photo de la galerie : même requête que la visionneuse, l'image en mémoire s'affiche aussitôt. */
@Immutable
internal data class GalleryPanoramaPhoto(val item: MediaItem) : PanoramaPhoto {
    override val key: Any get() = item.id
    override val contentDescription: String get() = item.displayName

    override fun texture(context: Context, maxTextureSize: Int, maxPixels: Long): Bitmap? =
        SphereTexture.decode(UriPhotoStreams(context.contentResolver, item.uri), item.orientation, maxTextureSize, maxPixels)

    @Composable
    override fun flatImage(): ZoomableImageSource {
        val context = LocalContext.current
        val request = remember(item.id, item.dateModified) {
            ImageRequest.Builder(context)
                .data(item.uri)
                .version(item)
                .placeholderMemoryCacheKey(MemoryCache.Key(ThumbnailSizes.memoryKey(item.id, item.dateModified, ThumbnailSizes.lastGridBucket)))
                .build()
        }
        return ZoomableImageSource.coil(request)
    }
}

/**
 * Fichier reçu d'une autre application, hors de l'index ([uri]) : orientation lue dans son
 * en-tête ; [placeholderKey], clé mémoire de l'aperçu déjà affiché.
 */
@Immutable
internal data class UriPanoramaPhoto(val uri: Uri, override val contentDescription: String?, val placeholderKey: String?) : PanoramaPhoto {
    override val key: Any get() = uri

    override fun texture(context: Context, maxTextureSize: Int, maxPixels: Long): Bitmap? =
        SphereTexture.decode(UriPhotoStreams(context.contentResolver, uri), orientation = null, maxTextureSize, maxPixels)

    @Composable
    override fun flatImage(): ZoomableImageSource {
        val context = LocalContext.current
        val request = remember(uri) {
            ImageRequest.Builder(context)
                .data(uri)
                .apply { placeholderKey?.let { placeholderMemoryCacheKey(it) } }
                .build()
        }
        return ZoomableImageSource.coil(request)
    }
}

/**
 * Vue plein écran d'une photo panoramique, par-dessus la visionneuse (qui reste dessous, intacte,
 * zoom compris) : sphère OpenGL ou panorama à plat selon [panorama]. Retour et appui sur la
 * photo comme dans la visionneuse, dont elle partage l'affichage des commandes. [secure] : la
 * sphère est exclue des captures et des écrans non sécurisés (coffre), comme sa fenêtre.
 */
@Composable
internal fun PanoramaOverlay(
    photo: PanoramaPhoto,
    panorama: Panorama,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onClose: () -> Unit,
    secure: Boolean = false,
) {
    BackHandler(onBack = onClose)
    when (panorama.kind) {
        PanoramaKind.SPHERE -> SphereViewer(photo, panorama, chromeVisible, onToggleChrome, onClose, secure)
        PanoramaKind.FLAT -> FlatPanoramaViewer(photo, panorama, chromeVisible, onToggleChrome, onClose)
    }
}

/**
 * Surface OpenGL ES 2 d'une vue 360°, rendue à la demande, contexte gardé en pause ; [secure] :
 * contenu exclu des captures et des écrans non sécurisés (avant la création de sa surface).
 */
internal fun sphereSurface(context: Context, renderer: GLSurfaceView.Renderer, secure: Boolean): GLSurfaceView =
    GLSurfaceView(context).apply {
        setSecure(secure)
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

/**
 * Habillage commun des deux vues : barre supérieure (retour, titre) et barre flottante
 * (« Bouger le téléphone », seulement avec un capteur de rotation ; « Vue initiale »), au-dessus
 * de [content]. Fond noir : une sphère partielle ne couvre pas tout. Une vidéo 360° reprend les
 * deux barres ([PanoramaTopBar], [PanoramaToolbar]) par-dessus ses commandes.
 */
@Composable
internal fun PanoramaChrome(
    title: String,
    chromeVisible: Boolean,
    motionAvailable: Boolean,
    motion: Boolean,
    onMotionChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) = ViewerChromeTheme {
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        content()
        PanoramaTopBar(title, chromeVisible, onClose, Modifier.align(Alignment.TopCenter))
        PanoramaToolbar(chromeVisible, motionAvailable, motion, onMotionChange, onReset, Modifier.align(Alignment.BottomCenter))
    }
}

/** Barre supérieure d'une vue panoramique ou 360° : retour et titre (à placer dans [ViewerChromeTheme]). */
@Composable
internal fun PanoramaTopBar(title: String, visible: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0.5f), Color.Transparent)))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = colors.onSurface)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colors.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * Barre flottante d'une vue panoramique ou 360° (à placer dans [ViewerChromeTheme]) : « Bouger le
 * téléphone », seulement avec un capteur de rotation ([motionAvailable]), et « Vue initiale » ; aux
 * mesures de la barre d'outils de la visionneuse ([ViewerChromeMetrics]), sur laquelle s'alignent
 * les commandes d'une vidéo.
 */
@Composable
internal fun PanoramaToolbar(
    visible: Boolean,
    motionAvailable: Boolean,
    motion: Boolean,
    onMotionChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
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
                .widthIn(max = ViewerChromeMetrics.BarMaxWidth),
        ) {
            if (motionAvailable) {
                ToggleButton(
                    checked = motion,
                    onCheckedChange = onMotionChange,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Outlined._3dRotation, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.panorama_motion))
                }
            }
            TextButton(onClick = onReset, shapes = ButtonDefaults.shapes(), contentPadding = ButtonDefaults.TextButtonWithIconContentPadding) {
                Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.panorama_reset))
            }
        }
    }
}

/** Capteur de rotation de l'appareil, ou null s'il n'en a pas (« Bouger le téléphone » absent). */
@Composable
internal fun rememberMotionSensor(): Pair<SensorManager?, android.hardware.Sensor?> {
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(SensorManager::class.java)
        manager to MotionSensor.find(manager)
    }
}

/** Chargement de la texture d'une sphère. */
private enum class SphereLoad { LOADING, READY, FAILED }

/** Contexte OpenGL créé ([generation] : un nouveau à chaque perte du précédent). */
private data class GlContext(val generation: Int, val maxTextureSize: Int)

/**
 * Photo 360° : sphère OpenGL ES ([SphereRenderer]) vue de l'intérieur, horizon redressé d'après
 * la pose GPano. Glisser pour regarder autour, pincer pour le champ ; « Bouger le téléphone » suit
 * le capteur de rotation ; « Vue initiale » revient au cap GPano. La photo est décodée hors du fil
 * principal à la taille que permet le contexte, et libérée (texture comprise, avec le contexte) en
 * quittant la vue.
 */
@Composable
private fun SphereViewer(
    photo: PanoramaPhoto,
    panorama: Panorama,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onClose: () -> Unit,
    secure: Boolean,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val (sensorManager, sensor) = rememberMotionSensor()
    var motion by rememberSaveable(photo.key) { mutableStateOf(false) }
    val camera = rememberSaveable(panorama, saver = sphereCameraSaver(panorama)) { panorama.camera() }
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    var glContext by remember { mutableStateOf<GlContext?>(null) }
    var load by remember { mutableStateOf(SphereLoad.LOADING) }
    val renderer = remember(camera) {
        val main = ContextCompat.getMainExecutor(context)
        SphereRenderer(camera, panorama.area, panorama.pose) { maxTextureSize ->
            main.execute { glContext = GlContext((glContext?.generation ?: 0) + 1, maxTextureSize) }
        }
    }

    LaunchedEffect(glContext) {
        val gl = glContext ?: return@LaunchedEffect
        load = SphereLoad.LOADING
        // Décodage mené à terme même si la vue se ferme entre-temps : le bitmap est alors libéré.
        val bitmap = withContext(Dispatchers.IO + NonCancellable) {
            photo.texture(context, gl.maxTextureSize, SphereTexture.pixelBudget(context))
        }
        val target = surface
        if (bitmap == null) {
            load = SphereLoad.FAILED
        } else if (!isActive || target == null) {
            bitmap.recycle()
        } else {
            target.queueEvent { renderer.setTexture(bitmap) }
            target.requestRender()
            load = SphereLoad.READY
        }
    }
    LaunchedEffect(motion) {
        camera.setMotion(motion)
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

    val description = stringResource(R.string.panorama_sphere_description)
    val toggleChrome by rememberUpdatedState(onToggleChrome)
    PanoramaChrome(
        title = stringResource(R.string.panorama_sphere_title),
        chromeVisible = chromeVisible,
        motionAvailable = sensor != null,
        motion = motion,
        onMotionChange = { motion = it },
        onReset = {
            camera.reset()
            surface?.requestRender()
        },
        onClose = onClose,
    ) {
        AndroidView(
            factory = { viewContext -> sphereSurface(viewContext, renderer, secure).also { surface = it } },
            onRelease = { released ->
                // Le contexte disparaît avec la vue ; la texture est libérée avant si possible.
                released.queueEvent(renderer::release)
                surface = null
            },
            modifier = Modifier.fillMaxSize(),
        )
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
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { toggleChrome() }) },
        )
        when (load) {
            SphereLoad.LOADING -> LoadingIndicator(Modifier.align(Alignment.Center))
            SphereLoad.FAILED -> Text(
                text = stringResource(R.string.panorama_failed),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
            )
            SphereLoad.READY -> Unit
        }
    }
}

internal fun Panorama.camera() = SphereCamera(area, initialYaw, initialPitch, initialHorizontalFov)

/** Cap, inclinaison et champ gardés à la recréation de l'activité (rotation de l'écran…). */
internal fun sphereCameraSaver(panorama: Panorama) = Saver<SphereCamera, DoubleArray>(
    save = { doubleArrayOf(it.yaw, it.pitch, it.fov) },
    restore = { saved -> panorama.camera().apply { restore(saved[0], saved[1], saved[2]) } },
)

/**
 * Panorama à plat : la photo remplit la hauteur de l'écran et se parcourt au doigt, avec
 * l'inertie de la visionneuse (telephoto, sous-échantillonnage des grandes images compris) ;
 * « Bouger le téléphone » la fait défiler quand on tourne sur soi, un degré du téléphone pour
 * un degré du panorama ; « Vue initiale » la recentre.
 */
@Composable
private fun FlatPanoramaViewer(
    photo: PanoramaPhoto,
    panorama: Panorama,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onClose: () -> Unit,
) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val (sensorManager, sensor) = rememberMotionSensor()
    var motion by rememberSaveable(photo.key) { mutableStateOf(false) }
    val state = rememberZoomableImageState(rememberZoomableState())
    if (motion && sensorManager != null && sensor != null) {
        LaunchedEffect(sensor, state) {
            val tracker = HeadingTracker()
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                tracker.reset()
                MotionSensor.rotations(sensorManager, sensor).collect { rotation ->
                    val turned = tracker.update(rotation, view.display?.rotation ?: 0)
                    val shift = panoramaShift(turned, state.zoomableState.transformedContentBounds.width, panorama.area.width)
                    if (shift != 0f) state.zoomableState.panBy(Offset(shift, 0f), snap())
                }
            }
        }
    }
    var viewportWidth by remember { mutableIntStateOf(0) }
    PanoramaChrome(
        title = stringResource(R.string.panorama_flat_title),
        chromeVisible = chromeVisible,
        motionAvailable = sensor != null,
        motion = motion,
        onMotionChange = { motion = it },
        onReset = { scope.launch { state.zoomableState.recenter(viewportWidth.toFloat()) } },
        onClose = onClose,
        modifier = Modifier.onSizeChanged { viewportWidth = it.width },
    ) {
        PanoramaStrip(
            image = photo.flatImage(),
            contentDescription = photo.contentDescription,
            state = state,
            onClick = onToggleChrome,
        )
    }
}

/**
 * « Vue initiale » d'un panorama à plat : zoom de base, puis image centrée dans la vue large de
 * [viewportWidth] pixels (le retour au zoom de base ne ramène pas l'image au centre).
 */
internal suspend fun ZoomableState.recenter(viewportWidth: Float) {
    resetZoom()
    val bounds = transformedContentBounds
    val shift = (viewportWidth - bounds.width) / 2 - bounds.left
    if (abs(shift) > 0.5f) panBy(Offset(shift, 0f))
}

/**
 * Photo à la hauteur de l'écran ([ContentScale.FillHeight]) : plus large que lui, elle se
 * parcourt au doigt dès l'ouverture, centrée ; le pincement l'agrandit encore.
 */
@Composable
internal fun PanoramaStrip(
    image: ZoomableImageSource,
    contentDescription: String?,
    state: ZoomableImageState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ZoomableImage(
        image = image,
        contentDescription = contentDescription,
        state = state,
        contentScale = ContentScale.FillHeight,
        onClick = { onClick() },
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Déplacement de l'image (pixels, vers la droite) quand le téléphone tourne de [turnedDegrees]
 * vers la droite : un degré du panorama par degré, d'après sa couverture horizontale.
 */
internal fun panoramaShift(turnedDegrees: Double, contentWidth: Float, coverageDegrees: Double): Float {
    if (contentWidth <= 0f || coverageDegrees <= 0.0) return 0f
    return (-turnedDegrees * contentWidth / coverageDegrees).toFloat()
}
