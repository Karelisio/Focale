package app.focale.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.edit.TextItem
import app.focale.edit.TextMath
import app.focale.edit.TextRenderer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mises en page des textes de l'aperçu, gardées d'une image à l'autre : seul un texte dont le
 * contenu, la couleur, le fond ou la graisse change est recomposé ; le déplacer, l'agrandir ou le
 * faire tourner ne coûte qu'une mise à l'échelle ([TextRenderer.Prepared]).
 */
class TextLayoutCache(val faces: TextRenderer.Faces) {
    private val entries = HashMap<TextRenderer.Key, TextRenderer.Prepared>()

    operator fun get(item: TextItem): TextRenderer.Prepared = entries.getOrPut(TextRenderer.key(item)) { TextRenderer.prepare(item, faces) }

    /** Oublie les mises en page des textes disparus. */
    fun retain(items: List<TextItem>) {
        val keys = items.mapTo(HashSet(), TextRenderer::key)
        entries.keys.retainAll(keys)
    }
}

/** Cache des mises en page, police de l'application. */
@Composable
fun rememberTextLayoutCache(): TextLayoutCache {
    val context = LocalContext.current
    return remember(context) { TextLayoutCache(TextRenderer.faces(context)) }
}

/**
 * Couche de l'outil Texte posée sur l'aperçu. Elle capte et consomme tous les gestes : un appui
 * hors des textes en ajoute un à cet endroit, un appui sur un texte l'ouvre en saisie, un glisser
 * le déplace, un pincement à deux doigts l'agrandit et le fait tourner (le texte touché, sinon le
 * texte sélectionné). Le texte sélectionné est entouré d'un cadre. Chaque texte est aussi un
 * élément accessible (TalkBack, clavier) : activer le modifie, une action le supprime, la touche
 * Suppr aussi.
 */
@Composable
fun TextOverlay(
    viewport: EditorViewport,
    texts: List<TextItem>,
    layouts: TextLayoutCache,
    selected: Long?,
    onAdd: (x: Float, y: Float) -> Unit,
    onEdit: (Long) -> Unit,
    onSelect: (Long?) -> Unit,
    onGestureStart: (Long) -> Unit,
    onTransform: (id: Long, dx: Float, dy: Float, zoom: Float, rotation: Float) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val slop = with(density) { TOUCH_MARGIN.toPx() }
    val margin = with(density) { FRAME_MARGIN.toPx() }
    val ring = with(density) { 1.5.dp.toPx() }
    val corner = with(density) { 6.dp.toPx() }
    val currentViewport by rememberUpdatedState(viewport)
    val currentTexts by rememberUpdatedState(texts)
    val currentSelected by rememberUpdatedState(selected)
    val currentOnAdd by rememberUpdatedState(onAdd)
    val currentOnEdit by rememberUpdatedState(onEdit)
    val currentOnGestureStart by rememberUpdatedState(onGestureStart)
    val currentOnTransform by rememberUpdatedState(onTransform)

    /** Texte le plus haut placé sous le point (px de l'écran), ou null. */
    fun hit(position: Offset): Long? {
        val frame = currentViewport.frame
        if (!currentViewport.visible.contains(position)) return null
        for (item in currentTexts.asReversed()) {
            val prepared = layouts[item]
            val inside = TextMath.contains(
                item,
                prepared.halfWidth,
                prepared.halfHeight,
                position.x - frame.left,
                position.y - frame.top,
                frame.width,
                frame.height,
                slop,
            )
            if (inside) return item.id
        }
        return null
    }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val target = hit(down.position)
                        var last = down.position
                        var dragging = false
                        var pinching = false
                        var started = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                // Pincement : il ne redevient jamais un glisser, même après un doigt levé.
                                pinching = true
                                val id = target ?: currentSelected?.takeIf { wanted -> currentTexts.any { it.id == wanted } }
                                if (id != null) {
                                    if (!started) {
                                        currentOnGestureStart(id)
                                        started = true
                                    }
                                    val frame = currentViewport.frame
                                    val pan = event.calculatePan()
                                    currentOnTransform(id, pan.x / frame.width, pan.y / frame.height, event.calculateZoom(), event.calculateRotation())
                                }
                            } else if (!pinching && pressed == 1) {
                                val change = event.changes.first { it.pressed }
                                if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragging = true
                                if (dragging && target != null) {
                                    if (!started) {
                                        currentOnGestureStart(target)
                                        started = true
                                    }
                                    // Depuis le point d'appui au premier pas : le texte reste sous le doigt.
                                    val frame = currentViewport.frame
                                    val delta = change.position - last
                                    currentOnTransform(target, delta.x / frame.width, delta.y / frame.height, 1f, 0f)
                                    last = change.position
                                }
                            }
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                        if (!dragging && !pinching) {
                            val frame = currentViewport.frame
                            when {
                                target != null -> currentOnEdit(target)
                                currentViewport.visible.contains(down.position) ->
                                    currentOnAdd(frame.normalizedX(down.position.x), frame.normalizedY(down.position.y))
                            }
                        }
                    }
                },
        ) {
            val item = texts.firstOrNull { it.id == selected } ?: return@Canvas
            val frame = viewport.frame
            val prepared = layouts[item]
            val scale = TextMath.scale(item, frame.width, frame.height)
            val halfWidth = prepared.halfWidth * scale + margin
            val halfHeight = prepared.halfHeight * scale + margin
            val center = Offset(frame.screenX(item.x), frame.screenY(item.y))
            rotate(item.rotation, pivot = center) {
                val topLeft = center - Offset(halfWidth, halfHeight)
                val size = Size(2f * halfWidth, 2f * halfHeight)
                drawRoundRect(Color.Black.copy(alpha = 0.4f), topLeft, size, CornerRadius(corner), style = Stroke(ring * 3f))
                drawRoundRect(Color.White, topLeft, size, CornerRadius(corner), style = Stroke(ring))
            }
        }
        TextNodes(viewport, texts, layouts, selected, onEdit, onSelect, onDelete)
    }
}

/**
 * Un élément accessible par texte, à l'emplacement de son bloc : sans geste propre (la couche
 * les capte tous), il se sélectionne au clavier, s'active pour être modifié et se supprime par
 * une action TalkBack ou la touche Suppr.
 */
@Composable
private fun TextNodes(
    viewport: EditorViewport,
    texts: List<TextItem>,
    layouts: TextLayoutCache,
    selected: Long?,
    onEdit: (Long) -> Unit,
    onSelect: (Long?) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val editLabel = stringResource(R.string.text_action_edit)
    val deleteLabel = stringResource(R.string.text_action_delete)
    val frame = viewport.frame
    for (item in texts) {
        val prepared = layouts[item]
        val corners = TextMath.corners(item, prepared.halfWidth, prepared.halfHeight, frame.width, frame.height)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in 0 until 4) {
            left = min(left, corners[2 * i])
            right = max(right, corners[2 * i])
            top = min(top, corners[2 * i + 1])
            bottom = max(bottom, corners[2 * i + 1])
        }
        // Limité à la zone visible : un texte en partie recadré reste sous le doigt de TalkBack.
        val visible = viewport.visible
        left = (left + frame.left).coerceIn(visible.left, visible.right)
        right = (right + frame.left).coerceIn(visible.left, visible.right)
        top = (top + frame.top).coerceIn(visible.top, visible.bottom)
        bottom = (bottom + frame.top).coerceIn(visible.top, visible.bottom)
        if (right <= left || bottom <= top) continue
        val description = stringResource(R.string.text_item_description, item.text)
        val id = item.id
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    this.selected = id == selected
                    onClick(label = editLabel) {
                        onEdit(id)
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(deleteLabel) {
                            onDelete(id)
                            true
                        },
                    )
                }
                .onFocusChanged { if (it.isFocused) onSelect(id) }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onEdit(id)
                            true
                        }
                        Key.Delete, Key.Backspace -> {
                            onDelete(id)
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
        )
    }
}

/** Marge de saisie au doigt autour du bloc d'un texte. */
private val TOUCH_MARGIN = 8.dp

/** Écart entre le bloc du texte sélectionné et son cadre. */
private val FRAME_MARGIN = 6.dp
