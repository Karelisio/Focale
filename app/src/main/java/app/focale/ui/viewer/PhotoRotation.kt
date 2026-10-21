package app.focale.ui.viewer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isEmpty
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import app.focale.core.model.MediaItem
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.withSign
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace

/** Calculs du geste de rotation, sans état : seuil, alignement et ajustement à l'écran. */
internal object RotationMath {
    /**
     * Rotation des doigts ignorée en deçà, en degrés. Un pincement fait tourner les doigts de
     * quelques degrés, rarement de 15 ; c'est aussi l'ordre de grandeur retenu par les cartes,
     * où rotation et pincement se mêlent de la même façon (15,3° chez Mapbox). Une rotation
     * involontaire plus forte revient de toute façon à 0° : il faut dépasser 45° affichés.
     */
    const val THRESHOLD = 15f

    const val QUARTER = 90f

    /**
     * Angle affiché pour une rotation cumulée des doigts : nul jusqu'au seuil, puis il
     * rattrape les doigts d'ici le quart de tour, sans à-coup (un quart de tour des doigts
     * tourne la photo d'un quart de tour).
     */
    fun gestureAngle(fingers: Float): Float {
        val magnitude = abs(fingers)
        val shown = when {
            magnitude <= THRESHOLD -> 0f
            magnitude >= QUARTER -> magnitude
            else -> (magnitude - THRESHOLD) * QUARTER / (QUARTER - THRESHOLD)
        }
        return shown.withSign(fingers)
    }

    /** Quart de tour le plus proche d'un angle en degrés horaires : au-delà de 45°, la photo tourne. */
    fun nearestQuarter(angle: Float): Int = (angle / QUARTER).roundToInt()

    /** Tour complet le plus proche, pour revenir à l'orientation d'origine par le plus court chemin. */
    fun nearestFullTurn(quarterTurns: Int): Int = Math.floorDiv(quarterTurns + 2, 4) * 4

    /**
     * Échelle qui ajuste au cadre échangé, après un quart de tour, une photo ajustée au cadre
     * actuel : tailles de la photo affichée et du cadre, dans le repère du cadre actuel.
     */
    fun refitScale(contentWidth: Float, contentHeight: Float, frameWidth: Float, frameHeight: Float): Float =
        min(frameHeight / contentWidth, frameWidth / contentHeight)
}

/**
 * Rotation d'une photo de la visionneuse, en attente d'enregistrement.
 *
 * Pendant le geste, un calque fait tourner la photo ([angle]). Au relâchement, elle s'aligne
 * avec un ressort sur le quart de tour le plus proche ([turns]) ; après un quart de tour,
 * l'échelle l'ajuste à l'écran puis le cadre de la photo prend les dimensions de l'écran
 * échangées ([frameTurns] impair) : zoom, déplacement et bords de telephoto restent justes
 * sur la photo tournée.
 */
@Stable
class PhotoRotationState {
    /** Photo tournée, fixée au début du geste. */
    var item: MediaItem? by mutableStateOf(null)
        private set

    /** Angle affiché, en degrés horaires. */
    var angle by mutableFloatStateOf(0f)
        private set

    /** Échelle du calque, relative au cadre. */
    var scale by mutableFloatStateOf(1f)
        private set

    /** Quarts de tour horaires visés. */
    var turns by mutableIntStateOf(0)
        private set

    /** Quarts de tour du cadre : dimensions de l'écran échangées s'il est impair. */
    var frameTurns by mutableIntStateOf(0)
        private set

    var dragging by mutableStateOf(false)
        private set

    var settling by mutableStateOf(false)
        private set

    /** Enregistrement demandé, issue encore inconnue. */
    var saving by mutableStateOf(false)
        private set

    /** Rotation écrite dans le fichier : la photo reste tournée à l'écran jusqu'à sa nouvelle version. */
    var saved by mutableStateOf(false)
        private set

    /** Quarts de tour horaires à enregistrer, de 0 (rien à enregistrer) à 3. */
    val pending: Int get() = if (saved) 0 else Math.floorMod(turns, 4)

    /**
     * La prochaine version du fichier peut déjà être tournée : dès la demande, car MediaStore
     * peut la signaler avant que l'issue de l'enregistrement ne parvienne à l'écran.
     */
    val awaitsNewVersion: Boolean get() = saving || saved

    internal fun begin(photo: MediaItem) {
        item = photo
        dragging = true
    }

    internal fun drag(degrees: Float) {
        angle = degrees
    }

    internal fun release() {
        turns = RotationMath.nearestQuarter(angle)
        dragging = false
    }

    /** Quart de tour horaire sans geste (menu, accessibilité), aligné par la page comme un relâchement. */
    fun rotateClockwise(photo: MediaItem) {
        if (dragging || saving || saved) return
        if (item?.id != photo.id) reset()
        item = photo
        turns += 1
    }

    /** Retour à l'orientation d'origine, animé par la page. */
    fun cancel() {
        turns = RotationMath.nearestFullTurn(turns)
    }

    fun beginSave() {
        saving = true
    }

    /** Issue connue : [written], la photo elle-même a tourné (sinon rien n'a changé, ou une copie). */
    fun endSave(written: Boolean) {
        saving = false
        saved = written
    }

    fun reset() {
        settleJob?.cancel()
        settleJob = null
        settling = false
        item = null
        angle = 0f
        scale = 1f
        turns = 0
        frameTurns = 0
        dragging = false
        saving = false
        saved = false
    }

    /** Alignement en cours : seul le dernier lancé fait foi, [reset] l'interrompt. */
    private var settleJob: Job? = null

    /**
     * Aligne la photo sur [turns] : angle et échelle suivent le même ressort, et le zoom revient
     * à la photo entière quand le cadre change d'orientation. Le cadre n'est échangé qu'une fois
     * l'animation finie, quand l'affichage est le même des deux côtés.
     */
    internal suspend fun settle(zoomable: ZoomableState) {
        val target = turns
        val swap = Math.floorMod(target - frameTurns, 2) == 1
        val targetAngle = target * RotationMath.QUARTER
        if (!swap && angle == targetAngle && scale == 1f) return
        val targetScale = if (swap) refitScale(zoomable) else 1f
        val job = currentCoroutineContext()[Job]
        settleJob = job
        settling = true
        try {
            coroutineScope {
                launch { animate(angle, targetAngle, animationSpec = SettleSpec) { value, _ -> angle = value } }
                launch { animate(scale, targetScale, animationSpec = SettleSpec) { value, _ -> scale = value } }
                if (swap) launch { zoomable.resetZoom(SettleSpec) }
            }
            frameTurns = target
            scale = 1f
            // Tour complet : retour aux valeurs d'origine, même affichage.
            if (Math.floorMod(target, 4) == 0) {
                angle = 0f
                turns = 0
                frameTurns = 0
            }
        } finally {
            if (settleJob === job) {
                settleJob = null
                settling = false
            }
        }
    }

    private fun refitScale(zoomable: ZoomableState): Float {
        val system = zoomable.coordinateSystem
        val content = with(system) { unscaledContentBounds(clipToViewport = false).sizeIn(CoordinateSpace.Viewport) }
        val frame = system.viewportSize
        if (content.isEmpty() || frame.isEmpty()) return 1f
        return RotationMath.refitScale(content.width, content.height, frame.width, frame.height)
    }

    private companion object {
        /** Ressort « spatial » par défaut de Material 3 Expressive, seuil fin : pas de saut à l'arrivée. */
        val SettleSpec = spring(dampingRatio = 0.8f, stiffness = 380f, visibilityThreshold = 0.001f)
    }
}

/**
 * Rotation à deux doigts de [item], observée sans rien consommer : zoom, déplacement, double
 * appui, balayage et glisser pour fermer restent à telephoto et à la visionneuse. Les
 * évènements sont lus à la passe initiale, dans le repère de la page, qui ne tourne pas.
 * Pendant l'alignement, un nouveau geste est en revanche absorbé : rien ne doit bouger la
 * photo avant l'échange du cadre.
 */
internal fun Modifier.rotationGesture(state: PhotoRotationState, item: () -> MediaItem, haptics: HapticFeedback): Modifier =
    pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (state.settling) {
                down.consume()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }
            val start = state.angle
            val startTurns = state.turns
            var fingers = 0f
            var engaged = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // Geste pris par un autre (lecture d'une photo animée) : la rotation s'arrête là.
                if (event.changes.any { it.isConsumed }) break
                if (event.changes.count { it.pressed } >= 2) {
                    fingers += event.calculateRotation()
                    val shown = RotationMath.gestureAngle(fingers)
                    if (!engaged && shown != 0f) {
                        engaged = true
                        state.begin(item())
                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    }
                    if (engaged) state.drag(start + shown)
                }
            } while (event.changes.any { it.pressed })
            if (engaged) {
                state.release()
                if (state.turns != startTurns) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            }
        }
    }

/** Cadre de la photo, centré : dimensions de la page échangées tant que [swapped]. */
internal fun Modifier.rotationFrame(swapped: () -> Boolean): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(if (swapped()) Constraints.fixed(height, width) else Constraints.fixed(width, height))
    layout(width, height) { placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2) }
}
