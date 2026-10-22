package app.focale.ui.cast

import android.app.Presentation
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.focale.R
import app.focale.cast.CastEvent
import app.focale.cast.display.ExternalDisplay
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.version
import app.focale.core.model.MediaItem
import app.focale.ui.viewer.findActivity
import coil3.compose.AsyncImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Montre [content] en plein écran sur l'écran externe [display] (API `Presentation`), tant que
 * l'écran qui l'appelle est composé ; masquée quand Focale passe en arrière-plan (l'écran externe
 * revient à la recopie du téléphone), elle reparaît au retour. Le contenu partage la composition
 * (thème, états) de l'écran appelant, avec la densité et la taille de l'écran externe.
 */
@Composable
fun CastPresentation(display: ExternalDisplay, content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    val savedStateOwner = LocalSavedStateRegistryOwner.current
    val parent = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    DisposableEffect(activity, display.id, lifecycleOwner) {
        val target = activity.getSystemService(DisplayManager::class.java)?.getDisplay(display.id)
            ?: return@DisposableEffect onDispose {}
        val presentation = Presentation(activity, target, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val view = ComposeView(presentation.context).apply {
            // Identifiant propre : l'état sauvegardé de l'écran principal n'est pas partagé.
            id = View.generateViewId()
            setParentCompositionContext(parent)
            setContent { currentContent() }
        }
        presentation.setContentView(view)
        presentation.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            decorView.setViewTreeLifecycleOwner(lifecycleOwner)
            decorView.setViewTreeSavedStateRegistryOwner(savedStateOwner)
        }
        // Suivi direct du cycle de vie : en arrière-plan, la composition est suspendue et ne
        // retirerait pas la présentation d'elle-même.
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> try {
                    presentation.show()
                } catch (gone: WindowManager.InvalidDisplayException) {
                    Log.w(TAG, "Écran externe parti avant la présentation", gone)
                } catch (closing: WindowManager.BadTokenException) {
                    Log.w(TAG, "Activité refermée avant la présentation", closing)
                }
                Lifecycle.Event.ON_STOP -> presentation.hide()
                else -> Unit
            }
        }
        // Ajouté, l'observateur reçoit aussitôt ON_START si l'écran est déjà au premier plan.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            presentation.dismiss()
        }
    }
}

/**
 * Média en cours de la visionneuse sur l'écran externe : photo à la taille de l'écran (fondu
 * d'une photo à l'autre), ou vidéo jouée par le lecteur du téléphone ([player]), qui garde les
 * commandes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ViewerPresentationContent(item: MediaItem?, player: Player?) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            item == null -> Unit
            item.isVideo && player != null -> ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                keepContentOnReset = true,
                shutter = { PresentationPhoto(item, Modifier.fillMaxSize()) },
            )
            else -> PresentationPhoto(item, Modifier.fillMaxSize())
        }
    }
}

/** Photo (ou image d'une vidéo) à la taille de l'écran externe, la miniature de la grille en attente. */
@Composable
fun PresentationPhoto(item: MediaItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val metrics = LocalResources.current.displayMetrics
    AsyncImage(
        model = remember(item.id, item.dateModified, metrics.widthPixels, metrics.heightPixels) {
            ImageRequest.Builder(context)
                .data(item.uri)
                .version(item)
                .size(metrics.widthPixels, metrics.heightPixels)
                .placeholderMemoryCacheKey(
                    MemoryCache.Key(ThumbnailSizes.memoryKey(item.id, item.dateModified, ThumbnailSizes.lastGridBucket)),
                )
                .crossfade(true)
                .build()
        },
        contentDescription = item.displayName,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/**
 * Effets de la diffusion d'un écran : [item] proposé à la TV tant que l'écran est composé (rien
 * d'autre, et jamais le coffre), recherche des TV à son ouverture et à chaque retour au premier
 * plan si la dernière date, messages ([onMessage]).
 *
 * @return identité de l'écran auprès de la diffusion ([CastViewModel.present]), pour proposer un
 *   média hors composition (diaporama en arrière-plan).
 */
@Composable
fun castEffects(viewModel: CastViewModel, item: MediaItem?, onMessage: suspend (String) -> Unit): Any {
    val owner = remember { Any() }
    LaunchedEffect(owner, item) { viewModel.present(owner, item) }
    DisposableEffect(owner) { onDispose { viewModel.withdraw(owner) } }
    LifecycleStartEffect(viewModel) {
        viewModel.searchIfStale()
        onStopOrDispose {}
    }
    val resources = LocalResources.current
    val currentOnMessage by rememberUpdatedState(onMessage)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is CastEvent.Refused -> resources.getString(if (event.video) R.string.cast_refused_video else R.string.cast_refused_photo)
                is CastEvent.Lost -> resources.getString(if (event.videoOnPhone) R.string.cast_lost_video else R.string.cast_lost, event.name)
                CastEvent.NoNetwork -> resources.getString(R.string.cast_no_network)
                is CastEvent.Failed -> resources.getString(if (event.videoOnPhone) R.string.cast_failed_video else R.string.cast_failed)
            }
            currentOnMessage(text)
        }
    }
    return owner
}

private const val TAG = "CastPresentation"
